#include "UpdateInstallerTrustVerifier.h"

#include <QCryptographicHash>
#include <QFile>
#include <QFileInfo>
#include <QRegularExpression>

#include <cstring>
#include <vector>

#ifdef Q_OS_WIN
#include <windows.h>
#include <bcrypt.h>
#include <softpub.h>
#include <wincrypt.h>
#include <wintrust.h>
#endif

namespace {
using Verifier = UpdateInstallerTrustVerifier;

Verifier::Result integrityFailure(const QString &error) {
    return {Verifier::Outcome::IntegrityRejected, error};
}

#ifdef Q_OS_WIN
QString windowsStatus(const QString &prefix, LONG status) {
    return QStringLiteral("%1 (0x%2)")
        .arg(prefix, QString::number(static_cast<quint32>(status), 16));
}

QString quoteWindowsArgument(const QString &argument) {
    if (!argument.isEmpty() && !argument.contains(QRegularExpression(QStringLiteral("[\\s\"]"))))
        return argument;
    QString quoted(QLatin1Char('"'));
    int backslashes = 0;
    for (const QChar character : argument) {
        if (character == QLatin1Char('\\')) {
            ++backslashes;
            continue;
        }
        if (character == QLatin1Char('"')) {
            quoted += QString(backslashes * 2 + 1, QLatin1Char('\\'));
            quoted += character;
            backslashes = 0;
            continue;
        }
        quoted += QString(backslashes, QLatin1Char('\\'));
        backslashes = 0;
        quoted += character;
    }
    quoted += QString(backslashes * 2, QLatin1Char('\\'));
    quoted += QLatin1Char('"');
    return quoted;
}

struct LockedVerification {
    Verifier::Result result;
    HANDLE file = INVALID_HANDLE_VALUE;
};

LockedVerification verifyWindowsLocked(
        const QString &path, qint64 expectedSize,
        const QByteArray &expectedSha256,
        const QByteArray &expectedSignerSha256Thumbprint) {
    const HANDLE lockedFile = CreateFileW(
        reinterpret_cast<LPCWSTR>(path.utf16()), GENERIC_READ, FILE_SHARE_READ,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        nullptr);
    if (lockedFile == INVALID_HANDLE_VALUE)
        return {integrityFailure(QStringLiteral(
                    "update installer cannot be locked for verification")),
                INVALID_HANDLE_VALUE};

    BY_HANDLE_FILE_INFORMATION fileInfo{};
    const bool regularFile = GetFileType(lockedFile) == FILE_TYPE_DISK
        && GetFileInformationByHandle(lockedFile, &fileInfo)
        && !(fileInfo.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT);
    if (!regularFile) {
        CloseHandle(lockedFile);
        return {integrityFailure(QStringLiteral(
                    "update installer is not a regular local file")),
                INVALID_HANDLE_VALUE};
    }

    const auto integrity = Verifier::verifyIntegrity(
        path, expectedSize, expectedSha256);
    if (!integrity.valid) {
        CloseHandle(lockedFile);
        return {integrityFailure(integrity.error), INVALID_HANDLE_VALUE};
    }

    WINTRUST_FILE_INFO trustFile{};
    trustFile.cbStruct = sizeof(trustFile);
    trustFile.pcwszFilePath = reinterpret_cast<LPCWSTR>(path.utf16());
    trustFile.hFile = lockedFile;

    WINTRUST_DATA trustData{};
    trustData.cbStruct = sizeof(trustData);
    trustData.dwUIChoice = WTD_UI_NONE;
    trustData.fdwRevocationChecks = WTD_REVOKE_WHOLECHAIN;
    trustData.dwUnionChoice = WTD_CHOICE_FILE;
    trustData.pFile = &trustFile;
    trustData.dwStateAction = WTD_STATEACTION_VERIFY;
    trustData.dwProvFlags = WTD_REVOCATION_CHECK_CHAIN_EXCLUDE_ROOT
        | WTD_SAFER_FLAG;

    GUID policy = WINTRUST_ACTION_GENERIC_VERIFY_V2;
    const LONG trustStatus = WinVerifyTrust(nullptr, &policy, &trustData);
    bool timestamped = false;
    QByteArray signerThumbprint;
    if (trustStatus == ERROR_SUCCESS) {
        using ProviderDataFunction = CRYPT_PROVIDER_DATA *(WINAPI *)(HANDLE);
        using ProviderSignerFunction = CRYPT_PROVIDER_SGNR *(WINAPI *)(
            CRYPT_PROVIDER_DATA *, DWORD, BOOL, DWORD);
        const HMODULE wintrustModule = GetModuleHandleW(L"wintrust.dll");
        const auto providerData = reinterpret_cast<ProviderDataFunction>(
            wintrustModule ? GetProcAddress(wintrustModule,
                "WTHelperProvDataFromStateData") : nullptr);
        const auto providerSigner = reinterpret_cast<ProviderSignerFunction>(
            wintrustModule ? GetProcAddress(wintrustModule,
                "WTHelperGetProvSignerFromChain") : nullptr);
        auto *provider = providerData
            ? providerData(trustData.hWVTStateData) : nullptr;
        auto *signer = provider && providerSigner
            ? providerSigner(provider, 0, FALSE, 0) : nullptr;
        if (signer && signer->csCertChain > 0 && signer->pasCertChain
                && signer->pasCertChain[0].pCert && signer->psSigner) {
            bool hasRfc3161Attribute = false;
            const auto &attributes = signer->psSigner->UnauthAttrs;
            for (DWORD index = 0; index < attributes.cAttr; ++index) {
                const auto &attribute = attributes.rgAttr[index];
                if (attribute.pszObjId
                        && std::strcmp(attribute.pszObjId,
                                       szOID_RFC3161_counterSign) == 0
                        && attribute.cValue > 0) {
                    hasRfc3161Attribute = true;
                    break;
                }
            }
            BYTE digest[32]{};
            DWORD digestSize = sizeof(digest);
            const auto *certificate = signer->pasCertChain[0].pCert;
            if (CryptHashCertificate2(BCRYPT_SHA256_ALGORITHM, 0, nullptr,
                                      certificate->pbCertEncoded,
                                      certificate->cbCertEncoded,
                                      digest, &digestSize)
                    && digestSize == sizeof(digest)) {
                signerThumbprint = QByteArray(
                    reinterpret_cast<const char *>(digest), sizeof(digest));
            }
            for (DWORD index = 0; providerSigner
                    && index < signer->csCounterSigners; ++index) {
                const auto *counterSigner = providerSigner(provider, 0, TRUE, index);
                if (hasRfc3161Attribute && counterSigner
                        && counterSigner->dwError == ERROR_SUCCESS
                        && counterSigner->psSigner) {
                    timestamped = true;
                    break;
                }
            }
        }
    }

    trustData.dwStateAction = WTD_STATEACTION_CLOSE;
    WinVerifyTrust(nullptr, &policy, &trustData);
    if (trustStatus != ERROR_SUCCESS) {
        CloseHandle(lockedFile);
        return {{Verifier::Outcome::AuthenticodeRejected,
                 windowsStatus(QStringLiteral("Windows rejected Authenticode"),
                               trustStatus)}, INVALID_HANDLE_VALUE};
    }
    if (!timestamped) {
        CloseHandle(lockedFile);
        return {{Verifier::Outcome::AuthenticodeRejected,
                 QStringLiteral("update installer has no valid Authenticode timestamp")},
                INVALID_HANDLE_VALUE};
    }
    if (signerThumbprint != expectedSignerSha256Thumbprint) {
        CloseHandle(lockedFile);
        return {{Verifier::Outcome::AuthenticodeRejected,
                 QStringLiteral("update installer signer does not match manifest")},
                INVALID_HANDLE_VALUE};
    }
    return {{Verifier::Outcome::Verified, {}}, lockedFile};
}
#endif
}

UpdateInstallerTrustVerifier::IntegrityResult
UpdateInstallerTrustVerifier::verifyIntegrity(
        const QString &path,
        qint64 expectedSize,
        const QByteArray &expectedSha256) {
    const QFileInfo info(path);
    if (!info.isAbsolute() || !info.exists() || !info.isFile() || info.isSymLink()
            || expectedSize <= 0 || expectedSize > 9007199254740991LL
            || expectedSha256.size() != QCryptographicHash::hashLength(
                QCryptographicHash::Sha256)) {
        return {false, QStringLiteral("update installer integrity input is invalid")};
    }

    QFile file(path);
    if (!file.open(QIODevice::ReadOnly) || file.size() != expectedSize)
        return {false, QStringLiteral("update installer size does not match manifest")};

    QCryptographicHash hash(QCryptographicHash::Sha256);
    if (!hash.addData(&file) || file.error() != QFile::NoError)
        return {false, QStringLiteral("update installer could not be hashed")};
    if (hash.result() != expectedSha256)
        return {false, QStringLiteral("update installer SHA-256 does not match manifest")};
    return {true, {}};
}

UpdateInstallerTrustVerifier::Result UpdateInstallerTrustVerifier::verify(
        const QString &path,
        qint64 expectedSize,
        const QByteArray &expectedSha256,
        const QByteArray &expectedSignerSha256Thumbprint) {
    if (expectedSignerSha256Thumbprint.size() != 32)
        return {Outcome::AuthenticodeRejected,
                QStringLiteral("update signer thumbprint is invalid")};

#ifndef Q_OS_WIN
    const auto integrity = verifyIntegrity(path, expectedSize, expectedSha256);
    if (!integrity.valid) return integrityFailure(integrity.error);
    return {Outcome::UnsupportedPlatform,
            QStringLiteral("Authenticode verification requires Windows")};
#else
    auto verified = verifyWindowsLocked(
        path, expectedSize, expectedSha256, expectedSignerSha256Thumbprint);
    if (verified.file != INVALID_HANDLE_VALUE) CloseHandle(verified.file);
    return verified.result;
#endif
}

UpdateInstallerTrustVerifier::LaunchResult
UpdateInstallerTrustVerifier::verifyLaunchAndWait(
        const QString &path, qint64 expectedSize,
        const QByteArray &expectedSha256,
        const QByteArray &expectedSignerSha256Thumbprint,
        int waitTimeoutMs) {
    if (waitTimeoutMs <= 0) {
        return {LaunchOutcome::StartFailed, 0,
                QStringLiteral("update installer wait timeout is invalid")};
    }
#ifndef Q_OS_WIN
    const auto trust = verify(path, expectedSize, expectedSha256,
                              expectedSignerSha256Thumbprint);
    return {trust.outcome == Outcome::UnsupportedPlatform
                ? LaunchOutcome::UnsupportedPlatform
                : LaunchOutcome::TrustRejected,
            0, trust.error};
#else
    if (expectedSignerSha256Thumbprint.size() != 32) {
        return {LaunchOutcome::TrustRejected, 0,
                QStringLiteral("update signer thumbprint is invalid")};
    }
    auto verified = verifyWindowsLocked(
        path, expectedSize, expectedSha256, expectedSignerSha256Thumbprint);
    if (verified.result.outcome != Outcome::Verified) {
        return {LaunchOutcome::TrustRejected, 0, verified.result.error};
    }

    const QString commandLine = quoteWindowsArgument(path)
        + QStringLiteral(" /S");
    std::vector<wchar_t> mutableCommand(
        reinterpret_cast<const wchar_t *>(commandLine.utf16()),
        reinterpret_cast<const wchar_t *>(commandLine.utf16())
            + commandLine.size());
    mutableCommand.push_back(L'\0');

    STARTUPINFOW startup{};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process{};
    const BOOL started = CreateProcessW(
        reinterpret_cast<LPCWSTR>(path.utf16()), mutableCommand.data(),
        nullptr, nullptr, FALSE, CREATE_UNICODE_ENVIRONMENT,
        nullptr, nullptr, &startup, &process);
    const DWORD startError = started ? ERROR_SUCCESS : GetLastError();
    CloseHandle(verified.file);
    if (!started) {
        return {LaunchOutcome::StartFailed, 0,
                windowsStatus(QStringLiteral("verified update installer could not start"),
                              static_cast<LONG>(startError))};
    }

    CloseHandle(process.hThread);
    const DWORD wait = WaitForSingleObject(
        process.hProcess, static_cast<DWORD>(waitTimeoutMs));
    if (wait == WAIT_TIMEOUT) {
        CloseHandle(process.hProcess);
        return {LaunchOutcome::TimedOut, 0,
                QStringLiteral("update installer did not exit before timeout")};
    }
    if (wait != WAIT_OBJECT_0) {
        CloseHandle(process.hProcess);
        return {LaunchOutcome::WaitFailed, 0,
                windowsStatus(QStringLiteral("update installer wait failed"),
                              static_cast<LONG>(GetLastError()))};
    }
    DWORD exitCode = 0;
    if (!GetExitCodeProcess(process.hProcess, &exitCode)) {
        CloseHandle(process.hProcess);
        return {LaunchOutcome::WaitFailed, 0,
                windowsStatus(QStringLiteral("update installer exit code is unavailable"),
                              static_cast<LONG>(GetLastError()))};
    }
    CloseHandle(process.hProcess);
    return {LaunchOutcome::Exited, exitCode, {}};
#endif
}
