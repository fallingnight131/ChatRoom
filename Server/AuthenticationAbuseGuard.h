#pragma once

#include <QHash>
#include <QString>
#include <QtGlobal>

class AuthenticationAbuseGuard {
public:
    struct Limits {
        int windowMs = 60 * 1000;
        int gatewayAttempts = 600;
        int ipAttempts = 60;
        int accountAttempts = 10;
        int maxTrackedKeys = 4096;
    };

    struct Decision {
        bool allowed = true;
        QString dimension;
        int retryAfterMs = 0;
        quint64 allowedAttempts = 0;
        quint64 deniedAttempts = 0;
        quint64 dimensionDeniedAttempts = 0;
        int activeIpKeys = 0;
        int activeAccountKeys = 0;
    };

    explicit AuthenticationAbuseGuard(const Limits &limits = limitsFromEnvironment());

    Decision allow(const QString &peerAddress, const QString &account);
    void recordSuccess(const QString &account);
    void reset();

    const Limits &limits() const { return m_limits; }

    static Limits limitsFromEnvironment();

private:
    struct Bucket {
        qint64 windowStartedMs = 0;
        int attempts = 0;
    };

    Decision deny(const QString &dimension, int retryAfterMs);
    bool consume(Bucket &bucket, int limit, qint64 nowMs, int *retryAfterMs);
    bool consumeKeyed(QHash<QString, Bucket> &buckets, const QString &key,
                      int limit, const QString &dimension, qint64 nowMs,
                      Decision *decision);
    void cleanupExpired(qint64 nowMs);
    Decision snapshot(bool allowed, const QString &dimension = QString(),
                      int retryAfterMs = 0) const;
    static QString normalizedAccount(const QString &account);
    static QString normalizedPeer(const QString &peerAddress);

    Limits m_limits;
    Bucket m_gateway;
    QHash<QString, Bucket> m_ipBuckets;
    QHash<QString, Bucket> m_accountBuckets;
    qint64 m_lastCleanupMs = 0;
    quint64 m_allowedAttempts = 0;
    quint64 m_deniedAttempts = 0;
    QHash<QString, quint64> m_deniedByDimension;
};
