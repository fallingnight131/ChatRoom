#pragma once

#include <QDialog>

class QLineEdit;
class QPushButton;
class QLabel;
class QTabWidget;
class QWidget;

/// 登录/注册对话框
class LoginDialog : public QDialog {
    Q_OBJECT
public:
    explicit LoginDialog(QWidget *parent = nullptr);

    QString username() const;
    int     userId() const { return m_userId; }

signals:
    void loginSuccess(int userId, const QString &username);

private slots:
    void onLogin();
    void onRegister();
    void onLoginResponse(bool success, const QString &error, int userId, const QString &username);
    void onRegisterResponse(bool success, const QString &error);
    void onConnected();
    void onConnectionError(const QString &error);

private:
    void setupUi();
    void connectToServer();

    QTabWidget  *m_tabWidget      = nullptr;

    // 登录页
    QLineEdit   *m_loginHost      = nullptr;
    QLineEdit   *m_loginPort      = nullptr;
    QLineEdit   *m_loginUser      = nullptr;
    QLineEdit   *m_loginPass      = nullptr;
    QPushButton *m_loginBtn       = nullptr;
    QLabel      *m_loginStatus    = nullptr;
    QPushButton *m_advancedBtn    = nullptr;
    QLabel      *m_hostLabel      = nullptr;
    QLabel      *m_portLabel      = nullptr;

    // 注册页
    QLineEdit   *m_regUser        = nullptr;
    QLineEdit   *m_regPass        = nullptr;
    QLineEdit   *m_regPassConfirm = nullptr;
    QPushButton *m_regBtn         = nullptr;
    QLabel      *m_regStatus      = nullptr;

    int          m_userId         = 0;
    QString      m_username;
    bool         m_connected      = false;

    enum PendingAction { None, Login, Register };
    PendingAction m_pendingAction  = None;
};
