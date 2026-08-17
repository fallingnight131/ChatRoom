#pragma once

#include <QDialog>
#include "WindowsLocaleCatalog.h"

class QLabel;
class QLineEdit;
class QPushButton;
class WindowsLocaleViewModel;

class RoomPasswordPromptDialog final : public QDialog {
    Q_OBJECT
public:
    explicit RoomPasswordPromptDialog(
        int roomId, QWidget *parent = nullptr,
        WindowsLocaleViewModel *localeViewModel = nullptr);

    QLineEdit *passwordForTest() const { return m_passwordEdit; }
    QLabel *promptForTest() const { return m_promptLabel; }
    QLabel *statusForTest() const { return m_statusLabel; }
    QPushButton *joinForTest() const { return m_joinButton; }
    QPushButton *cancelForTest() const { return m_cancelButton; }

signals:
    void joinRequested(int roomId, const QString &password);

private slots:
    void submit();
    void cancel();

private:
    void applyLocale();

    int m_roomId = 0;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
    QLabel *m_promptLabel = nullptr;
    QLineEdit *m_passwordEdit = nullptr;
    QLabel *m_statusLabel = nullptr;
    QPushButton *m_joinButton = nullptr;
    QPushButton *m_cancelButton = nullptr;
};
