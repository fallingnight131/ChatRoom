#pragma once

#include <QDialog>
#include <functional>

class QLabel;
class QListWidget;
class QPushButton;
class V2WindowsAccountBlockDirectoryViewModel;

class V2WindowsAccountBlockDirectoryDialog final : public QDialog {
    Q_OBJECT
public:
    using Confirm = std::function<bool(QWidget *parent, const QString &displayName)>;
    explicit V2WindowsAccountBlockDirectoryDialog(
        V2WindowsAccountBlockDirectoryViewModel *viewModel,
        Confirm confirm = {}, QWidget *parent = nullptr);

    QListWidget *listForTest() const { return m_list; }
    QLabel *statusForTest() const { return m_status; }
    QPushButton *refreshForTest() const { return m_refresh; }
    QPushButton *loadMoreForTest() const { return m_loadMore; }
    QPushButton *unblockForTest() const { return m_unblock; }

private:
    void render();
    void submitUnblock();

    V2WindowsAccountBlockDirectoryViewModel *m_viewModel;
    QListWidget *m_list;
    QLabel *m_status;
    QPushButton *m_refresh;
    QPushButton *m_loadMore;
    QPushButton *m_unblock;
    Confirm m_confirm;
};
