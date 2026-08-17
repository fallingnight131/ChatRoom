#pragma once

#include "V2WindowsMentionComposer.h"
#include "WindowsLocaleCatalog.h"

#include <QWidget>

class QEvent;
class QLabel;
class QListWidget;
class QListWidgetItem;
class QLineEdit;
class QPlainTextEdit;
class QPushButton;
class QTimer;
class V2WindowsConversationParticipantViewModel;
class V2WindowsConversationDirectoryViewModel;
class V2WindowsMessagingViewModel;
class V2WindowsMessageSearchViewModel;

class V2WindowsMessagingPanel final : public QWidget {
    Q_OBJECT
public:
    explicit V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent = nullptr, bool mentionsEnabled = false,
        V2WindowsConversationDirectoryViewModel *directoryViewModel = nullptr,
        bool forwardingEnabled = false,
        V2WindowsMessageSearchViewModel *searchViewModel = nullptr,
        WindowsLocale locale = WindowsLocale::ZhCn);
    ~V2WindowsMessagingPanel() override;
    void setConversation(const QString &conversationId);
    QPlainTextEdit *composerForTest() const { return m_composer; }
    QListWidget *messageListForTest() const { return m_messages; }
    QListWidget *participantListForTest() const { return m_participants; }
    QPushButton *cancelReplyForTest() const { return m_cancelReply; }
    QPushButton *mentionForTest() const { return m_mention; }
    QPushButton *sendForTest() const { return m_send; }
    QLabel *composerBudgetForTest() const { return m_composerBudget; }
    QLineEdit *searchInputForTest() const { return m_searchInput; }
    QPushButton *searchButtonForTest() const { return m_searchButton; }
    QListWidget *searchResultsForTest() const { return m_searchResults; }

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void render();
    void renderParticipants();
    void renderSearch();
    void startSearch();
    void revealSearchResult(QListWidgetItem *item);
    bool revealMessage(const QString &messageId);
    void chooseReply(const QString &messageId);
    void chooseForward(const QString &messageId);
    void beginEdit(const QString &messageId, const QString &text,
                   const QList<V2LocalMessageRepository::Mention> &mentions);
    void cancelComposition();
    void toggleParticipantPicker();
    void insertParticipant(QListWidgetItem *item);
    void reconcileComposer();
    void flushDraft();
    void restoreDraft();
    void sendComposition();
    V2WindowsMessagingViewModel *m_viewModel;
    V2WindowsConversationParticipantViewModel *m_participantViewModel;
    V2WindowsConversationDirectoryViewModel *m_directoryViewModel;
    V2WindowsMessageSearchViewModel *m_searchViewModel;
    QLabel *m_status;
    QWidget *m_searchPane;
    QLineEdit *m_searchInput;
    QPushButton *m_searchButton;
    QLabel *m_searchStatus;
    QListWidget *m_searchResults;
    QPushButton *m_searchLoadMore;
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
    QLabel *m_composerBudget;
    QTimer *m_draftSaveTimer;
    QString m_conversationId;
    QString m_editTargetMessageId;
    QString m_previousComposerText;
    QString m_pendingSearchRevealMessageId;
    QList<V2WindowsMentionComposer::Anchor> m_mentionAnchors;
    QString m_draftBeforeEdit;
    QList<V2WindowsMentionComposer::Anchor> m_draftAnchorsBeforeEdit;
    bool m_updatingComposer = false;
    bool m_mentionsEnabled = false;
    bool m_forwardingEnabled = false;
    WindowsLocale m_locale;
};
