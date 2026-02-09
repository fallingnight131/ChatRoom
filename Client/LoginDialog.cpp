#include "LoginDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QLineEdit>
#include <QPushButton>
#include <QLabel>
#include <QTabWidget>
#include <QMessageBox>

LoginDialog::LoginDialog(QWidget *parent)
    : QDialog(parent)
{
    setWindowTitle("Qt聊天室 - 登录");
    setFixedSize(400, 360);
    setupUi();

    auto *net = NetworkManager::instance();
    connect(net, &NetworkManager::connected,       this, &LoginDialog::onConnected);
    connect(net, &NetworkManager::connectionError, this, &LoginDialog::onConnectionError);
    connect(net, &NetworkManager::loginResponse,   this, &LoginDialog::onLoginResponse);
    connect(net, &NetworkManager::registerResponse,this, &LoginDialog::onRegisterResponse);
}

void LoginDialog::setupUi() {
    auto *mainLayout = new QVBoxLayout(this);

    m_tabWidget = new QTabWidget;

    // ==================== 登录页 ====================
    auto *loginPage   = new QWidget;
    auto *loginLayout = new QFormLayout(loginPage);

    m_loginHost = new QLineEdit("127.0.0.1");
    m_loginPort = new QLineEdit(QString::number(Protocol::DEFAULT_PORT));
    m_loginUser = new QLineEdit;
    m_loginPass = new QLineEdit;
    m_loginPass->setEchoMode(QLineEdit::Password);
    m_loginBtn  = new QPushButton("登 录");
    m_loginStatus = new QLabel;
    m_loginStatus->setStyleSheet("color: red;");

    loginLayout->addRow("服务器:", m_loginHost);
    loginLayout->addRow("端口:",   m_loginPort);
    loginLayout->addRow("用户名:", m_loginUser);
    loginLayout->addRow("密码:",   m_loginPass);
    loginLayout->addRow(m_loginBtn);
    loginLayout->addRow(m_loginStatus);

    m_loginUser->setPlaceholderText("请输入用户名");
    m_loginPass->setPlaceholderText("请输入密码");

    connect(m_loginBtn, &QPushButton::clicked, this, &LoginDialog::onLogin);
    connect(m_loginPass, &QLineEdit::returnPressed, this, &LoginDialog::onLogin);

    // ==================== 注册页 ====================
    auto *regPage   = new QWidget;
    auto *regLayout = new QFormLayout(regPage);

    m_regUser        = new QLineEdit;
    m_regPass        = new QLineEdit;
    m_regPassConfirm = new QLineEdit;
    m_regPass->setEchoMode(QLineEdit::Password);
    m_regPassConfirm->setEchoMode(QLineEdit::Password);
    m_regBtn    = new QPushButton("注 册");
    m_regStatus = new QLabel;
    m_regStatus->setStyleSheet("color: red;");

    regLayout->addRow("用户名:",   m_regUser);
    regLayout->addRow("密码:",     m_regPass);
    regLayout->addRow("确认密码:", m_regPassConfirm);
    regLayout->addRow(m_regBtn);
    regLayout->addRow(m_regStatus);

    m_regUser->setPlaceholderText("至少2个字符");
    m_regPass->setPlaceholderText("至少4个字符");
    m_regPassConfirm->setPlaceholderText("再次输入密码");

    connect(m_regBtn, &QPushButton::clicked, this, &LoginDialog::onRegister);

    m_tabWidget->addTab(loginPage, "登录");
    m_tabWidget->addTab(regPage,   "注册");

    mainLayout->addWidget(m_tabWidget);
}

void LoginDialog::connectToServer() {
    if (m_connected) return;

    QString host = m_loginHost->text().trimmed();
    quint16 port = m_loginPort->text().toUShort();
    if (host.isEmpty() || port == 0) {
        m_loginStatus->setText("请输入有效的服务器地址和端口");
        return;
    }

    m_loginStatus->setText("正在连接...");
    m_loginBtn->setEnabled(false);
    NetworkManager::instance()->connectToServer(host, port);
}

QString LoginDialog::username() const {
    return m_username;
}

// ==================== 登录 ====================

void LoginDialog::onLogin() {
    QString user = m_loginUser->text().trimmed();
    QString pass = m_loginPass->text();

    if (user.isEmpty() || pass.isEmpty()) {
        m_loginStatus->setText("请输入用户名和密码");
        return;
    }

    if (!m_connected) {
        // 需要先连接
        connectToServer();
        // 连接成功后会触发 onConnected → 再发发登录请求
        m_username = user; // 暂存
        return;
    }

    m_loginStatus->setText("正在登录...");
    m_loginBtn->setEnabled(false);

    NetworkManager::instance()->sendMessage(
        Protocol::makeLoginReq(user, pass));
}

void LoginDialog::onConnected() {
    m_connected = true;
    m_loginStatus->setText("已连接，正在登录...");

    // 如果是登录触发的连接，自动发送登录请求
    QString user = m_loginUser->text().trimmed();
    QString pass = m_loginPass->text();
    if (!user.isEmpty() && !pass.isEmpty()) {
        NetworkManager::instance()->sendMessage(
            Protocol::makeLoginReq(user, pass));
    }
}

void LoginDialog::onConnectionError(const QString &error) {
    m_connected = false;
    m_loginStatus->setText("连接失败: " + error);
    m_loginBtn->setEnabled(true);
    m_regBtn->setEnabled(true);
}

void LoginDialog::onLoginResponse(bool success, const QString &error, int userId, const QString &username) {
    if (success) {
        m_userId   = userId;
        m_username = username;
        m_loginStatus->setText("登录成功!");
        m_loginStatus->setStyleSheet("color: green;");
        emit loginSuccess(userId, username);
        accept();
    } else {
        m_loginStatus->setText("登录失败: " + error);
        m_loginBtn->setEnabled(true);
    }
}

// ==================== 注册 ====================

void LoginDialog::onRegister() {
    QString user    = m_regUser->text().trimmed();
    QString pass    = m_regPass->text();
    QString confirm = m_regPassConfirm->text();

    if (user.isEmpty() || pass.isEmpty()) {
        m_regStatus->setText("请输入用户名和密码");
        return;
    }
    if (pass != confirm) {
        m_regStatus->setText("两次密码不一致");
        return;
    }
    if (user.length() < 2) {
        m_regStatus->setText("用户名至少2个字符");
        return;
    }
    if (pass.length() < 4) {
        m_regStatus->setText("密码至少4个字符");
        return;
    }

    if (!m_connected) {
        // 使用登录页的连接信息
        connectToServer();
    }

    m_regStatus->setText("正在注册...");
    m_regBtn->setEnabled(false);

    // 若未连接，需要等连接建立后再注册; 这里简化为直接发送
    // 如果连接还在建立中，消息会被 NetworkManager 缓存或丢弃
    NetworkManager::instance()->sendMessage(
        Protocol::makeRegisterReq(user, pass));
}

void LoginDialog::onRegisterResponse(bool success, const QString &error) {
    m_regBtn->setEnabled(true);
    if (success) {
        m_regStatus->setStyleSheet("color: green;");
        m_regStatus->setText("注册成功！请切换到登录页面");
        // 自动填充到登录页
        m_loginUser->setText(m_regUser->text());
        m_loginPass->clear();
        m_tabWidget->setCurrentIndex(0);
    } else {
        m_regStatus->setStyleSheet("color: red;");
        m_regStatus->setText("注册失败: " + error);
    }
}
