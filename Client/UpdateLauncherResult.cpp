#include "UpdateLauncherResult.h"

#include <QJsonDocument>
#include <QJsonObject>
#include <QHash>
#include <QRegularExpression>
#include <QSet>
#include <QUuid>

#include <cmath>

namespace {
constexpr int MaxResultBytes = 16 * 1024;
constexpr int MaxErrorCharacters = 1024;
constexpr int ClockSkewSeconds = 5 * 60;

const QSet<QString> ResultKeys{
    QStringLiteral("schemaVersion"), QStringLiteral("requestId"),
    QStringLiteral("outcome"), QStringLiteral("installerExitCode"),
    QStringLiteral("recordedAt"), QStringLiteral("error")};

bool exactKeys(const QJsonObject &object) {
    QSet<QString> actual;
    for (const QString &key : object.keys()) actual.insert(key);
    return actual == ResultKeys;
}

bool canonicalRequestId(const QString &value) {
    const QUuid uuid(value);
    return !uuid.isNull()
        && uuid.toString(QUuid::WithoutBraces).toLower() == value;
}

bool safeExitCode(const QJsonValue &value, quint32 *result) {
    if (!value.isDouble() || !result) return false;
    const double number = value.toDouble();
    if (!std::isfinite(number) || std::floor(number) != number
            || number < 0 || number > 4294967295.0) return false;
    *result = static_cast<quint32>(number);
    return true;
}

bool parseOutcome(const QString &name, UpdateLauncherResult::Outcome *outcome) {
    using Outcome = UpdateLauncherResult::Outcome;
    static const QHash<QString, Outcome> Outcomes{
        {QStringLiteral("installed"), Outcome::Installed},
        {QStringLiteral("installer-failed"), Outcome::InstallerFailed},
        {QStringLiteral("trust-rejected"), Outcome::TrustRejected},
        {QStringLiteral("start-failed"), Outcome::StartFailed},
        {QStringLiteral("installer-timeout"), Outcome::InstallerTimeout},
        {QStringLiteral("installer-wait-failed"), Outcome::InstallerWaitFailed},
        {QStringLiteral("parent-open-failed"), Outcome::ParentOpenFailed},
        {QStringLiteral("handshake-failed"), Outcome::HandshakeFailed},
        {QStringLiteral("handoff-aborted"), Outcome::HandoffAborted},
        {QStringLiteral("parent-timeout"), Outcome::ParentTimeout},
        {QStringLiteral("parent-wait-failed"), Outcome::ParentWaitFailed},
        {QStringLiteral("unsupported-platform"), Outcome::UnsupportedPlatform}
    };
    const auto found = Outcomes.constFind(name);
    if (found == Outcomes.cend() || !outcome) return false;
    *outcome = found.value();
    return true;
}

bool safeError(const QJsonValue &value, QString *error) {
    if (!value.isString() || !error) return false;
    const QString text = value.toString();
    if (text.size() > MaxErrorCharacters) return false;
    for (const QChar character : text) {
        if (character.unicode() < 0x20 || character.unicode() == 0x7f)
            return false;
    }
    *error = text;
    return true;
}

bool exactUtc(const QString &text, QDateTime *result) {
    if (!result || !text.endsWith(QLatin1Char('Z'))) return false;
    const QDateTime parsed = QDateTime::fromString(text, Qt::ISODate);
    if (!parsed.isValid() || parsed.offsetFromUtc() != 0
            || parsed.toUTC().toString(Qt::ISODate) != text) return false;
    *result = parsed.toUTC();
    return true;
}

void fail(QString *error, const QString &message) {
    if (error) *error = message;
}
}

bool UpdateLauncherResult::parse(
        const QByteArray &bytes, const QString &expectedRequestId,
        const QDateTime &notBeforeUtc, const QDateTime &nowUtc,
        Value *result, QString *error) {
    if (error) error->clear();
    if (result) *result = {};
    if (!result || bytes.isEmpty() || bytes.size() > MaxResultBytes
            || !canonicalRequestId(expectedRequestId)
            || !notBeforeUtc.isValid() || notBeforeUtc.timeSpec() != Qt::UTC
            || !nowUtc.isValid() || nowUtc.timeSpec() != Qt::UTC
            || notBeforeUtc > nowUtc) {
        fail(error, QStringLiteral("update launcher result context is invalid"));
        return false;
    }

    QJsonParseError parseError;
    const QJsonDocument document = QJsonDocument::fromJson(bytes, &parseError);
    if (parseError.error != QJsonParseError::NoError || !document.isObject()) {
        fail(error, QStringLiteral("update launcher result JSON is invalid"));
        return false;
    }
    const QJsonObject object = document.object();
    Value parsed;
    const QString requestId = object.value(QStringLiteral("requestId")).toString();
    const QString outcome = object.value(QStringLiteral("outcome")).toString();
    const QString recordedAt = object.value(QStringLiteral("recordedAt")).toString();
    if (!exactKeys(object)
            || object.value(QStringLiteral("schemaVersion")).toInt(-1) != 1
            || requestId != expectedRequestId
            || !parseOutcome(outcome, &parsed.outcome)
            || !safeExitCode(object.value(QStringLiteral("installerExitCode")),
                             &parsed.installerExitCode)
            || !exactUtc(recordedAt, &parsed.recordedAtUtc)
            || !safeError(object.value(QStringLiteral("error")), &parsed.error)
            || parsed.recordedAtUtc < notBeforeUtc.addSecs(-ClockSkewSeconds)
            || parsed.recordedAtUtc > nowUtc.addSecs(ClockSkewSeconds)
            || (parsed.outcome == Outcome::Installed
                && parsed.installerExitCode != 0)
            || (parsed.outcome == Outcome::InstallerFailed
                && parsed.installerExitCode == 0)
            || (parsed.outcome != Outcome::Installed
                && parsed.outcome != Outcome::InstallerFailed
                && parsed.installerExitCode != 0)) {
        fail(error, QStringLiteral("update launcher result policy rejected the record"));
        return false;
    }
    parsed.requestId = requestId;
    *result = parsed;
    return true;
}

QString UpdateLauncherResult::outcomeName(Outcome outcome) {
    switch (outcome) {
    case Outcome::Installed: return QStringLiteral("installed");
    case Outcome::InstallerFailed: return QStringLiteral("installer-failed");
    case Outcome::TrustRejected: return QStringLiteral("trust-rejected");
    case Outcome::StartFailed: return QStringLiteral("start-failed");
    case Outcome::InstallerTimeout: return QStringLiteral("installer-timeout");
    case Outcome::InstallerWaitFailed: return QStringLiteral("installer-wait-failed");
    case Outcome::ParentOpenFailed: return QStringLiteral("parent-open-failed");
    case Outcome::HandshakeFailed: return QStringLiteral("handshake-failed");
    case Outcome::HandoffAborted: return QStringLiteral("handoff-aborted");
    case Outcome::ParentTimeout: return QStringLiteral("parent-timeout");
    case Outcome::ParentWaitFailed: return QStringLiteral("parent-wait-failed");
    case Outcome::UnsupportedPlatform: return QStringLiteral("unsupported-platform");
    }
    return {};
}
