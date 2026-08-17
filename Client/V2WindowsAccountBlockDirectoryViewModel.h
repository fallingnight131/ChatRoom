#pragma once

#include <QObject>
#include <QString>
#include <QVector>
#include <functional>

class V2WindowsAccountBlockDirectoryViewModel final : public QObject {
    Q_OBJECT
public:
    static constexpr qsizetype MaxRows = 500;
    enum class Failure {
        None, SessionEnded, RefreshNotSent, LoadMoreNotSent,
        RetryableRequestFailed, RequestFailed, ServiceUnavailable
    };
    enum class MutationFailure { None, NotSent, Retryable, Failed, Disconnected };
    struct Row {
        QString targetAccountId;
        QString targetDisplayName;
        qint64 blockedAtEpochMs = 0;
    };
    using List = std::function<bool(const QString &afterTargetAccountId)>;
    using Unblock = std::function<bool(
        const QString &targetAccountId, const QString &clientOperationId)>;

    explicit V2WindowsAccountBlockDirectoryViewModel(
        List list, Unblock unblock, QObject *parent = nullptr);
    QVector<Row> rows() const { return m_rows; }
    bool available() const { return m_available; }
    bool busy() const { return m_busy; }
    bool hasMore() const { return m_hasMore; }
    Failure failure() const { return m_failure; }
    QString failureDetail() const { return m_failureDetail; }
    bool mutationPending() const { return m_mutationPending; }
    MutationFailure mutationFailure() const { return m_mutationFailure; }

    void bindSession(bool clearRows);
    void clearSession();
    bool refresh();
    bool loadMore();
    bool canUnblock(const QString &targetAccountId) const;
    bool requestUnblock(const QString &targetAccountId);
    bool ownsOperation(const QString &clientOperationId) const;
    void applyUnblockResult(const QString &targetAccountId,
                            const QString &clientOperationId);
    void applyUnblockFailure(const QString &clientOperationId, bool retryable);
    void applyPage(QVector<Row> rows, const QString &nextAfterTargetAccountId,
                   bool hasMore);
    void applyFailure(bool retryable, const QString &safeReason = {});
    void setUnavailable();

signals:
    void changed();

private:
    List m_list;
    Unblock m_unblock;
    QVector<Row> m_rows;
    QString m_nextAfterTargetAccountId;
    Failure m_failure = Failure::None;
    QString m_failureDetail;
    QString m_mutationTargetAccountId;
    QString m_mutationOperationId;
    MutationFailure m_mutationFailure = MutationFailure::None;
    bool m_available = false;
    bool m_busy = false;
    bool m_appendPending = false;
    bool m_hasMore = false;
    bool m_mutationPending = false;
};
