#pragma once

#include "V2WindowsConversationParticipantViewModel.h"

#include <QObject>
#include <QString>
#include <functional>

class V2WindowsAccountBlockViewModel final : public QObject {
    Q_OBJECT
public:
    enum class State { Unavailable, Unknown, Pending, Applied, Failed };
    Q_ENUM(State)
    using Submit = std::function<bool(
        const QString &targetAccountId, bool blocked, const QString &clientOperationId)>;

    explicit V2WindowsAccountBlockViewModel(
        Submit submit, QObject *parent = nullptr,
        WindowsLocale locale = WindowsLocale::ZhCn);
    State state() const { return m_state; }
    QString conversationId() const { return m_conversationId; }
    QString targetAccountId() const { return m_targetAccountId; }
    QString targetDisplayName() const { return m_targetDisplayName; }
    QString statusText() const { return m_statusText; }
    bool hasKnownState() const { return m_hasKnownState; }
    bool blocked() const { return m_blocked; }
    bool canSubmit() const;

    void bindSession(const QString &actorAccountId);
    void clearSession();
    bool activateDirectConversation(
        const QString &conversationId, const QString &participantConversationId,
        const QVector<V2WindowsConversationParticipantViewModel::Row> &participants,
        bool hasMore, bool direct);
    bool request(bool blocked);
    void applyResult(const QString &targetAccountId, bool blocked, bool changed,
                     const QString &clientOperationId);
    void applyFailure(const QString &clientOperationId, bool retryable);
    void setUnavailable();

signals:
    void changed();

private:
    void resetTarget(const QString &status);
    static QString operationId();

    Submit m_submit;
    QString m_actorAccountId;
    QString m_conversationId;
    QString m_targetAccountId;
    QString m_targetDisplayName;
    QString m_statusText;
    QString m_clientOperationId;
    QString m_operationTargetAccountId;
    bool m_operationBlocked = false;
    bool m_hasKnownState = false;
    bool m_blocked = false;
    State m_state = State::Unavailable;
    WindowsLocale m_locale;
};
