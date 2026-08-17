#pragma once

#include "WindowsLocaleCatalog.h"

#include <QDialog>

class QLabel;
class QListWidget;
class QListWidgetItem;
class QPushButton;
class QComboBox;
class QDialogButtonBox;
class QSplitter;
class V2WindowsConversationDirectoryViewModel;
class V2WindowsConversationParticipantViewModel;
class V2WindowsMessagingPanel;
class V2WindowsMessagingViewModel;
class V2WindowsMessageSearchViewModel;
class V2WindowsAccountBlockViewModel;
class WindowsLocaleViewModel;

class V2WindowsConversationDialog final : public QDialog {
    Q_OBJECT
public:
    explicit V2WindowsConversationDialog(
        V2WindowsConversationDirectoryViewModel *directoryViewModel,
        V2WindowsMessagingViewModel *messagingViewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        QWidget *parent = nullptr, bool mentionsEnabled = false,
        bool forwardingEnabled = false,
        V2WindowsMessageSearchViewModel *searchViewModel = nullptr,
        V2WindowsAccountBlockViewModel *accountBlockViewModel = nullptr,
        WindowsLocale locale = WindowsLocale::ZhCn,
        WindowsLocaleViewModel *localeViewModel = nullptr);

    QListWidget *conversationListForTest() const { return m_conversations; }
    QPushButton *refreshForTest() const { return m_refresh; }
    QPushButton *loadMoreForTest() const { return m_loadMore; }
    V2WindowsMessagingPanel *messagingPanelForTest() const { return m_messagingPanel; }
    QPushButton *accountBlockForTest() const { return m_accountBlock; }
    QComboBox *localeSelectorForTest() const { return m_localeSelector; }
    QLabel *localeStatusForTest() const { return m_localeStatus; }
    QString selectedConversationId() const { return m_selectedConversationId; }

private:
    void renderDirectory();
    void openItem(QListWidgetItem *item);
    void markConversationOpened(const QString &conversationId);
    void applyLocale();

    V2WindowsConversationDirectoryViewModel *m_directoryViewModel;
    V2WindowsConversationParticipantViewModel *m_participantViewModel;
    V2WindowsMessagingViewModel *m_messagingViewModel;
    V2WindowsMessageSearchViewModel *m_searchViewModel;
    V2WindowsAccountBlockViewModel *m_accountBlockViewModel;
    WindowsLocaleViewModel *m_localeViewModel;
    QLabel *m_localeLabel;
    QComboBox *m_localeSelector;
    QLabel *m_localeStatus;
    QLabel *m_directoryTitle;
    QLabel *m_directoryStatus;
    QLabel *m_conversationTitle;
    QListWidget *m_conversations;
    QPushButton *m_refresh;
    QPushButton *m_loadMore;
    QPushButton *m_accountBlock;
    V2WindowsMessagingPanel *m_messagingPanel;
    QSplitter *m_splitter;
    QDialogButtonBox *m_closeButtons;
    QString m_selectedConversationId;
    bool m_selectedConversationDirect = false;
    WindowsLocale m_locale;
};
