#pragma once

#include <QWidget>

class QLabel;
class QListWidget;
class QPlainTextEdit;
class QPushButton;
class V2WindowsMessagingViewModel;

class V2WindowsMessagingPanel final : public QWidget {
    Q_OBJECT
public:
    explicit V2WindowsMessagingPanel(
        V2WindowsMessagingViewModel *viewModel, QWidget *parent = nullptr);
    QPlainTextEdit *composerForTest() const { return m_composer; }
    QListWidget *messageListForTest() const { return m_messages; }
    QPushButton *cancelReplyForTest() const { return m_cancelReply; }
    QPushButton *sendForTest() const { return m_send; }

private:
    void render();
    void sendReply();
    V2WindowsMessagingViewModel *m_viewModel;
    QLabel *m_status;
    QLabel *m_replyBanner;
    QListWidget *m_messages;
    QPlainTextEdit *m_composer;
    QPushButton *m_cancelReply;
    QPushButton *m_send;
};
