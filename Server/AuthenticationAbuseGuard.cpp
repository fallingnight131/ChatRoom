#include "AuthenticationAbuseGuard.h"

#include <QDateTime>
#include <QHostAddress>

namespace {

int boundedEnvironmentInt(const char *name, int fallback, int minimum, int maximum) {
    bool ok = false;
    const int value = qEnvironmentVariableIntValue(name, &ok);
    if (!ok || value < minimum || value > maximum) {
        return fallback;
    }
    return value;
}

} // namespace

AuthenticationAbuseGuard::AuthenticationAbuseGuard(const Limits &limits)
    : m_limits(limits)
{
}

AuthenticationAbuseGuard::Limits AuthenticationAbuseGuard::limitsFromEnvironment() {
    Limits limits;
    limits.windowMs = boundedEnvironmentInt(
        "CHATROOM_AUTH_WINDOW_MS", limits.windowMs, 1000, 60 * 60 * 1000);
    limits.gatewayAttempts = boundedEnvironmentInt(
        "CHATROOM_AUTH_GATEWAY_ATTEMPTS", limits.gatewayAttempts, 1, 1000000);
    limits.ipAttempts = boundedEnvironmentInt(
        "CHATROOM_AUTH_IP_ATTEMPTS", limits.ipAttempts, 1, 100000);
    limits.accountAttempts = boundedEnvironmentInt(
        "CHATROOM_AUTH_ACCOUNT_ATTEMPTS", limits.accountAttempts, 1, 10000);
    limits.maxTrackedKeys = boundedEnvironmentInt(
        "CHATROOM_AUTH_MAX_TRACKED_KEYS", limits.maxTrackedKeys, 16, 1000000);
    return limits;
}

AuthenticationAbuseGuard::Decision AuthenticationAbuseGuard::allow(
    const QString &peerAddress, const QString &account)
{
    const qint64 nowMs = QDateTime::currentMSecsSinceEpoch();
    cleanupExpired(nowMs);

    int retryAfterMs = 0;
    if (!consume(m_gateway, m_limits.gatewayAttempts, nowMs, &retryAfterMs)) {
        return deny(QStringLiteral("gateway"), retryAfterMs);
    }

    Decision decision;
    if (!consumeKeyed(m_ipBuckets, normalizedPeer(peerAddress), m_limits.ipAttempts,
                      QStringLiteral("ip"), nowMs, &decision)) {
        return decision;
    }

    if (!consumeKeyed(m_accountBuckets, normalizedAccount(account),
                      m_limits.accountAttempts, QStringLiteral("account"),
                      nowMs, &decision)) {
        return decision;
    }

    ++m_allowedAttempts;
    return snapshot(true);
}

void AuthenticationAbuseGuard::recordSuccess(const QString &account) {
    m_accountBuckets.remove(normalizedAccount(account));
}

void AuthenticationAbuseGuard::reset() {
    m_gateway = Bucket();
    m_ipBuckets.clear();
    m_accountBuckets.clear();
    m_lastCleanupMs = 0;
    m_allowedAttempts = 0;
    m_deniedAttempts = 0;
    m_deniedByDimension.clear();
}

AuthenticationAbuseGuard::Decision AuthenticationAbuseGuard::deny(
    const QString &dimension, int retryAfterMs)
{
    ++m_deniedAttempts;
    ++m_deniedByDimension[dimension];
    return snapshot(false, dimension, retryAfterMs);
}

bool AuthenticationAbuseGuard::consume(Bucket &bucket, int limit, qint64 nowMs,
                                       int *retryAfterMs)
{
    const qint64 elapsed = nowMs - bucket.windowStartedMs;
    if (bucket.windowStartedMs == 0 || elapsed < 0 || elapsed >= m_limits.windowMs) {
        bucket.windowStartedMs = nowMs;
        bucket.attempts = 0;
    }

    if (bucket.attempts >= limit) {
        if (retryAfterMs) {
            *retryAfterMs = qMax(1, m_limits.windowMs
                                    - static_cast<int>(nowMs - bucket.windowStartedMs));
        }
        return false;
    }

    ++bucket.attempts;
    return true;
}

bool AuthenticationAbuseGuard::consumeKeyed(
    QHash<QString, Bucket> &buckets, const QString &key, int limit,
    const QString &dimension, qint64 nowMs, Decision *decision)
{
    auto it = buckets.find(key);
    if (it == buckets.end()) {
        if (buckets.size() >= m_limits.maxTrackedKeys) {
            cleanupExpired(nowMs);
        }
        if (buckets.size() >= m_limits.maxTrackedKeys) {
            *decision = deny(dimension + QStringLiteral("-capacity"), m_limits.windowMs);
            return false;
        }
        it = buckets.insert(key, Bucket());
    }

    int retryAfterMs = 0;
    if (!consume(it.value(), limit, nowMs, &retryAfterMs)) {
        *decision = deny(dimension, retryAfterMs);
        return false;
    }
    return true;
}

void AuthenticationAbuseGuard::cleanupExpired(qint64 nowMs) {
    if (m_lastCleanupMs != 0 && nowMs >= m_lastCleanupMs
        && nowMs - m_lastCleanupMs < m_limits.windowMs) {
        return;
    }
    m_lastCleanupMs = nowMs;

    const auto expired = [this, nowMs](const Bucket &bucket) {
        const qint64 elapsed = nowMs - bucket.windowStartedMs;
        return bucket.windowStartedMs == 0 || elapsed < 0 || elapsed >= m_limits.windowMs;
    };

    for (auto it = m_ipBuckets.begin(); it != m_ipBuckets.end(); ) {
        if (expired(it.value())) {
            it = m_ipBuckets.erase(it);
        } else {
            ++it;
        }
    }
    for (auto it = m_accountBuckets.begin(); it != m_accountBuckets.end(); ) {
        if (expired(it.value())) {
            it = m_accountBuckets.erase(it);
        } else {
            ++it;
        }
    }
}

AuthenticationAbuseGuard::Decision AuthenticationAbuseGuard::snapshot(
    bool allowed, const QString &dimension, int retryAfterMs) const
{
    Decision decision;
    decision.allowed = allowed;
    decision.dimension = dimension;
    decision.retryAfterMs = retryAfterMs;
    decision.allowedAttempts = m_allowedAttempts;
    decision.deniedAttempts = m_deniedAttempts;
    decision.dimensionDeniedAttempts = m_deniedByDimension.value(dimension);
    decision.activeIpKeys = m_ipBuckets.size();
    decision.activeAccountKeys = m_accountBuckets.size();
    return decision;
}

QString AuthenticationAbuseGuard::normalizedAccount(const QString &account) {
    const QString normalized = account.trimmed().toCaseFolded();
    return normalized.isEmpty() ? QStringLiteral("<unknown>") : normalized;
}

QString AuthenticationAbuseGuard::normalizedPeer(const QString &peerAddress) {
    QHostAddress address;
    if (!address.setAddress(peerAddress)) {
        return QStringLiteral("<unknown>");
    }

    bool isIpv4 = false;
    const quint32 ipv4 = address.toIPv4Address(&isIpv4);
    return isIpv4 ? QHostAddress(ipv4).toString() : address.toString();
}
