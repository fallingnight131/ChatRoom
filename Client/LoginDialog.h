#pragma once

#include <QByteArray>
#include <QDialog>
#include <memory>
#include "WindowsLocaleCatalog.h"

class QLineEdit;
class QPushButton;
class QLabel;
class QTabWidget;
class QWidget;
class QComboBox;
class QSettings;
class WindowsLocalePreferenceRepository;
class WindowsLocaleViewModel;

/// 登录/注册对话框
class LoginDialog : public QDialog {
    Q_OBJECT
public:
    explicit LoginDialog(QWidget *parent = nullptr,
                         WindowsLocaleViewModel *localeViewModel = nullptr);
    ~LoginDialog() override;

    QString username() const;
    QByteArray takePasswordUtf8();
    QString displayName() const { return m_displayName; }
    int     userId() const { return m_userId; }
    QComboBox *localeSelectorForTest() const { return m_localeSelector; }
    QLabel *localeStatusForTest() const { return m_localeStatus; }
    QTabWidget *tabsForTest() const { return m_tabWidget; }
    QLineEdit *loginUserForTest() const { return m_loginUser; }
    QLineEdit *loginPasswordForTest() const { return m_loginPass; }
    QPushButton *loginButtonForTest() const { return m_loginBtn; }
    QLabel *loginStatusForTest() const { return m_loginStatus; }
    QLineEdit *registerUserForTest() const { return m_regUniqueId; }
    QLineEdit *registerPasswordForTest() const { return m_regPass; }
    QLineEdit *registerConfirmPasswordForTest() const { return m_regPassConfirm; }
    QPushButton *registerButtonForTest() const { return m_regBtn; }
    QLabel *registerStatusForTest() const { return m_regStatus; }

signals:
    void loginSuccess(int userId, const QString &username, const QString &displayName);

private slots:
    void onLogin();
    void onRegister();
    void onLoginResponse(bool success, const QString &error, int userId, const QString &username, const QString &displayName);
    void onRegisterResponse(bool success, const QString &error);
    void onConnected();
    void onConnectionError(const QString &error);

private:
    enum class StatusKind {
        None,
        Connecting,
        CredentialsRequired,
        LoggingIn,
        ConnectedLoggingIn,
        ConnectedRegistering,
        ConnectionFailed,
        LoginSucceeded,
        LoginFailed,
        PasswordsMismatch,
        UserIdInvalid,
        NicknameTooLong,
        PasswordTooShort,
        Registering,
        RegistrationSucceeded,
        RegistrationFailed
    };
    void setupUi();
    void connectToServer();
    void applyLocale();
    void setLoginStatus(StatusKind kind, const QString &detail = {});
    void setRegisterStatus(StatusKind kind, const QString &detail = {});
    QString statusText(StatusKind kind, const QString &detail) const;

    QTabWidget  *m_tabWidget      = nullptr;
    QLabel *m_localeLabel = nullptr;
    QComboBox *m_localeSelector = nullptr;
    QLabel *m_localeStatus = nullptr;

    // 登录页
    QLineEdit   *m_loginUser      = nullptr;
    QLineEdit   *m_loginPass      = nullptr;
    QPushButton *m_loginBtn       = nullptr;
    QLabel      *m_loginStatus    = nullptr;
    QLabel      *m_loginUserLabel = nullptr;
    QLabel      *m_loginPasswordLabel = nullptr;

    // 注册页
    QLineEdit   *m_regUniqueId    = nullptr;
    QLineEdit   *m_regDisplayName = nullptr;
    QLineEdit   *m_regPass        = nullptr;
    QLineEdit   *m_regPassConfirm = nullptr;
    QPushButton *m_regBtn         = nullptr;
    QLabel      *m_regStatus      = nullptr;
    QLabel      *m_regUserIdLabel = nullptr;
    QLabel      *m_regDisplayNameLabel = nullptr;
    QLabel      *m_regPasswordLabel = nullptr;
    QLabel      *m_regPasswordConfirmLabel = nullptr;

    int          m_userId         = 0;
    QString      m_username;
    QString      m_displayName;
    bool         m_connected      = false;

    enum PendingAction { None, Login, Register };
    PendingAction m_pendingAction  = None;
    StatusKind m_loginStatusKind = StatusKind::None;
    StatusKind m_registerStatusKind = StatusKind::None;
    QString m_loginStatusDetail;
    QString m_registerStatusDetail;
    std::unique_ptr<QSettings> m_ownedLocaleSettings;
    std::unique_ptr<WindowsLocalePreferenceRepository> m_ownedLocaleRepository;
    std::unique_ptr<WindowsLocaleViewModel> m_ownedLocaleViewModel;
    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
};
