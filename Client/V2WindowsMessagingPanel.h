#pragma once

#include "V2WindowsMentionComposer.h"

#include <QWidget>

class QLabel;
class QListWidget;
class QListWidgetItem;
class QPlainTextEdit;
class QPushButton;
class V2WindowsConversationParticipantViewModel;
class V2WindowsMessagingViewModel;

class V2WindowsMessagingPanel final : public QWidget {
    Q_OBJECT
public:
    explicit V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent = nullptr, bool mentionsEnabled = false);
    void setConversation(const QString &conversationId);
    QPlainTextEdit *composerForTest() const { return m_composer; }
    QListWidget *messageListForTest() const { return m_messages; }
    QListWidget *participantListForTest() const { return m_participants; }
    QPushButton *cancelReplyForTest() const { return m_cancelReply; }
    QPushButton *mentionForTest() const { return m_mention; }
    QPushButton *sendForTest() const { return m_send; }

private:
    void render();
    void renderParticipants();
    void chooseReply(const QString &messageId);
    void beginEdit(const QString &messageId, const QString &text,
                   const QList<V2LocalMessageRepository::Mention> &mentions);
    void cancelComposition();
    void toggleParticipantPicker();
    void insertParticipant(QListWidgetItem *item);
    void reconcileComposer();
    void sendReply();
    V2WindowsMessagingViewModel *m_viewModel;
    V2WindowsConversationParticipantViewModel *m_participantViewModel;
    QLabel *m_status;
    QLabel *m_replyBanner;
    QListWidget *m_messages;
    QPlainTextEdit *m_composer;
    QWidget *m_participantPane;
    QLabel *m_participantStatus;
    QListWidget *m_participants;
    QPushButton *m_refreshParticipants;
    QPushButton *m_loadMoreParticipants;
    QPushButton *m_closeParticipants;
    QPushButton *m_cancelReply;
    QPushButton *m_mention;
    QPushButton *m_send;
    QString m_conversationId;
    QString m_editTargetMessageId;
    QString m_previousComposerText;
    QList<V2WindowsMentionComposer::Anchor> m_mentionAnchors;
    bool m_updatingComposer = false;
    bool m_mentionsEnabled = false;
};
