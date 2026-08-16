#pragma once

#include <QDialog>

class QLabel;
class QListWidget;
class QPushButton;
class V2WindowsAccountBlockDirectoryViewModel;

class V2WindowsAccountBlockDirectoryDialog final : public QDialog {
    Q_OBJECT
public:
    explicit V2WindowsAccountBlockDirectoryDialog(
        V2WindowsAccountBlockDirectoryViewModel *viewModel,
        QWidget *parent = nullptr);

    QListWidget *listForTest() const { return m_list; }
    QLabel *statusForTest() const { return m_status; }
    QPushButton *refreshForTest() const { return m_refresh; }
    QPushButton *loadMoreForTest() const { return m_loadMore; }

private:
    void render();

    V2WindowsAccountBlockDirectoryViewModel *m_viewModel;
    QListWidget *m_list;
    QLabel *m_status;
    QPushButton *m_refresh;
    QPushButton *m_loadMore;
};
