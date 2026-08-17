#pragma once

#include <QDialog>
#include <functional>
#include "WindowsLocaleCatalog.h"

class QLabel;
class QListWidget;
class QPushButton;
class V2WindowsAccountBlockDirectoryViewModel;
class WindowsLocaleViewModel;

class V2WindowsAccountBlockDirectoryDialog final : public QDialog {
    Q_OBJECT
public:
    using Confirm = std::function<bool(QWidget *parent, const QString &displayName)>;
    explicit V2WindowsAccountBlockDirectoryDialog(
        V2WindowsAccountBlockDirectoryViewModel *viewModel,
        Confirm confirm = {}, QWidget *parent = nullptr,
        WindowsLocaleViewModel *localeViewModel = nullptr);

    QListWidget *listForTest() const { return m_list; }
    QLabel *statusForTest() const { return m_status; }
    QPushButton *refreshForTest() const { return m_refresh; }
    QPushButton *loadMoreForTest() const { return m_loadMore; }
    QPushButton *unblockForTest() const { return m_unblock; }

private:
    void applyLocale();
    void render();
    void submitUnblock();
    QString failureText() const;
    QString mutationFailureText() const;
    bool confirmUnblock(const QString &displayName);

    V2WindowsAccountBlockDirectoryViewModel *m_viewModel;
    QListWidget *m_list;
    QLabel *m_status;
    QLabel *m_intro;
    QPushButton *m_refresh;
    QPushButton *m_loadMore;
    QPushButton *m_unblock;
    QPushButton *m_close;
    Confirm m_confirm;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
};
