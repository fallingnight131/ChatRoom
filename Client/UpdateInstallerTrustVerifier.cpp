#include "UpdateInstallerTrustVerifier.h"

#include <QCryptographicHash>
#include <QFile>
#include <QFileInfo>

#include <cstring>

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
    const HANDLE lockedFile = CreateFileW(
        reinterpret_cast<LPCWSTR>(path.utf16()), GENERIC_READ, FILE_SHARE_READ,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL | FILE_FLAG_SEQUENTIAL_SCAN,
        nullptr);
    if (lockedFile == INVALID_HANDLE_VALUE)
        return integrityFailure(QStringLiteral("update installer cannot be locked for verification"));

    BY_HANDLE_FILE_INFORMATION fileInfo{};
    const bool regularFile = GetFileType(lockedFile) == FILE_TYPE_DISK
        && GetFileInformationByHandle(lockedFile, &fileInfo)
        && !(fileInfo.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT);
    if (!regularFile) {
        CloseHandle(lockedFile);
        return integrityFailure(QStringLiteral("update installer is not a regular local file"));
    }

    const auto integrity = verifyIntegrity(path, expectedSize, expectedSha256);
    if (!integrity.valid) {
        CloseHandle(lockedFile);
        return integrityFailure(integrity.error);
    }

    WINTRUST_FILE_INFO trustFile{};
    trustFile.cbStruct = sizeof(trustFile);
    trustFile.pcwszFilePath = reinterpret_cast<LPCWSTR>(path.utf16());

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
    CloseHandle(lockedFile);

    if (trustStatus != ERROR_SUCCESS)
        return {Outcome::AuthenticodeRejected,
                windowsStatus(QStringLiteral("Windows rejected Authenticode"), trustStatus)};
    if (!timestamped)
        return {Outcome::AuthenticodeRejected,
                QStringLiteral("update installer has no valid Authenticode timestamp")};
    if (signerThumbprint != expectedSignerSha256Thumbprint)
        return {Outcome::AuthenticodeRejected,
                QStringLiteral("update installer signer does not match manifest")};
    return {Outcome::Verified, {}};
#endif
}
