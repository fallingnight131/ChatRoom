#pragma once

#include <QObject>
#include <QString>
#include <QVector>
#include <functional>

class V2WindowsConversationParticipantViewModel final : public QObject {
    Q_OBJECT
public:
    struct Row { QString accountId; QString displayName; QString roleLabel; };
    using Request = std::function<bool(const QString &, bool)>;

    explicit V2WindowsConversationParticipantViewModel(
        Request request, QObject *parent = nullptr);
    QString conversationId() const { return m_conversationId; }
    QVector<Row> rows() const { return m_rows; }
    bool busy() const { return m_busy; }
    bool hasMore() const { return m_hasMore; }
    QString failure() const { return m_failure; }
    bool activate(const QString &conversationId);
    bool refresh();
    bool loadMore();
    void applyPage(const QString &conversationId, QVector<Row> rows,
                   bool append, bool hasMore);
    void applyFailure(const QString &conversationId, const QString &safeReason);
    void setUnavailable();

signals:
    void changed();

private:
    static constexpr qsizetype MaximumRows = 500;
    bool request(bool continuation);

    Request m_request;
    QString m_conversationId;
    QVector<Row> m_rows;
    bool m_busy = false;
    bool m_hasMore = false;
    QString m_failure;
};
