#pragma once

#include <QObject>
#include <QString>
#include <QVector>
#include <functional>

class V2WindowsConversationDirectoryViewModel final : public QObject {
    Q_OBJECT
public:
    struct Row {
        QString conversationId;
        QString displayName;
        QString kindLabel;
        QString roleLabel;
        qint64 unreadCount = 0;
    };
    using Action = std::function<bool()>;
    using OpenConversation = std::function<bool(const QString &)>;

    V2WindowsConversationDirectoryViewModel(
        Action refresh, Action loadMore, OpenConversation open,
        QObject *parent = nullptr);
    QVector<Row> rows() const { return m_rows; }
    bool busy() const { return m_busy; }
    bool hasMore() const { return m_hasMore; }
    QString failure() const { return m_failure; }
    bool refresh();
    bool loadMore();
    bool openConversation(const QString &conversationId);
    void applyPage(QVector<Row> rows, bool append, bool hasMore);
    void applyFailure(const QString &safeReason);
    void setUnavailable();

signals:
    void changed();
    void conversationOpened(const QString &conversationId);

private:
    Action m_refresh;
    Action m_loadMore;
    OpenConversation m_open;
    QVector<Row> m_rows;
    bool m_busy = false;
    bool m_hasMore = false;
    QString m_failure;
};
