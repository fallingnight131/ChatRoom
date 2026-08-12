#include "UpdateManifestDecisionPolicy.h"

#include <QCryptographicHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QRegularExpression>
#include <QSet>
#include <QUrl>

#include <array>
#include <cmath>

namespace {
using Outcome = UpdateManifestDecisionPolicy::Outcome;
using Decision = UpdateManifestDecisionPolicy::Decision;

const QRegularExpression SemVer(QStringLiteral(
    R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$)"));
const QRegularExpression Timestamp(QStringLiteral(
    R"(^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$)"));
const QRegularExpression Hex64(QStringLiteral(R"(^[0-9a-f]{64}$)"));
const QRegularExpression Revision(QStringLiteral(R"(^[0-9a-f]{40}$)"));
const QRegularExpression KeyId(QStringLiteral(R"(^[a-z0-9][a-z0-9.-]{0,63}$)"));

Decision reject(const QString &reason) {
    Decision decision;
    decision.error = reason;
    return decision;
}

bool exactKeys(const QJsonObject &object, const QSet<QString> &expected) {
    QSet<QString> actual;
    for (const auto &key : object.keys()) actual.insert(key);
    return actual == expected;
}

bool parseVersion(const QString &value, std::array<int, 3> *parts) {
    const auto match = SemVer.match(value);
    if (!match.hasMatch() || !parts) return false;
    for (int index = 0; index < 3; ++index) {
        bool ok = false;
        const qlonglong parsed = match.captured(index + 1).toLongLong(&ok);
        if (!ok || parsed > 65535) return false;
        (*parts)[index] = static_cast<int>(parsed);
    }
    return true;
}

int compareVersion(const std::array<int, 3> &left, const std::array<int, 3> &right) {
    for (int index = 0; index < 3; ++index) {
        if (left[index] < right[index]) return -1;
        if (left[index] > right[index]) return 1;
    }
    return 0;
}

bool parseUtc(const QString &value, QDateTime *result) {
    if (!Timestamp.match(value).hasMatch() || !result) return false;
    const auto parsed = QDateTime::fromString(value, Qt::ISODate);
    if (!parsed.isValid() || parsed.offsetFromUtc() != 0) return false;
    *result = parsed.toUTC();
    return result->toString(Qt::ISODate) == value;
}

bool safeInteger(const QJsonValue &value, qint64 minimum, qint64 maximum, qint64 *result) {
    if (!value.isDouble() || !result) return false;
    const double number = value.toDouble();
    if (!std::isfinite(number) || std::floor(number) != number
            || number < static_cast<double>(minimum) || number > static_cast<double>(maximum)) return false;
    *result = static_cast<qint64>(number);
    return true;
}

int rolloutBucket(const QString &deviceId, const QByteArray &seed) {
    QByteArray input = deviceId.toUtf8();
    input += '\0';
    input += seed;
    const QByteArray digest = QCryptographicHash::hash(input, QCryptographicHash::Sha256);
    quint64 prefix = 0;
    for (int index = 0; index < 8; ++index)
        prefix = (prefix << 8) | static_cast<unsigned char>(digest.at(index));
    return static_cast<int>(prefix % 100);
}
}

UpdateManifestDecisionPolicy::Decision UpdateManifestDecisionPolicy::evaluate(
        const QByteArray &canonicalManifest,
        const QJsonObject &manifest,
        const Context &context) {
    static const QSet<QString> ManifestKeys = {
        QStringLiteral("schemaVersion"), QStringLiteral("product"), QStringLiteral("channel"),
        QStringLiteral("architecture"),
        QStringLiteral("manifestSequence"), QStringLiteral("signingKeyId"),
        QStringLiteral("publishedAt"), QStringLiteral("expiresAt"), QStringLiteral("version"),
        QStringLiteral("minimumUpdatableVersion"), QStringLiteral("sourceRevision"),
        QStringLiteral("rollout"), QStringLiteral("installer")
    };
    QJsonParseError parseError;
    const auto signedDocument = QJsonDocument::fromJson(canonicalManifest, &parseError);
    if (canonicalManifest.isEmpty() || canonicalManifest.size() > 64 * 1024
            || parseError.error != QJsonParseError::NoError || !signedDocument.isObject()
            || signedDocument.object() != manifest
            || !exactKeys(manifest, ManifestKeys)
            || manifest.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
            || manifest.value(QStringLiteral("product")).toString()
                != QStringLiteral("chat-room-windows-client")
            || manifest.value(QStringLiteral("architecture")).toString() != QStringLiteral("x86_64"))
        return reject(QStringLiteral("update manifest shape is invalid"));

    const QString channel = manifest.value(QStringLiteral("channel")).toString();
    const QString keyId = manifest.value(QStringLiteral("signingKeyId")).toString();
    if ((channel != QStringLiteral("stable") && channel != QStringLiteral("beta"))
            || channel != context.channel || !KeyId.match(keyId).hasMatch())
        return reject(QStringLiteral("update channel or key identity is invalid"));

    qint64 sequence = 0;
    if (!safeInteger(manifest.value(QStringLiteral("manifestSequence")), 1, 9007199254740991LL, &sequence)
            || context.highestAcceptedSequence < 0
            || (context.highestAcceptedSequence == 0 && !context.highestAcceptedManifestSha256.isEmpty())
            || (context.highestAcceptedSequence > 0 && context.highestAcceptedManifestSha256.size() != 32))
        return reject(QStringLiteral("update sequence state is invalid"));

    const QByteArray manifestDigest = QCryptographicHash::hash(canonicalManifest, QCryptographicHash::Sha256);
    if (sequence < context.highestAcceptedSequence
            || (sequence == context.highestAcceptedSequence
                && manifestDigest != context.highestAcceptedManifestSha256))
        return reject(QStringLiteral("update manifest replay or sequence conflict"));

    std::array<int, 3> current{}, target{}, minimum{};
    const QString targetText = manifest.value(QStringLiteral("version")).toString();
    if (!parseVersion(context.currentVersion, &current) || !parseVersion(targetText, &target)
            || !parseVersion(manifest.value(QStringLiteral("minimumUpdatableVersion")).toString(), &minimum)
            || compareVersion(minimum, target) > 0)
        return reject(QStringLiteral("update version policy is invalid"));

    QDateTime published, expires;
    const QDateTime now = context.nowUtc.toUTC();
    if (!context.nowUtc.isValid() || context.nowUtc.timeSpec() != Qt::UTC
            || !parseUtc(manifest.value(QStringLiteral("publishedAt")).toString(), &published)
            || !parseUtc(manifest.value(QStringLiteral("expiresAt")).toString(), &expires)
            || expires <= published || published.secsTo(expires) > 31 * 24 * 60 * 60
            || now < published || now >= expires)
        return reject(QStringLiteral("update manifest validity window is invalid"));

    if (!Revision.match(manifest.value(QStringLiteral("sourceRevision")).toString()).hasMatch())
        return reject(QStringLiteral("update source identity is invalid"));

    const auto rollout = manifest.value(QStringLiteral("rollout")).toObject();
    qint64 percentage = -1;
    const QString seedText = rollout.value(QStringLiteral("seed")).toString();
    if (!exactKeys(rollout, {QStringLiteral("percentage"), QStringLiteral("seed")})
            || !safeInteger(rollout.value(QStringLiteral("percentage")), 0, 100, &percentage)
            || !Hex64.match(seedText).hasMatch()
            || context.stableDeviceId.isEmpty() || context.stableDeviceId.size() > 128
            || context.stableDeviceId.contains(QRegularExpression(QStringLiteral(R"([\x00-\x1f\x7f])"))))
        return reject(QStringLiteral("update rollout policy is invalid"));

    const auto installer = manifest.value(QStringLiteral("installer")).toObject();
    static const QSet<QString> InstallerKeys = {
        QStringLiteral("url"), QStringLiteral("size"), QStringLiteral("sha256"),
        QStringLiteral("authenticodeSha256Thumbprint")
    };
    qint64 installerSize = 0;
    const QString urlText = installer.value(QStringLiteral("url")).toString();
    const QUrl url(urlText, QUrl::StrictMode);
    const QString expectedName = QStringLiteral("ChatRoom-%1-Setup.exe").arg(targetText);
    const QString digestText = installer.value(QStringLiteral("sha256")).toString();
    const QString thumbprintText = installer.value(QStringLiteral("authenticodeSha256Thumbprint")).toString();
    if (!exactKeys(installer, InstallerKeys)
            || !safeInteger(installer.value(QStringLiteral("size")), 1, 9007199254740991LL, &installerSize)
            || !Hex64.match(digestText).hasMatch() || !Hex64.match(thumbprintText).hasMatch()
            || !url.isValid() || url.scheme() != QStringLiteral("https") || url.host().isEmpty()
            || !url.userInfo().isEmpty() || url.hasQuery() || url.hasFragment()
            || url.fileName() != expectedName || url.toEncoded() != urlText.toUtf8()
            || url.path().contains(QStringLiteral("//"))
            || url.path().split('/').contains(QStringLiteral("..")))
        return reject(QStringLiteral("update installer policy is invalid"));

    Decision decision;
    decision.targetVersion = targetText;
    decision.installerUrl = urlText;
    decision.installerSize = installerSize;
    decision.installerSha256 = QByteArray::fromHex(digestText.toLatin1());
    decision.authenticodeSha256Thumbprint = QByteArray::fromHex(thumbprintText.toLatin1());
    decision.acceptedSequence = sequence;
    decision.acceptedManifestSha256 = manifestDigest;
    decision.rolloutBucket = rolloutBucket(context.stableDeviceId, QByteArray::fromHex(seedText.toLatin1()));

    if (compareVersion(target, current) <= 0)
        decision.outcome = Outcome::NoUpdate;
    else if (compareVersion(current, minimum) < 0)
        decision.outcome = Outcome::ManualUpdateRequired;
    else if (decision.rolloutBucket >= percentage)
        decision.outcome = Outcome::DeferredByRollout;
    else
        decision.outcome = Outcome::Eligible;
    return decision;
}
