#pragma once

#include <QObject>
#include <QString>
#include <QVector>
#include <functional>

class V2WindowsMessageSearchViewModel final : public QObject {
    Q_OBJECT
public:
    struct Row {
        QString messageId;
        quint64 conversationSequence = 0;
        QString senderAccountId;
        QString text;
        qint64 acceptedAtEpochMs = 0;
        quint32 contentRevision = 0;
        qint64 editedAtEpochMs = 0;
    };
    using Request = std::function<bool(
        const QString &, const QString &, quint64, bool)>;

    explicit V2WindowsMessageSearchViewModel(
        Request request, QObject *parent = nullptr);
    QString conversationId() const { return m_conversationId; }
    QString query() const { return m_query; }
    QVector<Row> rows() const { return m_rows; }
    bool busy() const { return m_busy; }
    bool hasMore() const { return m_hasMore; }
    QString failure() const { return m_failure; }
    bool activate(const QString &conversationId);
    bool search(const QString &literalQuery);
    bool loadMore();
    void applyPage(const QString &conversationId, const QString &query, QVector<Row> rows,
                   bool append, quint64 nextBeforeSequence, bool hasMore);
    void applyFailure(const QString &conversationId, const QString &query,
                      const QString &safeReason);
    void setUnavailable();

signals:
    void changed();

private:
    static constexpr qsizetype MaximumRows = 100;
    Request m_request;
    QString m_conversationId;
    QString m_query;
    QVector<Row> m_rows;
    quint64 m_nextBeforeSequence = 0;
    bool m_busy = false;
    bool m_hasMore = false;
    QString m_failure;
};
