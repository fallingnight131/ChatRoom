#pragma once

#include <QObject>
#include <QString>
#include <QVector>
#include <functional>

class V2WindowsAccountBlockDirectoryViewModel final : public QObject {
    Q_OBJECT
public:
    static constexpr qsizetype MaxRows = 500;
    struct Row {
        QString targetAccountId;
        QString targetDisplayName;
        qint64 blockedAtEpochMs = 0;
    };
    using List = std::function<bool(const QString &afterTargetAccountId)>;

    explicit V2WindowsAccountBlockDirectoryViewModel(
        List list, QObject *parent = nullptr);
    QVector<Row> rows() const { return m_rows; }
    bool available() const { return m_available; }
    bool busy() const { return m_busy; }
    bool hasMore() const { return m_hasMore; }
    QString failure() const { return m_failure; }

    void bindSession(bool clearRows);
    void clearSession();
    bool refresh();
    bool loadMore();
    void applyPage(QVector<Row> rows, const QString &nextAfterTargetAccountId,
                   bool hasMore);
    void applyFailure(const QString &safeReason);
    void setUnavailable();

signals:
    void changed();

private:
    List m_list;
    QVector<Row> m_rows;
    QString m_nextAfterTargetAccountId;
    QString m_failure;
    bool m_available = false;
    bool m_busy = false;
    bool m_appendPending = false;
    bool m_hasMore = false;
};
