#pragma once

#include <QDialog>
#include <functional>

class QLabel;
class QPushButton;
class V2WindowsAccountBlockViewModel;
class V2WindowsConversationParticipantViewModel;

class V2WindowsAccountBlockDialog final : public QDialog {
    Q_OBJECT
public:
    using Confirm = std::function<bool(
        QWidget *parent, const QString &targetDisplayName, bool blocked)>;

    explicit V2WindowsAccountBlockDialog(
        V2WindowsAccountBlockViewModel *viewModel,
        V2WindowsConversationParticipantViewModel *participantViewModel,
        Confirm confirm = {}, QWidget *parent = nullptr);
    void setConversation(const QString &conversationId, bool direct);

    QLabel *targetForTest() const { return m_target; }
    QLabel *statusForTest() const { return m_status; }
    QPushButton *blockForTest() const { return m_block; }
    QPushButton *unblockForTest() const { return m_unblock; }

private:
    void synchronizeTarget();
    void render();
    void submit(bool blocked);

    V2WindowsAccountBlockViewModel *m_viewModel;
    V2WindowsConversationParticipantViewModel *m_participantViewModel;
    Confirm m_confirm;
    QLabel *m_target;
    QLabel *m_status;
    QPushButton *m_block;
    QPushButton *m_unblock;
    QString m_conversationId;
    bool m_direct = false;
};
