#include "LoginDialog.h"
#include "NetworkManager.h"
#include "Protocol.h"
#include "WindowsLocalePreferenceRepository.h"
#include "WindowsLocaleViewModel.h"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QFormLayout>
#include <QLineEdit>
#include <QPushButton>
#include <QLabel>
#include <QTabWidget>
#include <QMessageBox>
#include <QRegularExpression>
#include <QComboBox>
#include <QSettings>
#include <QSignalBlocker>
#include <QAccessible>

LoginDialog::LoginDialog(QWidget *parent, WindowsLocaleViewModel *localeViewModel)
    : QDialog(parent), m_localeViewModel(localeViewModel)
{
    if (!m_localeViewModel) {
        m_ownedLocaleSettings = std::make_unique<QSettings>();
        m_ownedLocaleRepository =
            std::make_unique<WindowsLocalePreferenceRepository>(
                *m_ownedLocaleSettings);
        m_ownedLocaleViewModel = std::make_unique<WindowsLocaleViewModel>(
            m_ownedLocaleRepository.get());
        m_localeViewModel = m_ownedLocaleViewModel.get();
    }
    m_locale = m_localeViewModel->locale();
    setWindowFlags(Qt::Dialog | Qt::WindowMinimizeButtonHint | Qt::WindowCloseButtonHint);
    setMinimumWidth(400);
    setupUi();

    auto *net = NetworkManager::instance();
    connect(net, &NetworkManager::connected,       this, &LoginDialog::onConnected);
    connect(net, &NetworkManager::connectionError, this, &LoginDialog::onConnectionError);
    connect(net, &NetworkManager::loginResponse,   this, &LoginDialog::onLoginResponse);
    connect(net, &NetworkManager::registerResponse,this, &LoginDialog::onRegisterResponse);
    connect(m_localeViewModel, &WindowsLocaleViewModel::changed,
            this, &LoginDialog::applyLocale);
    applyLocale();
}

LoginDialog::~LoginDialog() = default;

void LoginDialog::setupUi() {
    auto *mainLayout = new QVBoxLayout(this);

    auto *localeRow = new QHBoxLayout;
    m_localeLabel = new QLabel;
    m_localeSelector = new QComboBox;
    m_localeStatus = new QLabel;
    m_localeSelector->addItem({}, static_cast<int>(WindowsLocale::ZhCn));
    m_localeSelector->addItem({}, static_cast<int>(WindowsLocale::EnUs));
    m_localeLabel->setBuddy(m_localeSelector);
    m_localeStatus->setWordWrap(true);
    localeRow->addWidget(m_localeLabel);
    localeRow->addWidget(m_localeSelector);
    localeRow->addWidget(m_localeStatus, 1);
    mainLayout->addLayout(localeRow);
    connect(m_localeSelector, qOverload<int>(&QComboBox::currentIndexChanged),
            this, [this](int index) {
                m_localeViewModel->select(static_cast<WindowsLocale>(
                    m_localeSelector->itemData(index).toInt()));
            });

    m_tabWidget = new QTabWidget;

    // ==================== 登录页 ====================
    auto *loginPage   = new QWidget;
    auto *loginLayout = new QFormLayout(loginPage);

    m_loginUser = new QLineEdit;
    m_loginPass = new QLineEdit;
    m_loginPass->setEchoMode(QLineEdit::Password);
    m_loginUser->setMinimumHeight(32);
    m_loginPass->setMinimumHeight(32);
    m_loginBtn  = new QPushButton;
    m_loginStatus = new QLabel;
    m_loginStatus->setWordWrap(true);

    m_loginUserLabel = new QLabel;
    m_loginPasswordLabel = new QLabel;
    loginLayout->addRow(m_loginUserLabel, m_loginUser);
    loginLayout->addRow(m_loginPasswordLabel, m_loginPass);
    loginLayout->addRow(m_loginBtn);
    loginLayout->addRow(m_loginStatus);

    connect(m_loginBtn, &QPushButton::clicked, this, &LoginDialog::onLogin);
    connect(m_loginPass, &QLineEdit::returnPressed, this, &LoginDialog::onLogin);

    // ==================== 注册页 ====================
    auto *regPage   = new QWidget;
    auto *regLayout = new QFormLayout(regPage);

    m_regUniqueId    = new QLineEdit;
    m_regDisplayName = new QLineEdit;
    m_regPass        = new QLineEdit;
    m_regPassConfirm = new QLineEdit;
    m_regPass->setEchoMode(QLineEdit::Password);
    m_regPassConfirm->setEchoMode(QLineEdit::Password);
    m_regBtn    = new QPushButton;
    m_regStatus = new QLabel;
    m_regStatus->setStyleSheet("color: red;");

    m_regUserIdLabel = new QLabel;
    m_regDisplayNameLabel = new QLabel;
    m_regPasswordLabel = new QLabel;
    m_regPasswordConfirmLabel = new QLabel;
    regLayout->addRow(m_regUserIdLabel, m_regUniqueId);
    regLayout->addRow(m_regDisplayNameLabel, m_regDisplayName);
    regLayout->addRow(m_regPasswordLabel, m_regPass);
    regLayout->addRow(m_regPasswordConfirmLabel, m_regPassConfirm);
    regLayout->addRow(m_regBtn);
    regLayout->addRow(m_regStatus);

    connect(m_regBtn, &QPushButton::clicked, this, &LoginDialog::onRegister);

    m_tabWidget->addTab(loginPage, {});
    m_tabWidget->addTab(regPage, {});

    mainLayout->addWidget(m_tabWidget);
}

QString LoginDialog::statusText(StatusKind kind, const QString &detail) const {
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    switch (kind) {
    case StatusKind::None: return {};
    case StatusKind::Connecting: return copy.connectingToServer;
    case StatusKind::CredentialsRequired: return copy.loginCredentialsRequired;
    case StatusKind::LoggingIn: return copy.loggingIn;
    case StatusKind::ConnectedLoggingIn: return copy.connectedLoggingIn;
    case StatusKind::ConnectedRegistering: return copy.connectedRegistering;
    case StatusKind::ConnectionFailed: return copy.connectionFailed.arg(detail);
    case StatusKind::LoginSucceeded: return copy.loginSucceeded;
    case StatusKind::LoginFailed: return copy.loginFailed.arg(detail);
    case StatusKind::PasswordsMismatch: return copy.registerPasswordsMismatch;
    case StatusKind::UserIdInvalid: return copy.registerUserIdInvalid;
    case StatusKind::NicknameTooLong: return copy.registerNicknameTooLong;
    case StatusKind::PasswordTooShort: return copy.registerPasswordTooShort;
    case StatusKind::Registering: return copy.registering;
    case StatusKind::RegistrationSucceeded: return copy.registrationSucceeded;
    case StatusKind::RegistrationFailed: return copy.registrationFailed.arg(detail);
    }
    return {};
}

void LoginDialog::setLoginStatus(StatusKind kind, const QString &detail) {
    m_loginStatusKind = kind;
    m_loginStatusDetail = detail;
    m_loginStatus->setText(statusText(kind, detail));
    m_loginStatus->setStyleSheet(
        kind == StatusKind::LoginSucceeded ? QStringLiteral("color: green;")
        : kind == StatusKind::None || kind == StatusKind::Connecting
              || kind == StatusKind::LoggingIn
              || kind == StatusKind::ConnectedLoggingIn
            ? QString()
            : QStringLiteral("color: red;"));
}

void LoginDialog::setRegisterStatus(StatusKind kind, const QString &detail) {
    m_registerStatusKind = kind;
    m_registerStatusDetail = detail;
    m_regStatus->setText(statusText(kind, detail));
    m_regStatus->setStyleSheet(
        kind == StatusKind::RegistrationSucceeded ? QStringLiteral("color: green;")
        : kind == StatusKind::None || kind == StatusKind::Connecting
              || kind == StatusKind::Registering
              || kind == StatusKind::ConnectedRegistering
            ? QString()
            : QStringLiteral("color: red;"));
}

void LoginDialog::applyLocale() {
    m_locale = m_localeViewModel->locale();
    const auto &copy = WindowsLocaleCatalog::messages(m_locale);
    setWindowTitle(copy.loginWindowTitle);
    m_localeLabel->setText(copy.language);
    m_localeSelector->setAccessibleName(copy.languageSelectorAccessible);
    m_localeSelector->setAccessibleDescription(copy.languageSelectorDescription);
    m_localeStatus->setAccessibleName(copy.localePreferenceStatusAccessible);
    m_localeStatus->setText(m_localeViewModel->failure());
    if (!m_localeViewModel->failure().isEmpty() && isVisible()) {
        QAccessibleEvent announcement(m_localeStatus, QAccessible::Alert);
        QAccessible::updateAccessibility(&announcement);
    }
    {
        const QSignalBlocker blocker(m_localeSelector);
        m_localeSelector->setItemText(0, copy.chinese);
        m_localeSelector->setItemText(1, copy.english);
        m_localeSelector->setCurrentIndex(
            m_locale == WindowsLocale::EnUs ? 1 : 0);
    }
    m_tabWidget->setTabText(0, copy.loginTab);
    m_tabWidget->setTabText(1, copy.registerTab);
    m_loginUserLabel->setText(copy.loginUserId);
    m_loginUserLabel->setBuddy(m_loginUser);
    m_loginPasswordLabel->setText(copy.loginPassword);
    m_loginPasswordLabel->setBuddy(m_loginPass);
    m_loginBtn->setText(copy.loginAction);
    m_loginStatus->setAccessibleName(copy.loginStatusAccessible);
    m_regUserIdLabel->setText(copy.loginUserId);
    m_regUserIdLabel->setBuddy(m_regUniqueId);
    m_regDisplayNameLabel->setText(copy.registerNickname);
    m_regDisplayNameLabel->setBuddy(m_regDisplayName);
    m_regPasswordLabel->setText(copy.loginPassword);
    m_regPasswordLabel->setBuddy(m_regPass);
    m_regPasswordConfirmLabel->setText(copy.registerConfirmPassword);
    m_regPasswordConfirmLabel->setBuddy(m_regPassConfirm);
    m_regBtn->setText(copy.registerAction);
    m_regStatus->setAccessibleName(copy.registerStatusAccessible);
    m_regUniqueId->setPlaceholderText(copy.registerUserIdPlaceholder);
    m_regDisplayName->setPlaceholderText(copy.registerDisplayNamePlaceholder);
    m_regPass->setPlaceholderText(copy.registerPasswordPlaceholder);
    m_regPassConfirm->setPlaceholderText(copy.registerConfirmPasswordPlaceholder);
    m_loginStatus->setText(statusText(m_loginStatusKind, m_loginStatusDetail));
    m_regStatus->setText(statusText(
        m_registerStatusKind, m_registerStatusDetail));
}

void LoginDialog::connectToServer() {
    if (m_connected) return;

    const QString host = QStringLiteral("127.0.0.1");
    const quint16 port = Protocol::DEFAULT_PORT;

    if (m_pendingAction == Register)
        setRegisterStatus(StatusKind::Connecting);
    else
        setLoginStatus(StatusKind::Connecting);
    m_loginBtn->setEnabled(false);
    m_regBtn->setEnabled(false);
    NetworkManager::instance()->connectToServer(host, port);
}

QString LoginDialog::username() const {
    return m_username;
}

QByteArray LoginDialog::takePasswordUtf8() {
    QByteArray password = m_loginPass->text().toUtf8();
    m_loginPass->clear();
    return password;
}

// ==================== 登录 ====================

void LoginDialog::onLogin() {
    QString user = m_loginUser->text().trimmed();
    QString pass = m_loginPass->text();

    if (user.isEmpty() || pass.isEmpty()) {
        setLoginStatus(StatusKind::CredentialsRequired);
        return;
    }

    if (!m_connected) {
        // 需要先连接
        m_pendingAction = Login;
        connectToServer();
        // 连接成功后会触发 onConnected → 再发登录请求
        m_username = user; // 暂存
        return;
    }

    setLoginStatus(StatusKind::LoggingIn);
    m_loginBtn->setEnabled(false);

    NetworkManager::instance()->loginWithCredentials(user, pass);
}

void LoginDialog::onConnected() {
    m_connected = true;

    if (m_pendingAction == Login) {
        setLoginStatus(StatusKind::ConnectedLoggingIn);
        QString user = m_loginUser->text().trimmed();
        QString pass = m_loginPass->text();
        if (!user.isEmpty() && !pass.isEmpty()) {
            NetworkManager::instance()->loginWithCredentials(user, pass);
        }
    } else if (m_pendingAction == Register) {
        setRegisterStatus(StatusKind::ConnectedRegistering);
        QString uid  = m_regUniqueId->text().trimmed();
        QString name = m_regDisplayName->text().trimmed();
        QString pass = m_regPass->text();
        if (!uid.isEmpty() && !pass.isEmpty()) {
            NetworkManager::instance()->sendMessage(
                Protocol::makeRegisterReq(uid, name, pass));
        }
    }
    m_pendingAction = None;
}

void LoginDialog::onConnectionError(const QString &error) {
    m_connected = false;
    if (m_pendingAction == Register)
        setRegisterStatus(StatusKind::ConnectionFailed, error);
    else
        setLoginStatus(StatusKind::ConnectionFailed, error);
    m_pendingAction = None;
    m_loginBtn->setEnabled(true);
    m_regBtn->setEnabled(true);
}

void LoginDialog::onLoginResponse(bool success, const QString &error, int userId, const QString &username, const QString &displayName) {
    if (success) {
        m_userId   = userId;
        m_username = username;
        m_displayName = displayName;
        setLoginStatus(StatusKind::LoginSucceeded);
        emit loginSuccess(userId, username, displayName);
        accept();
    } else {
        setLoginStatus(StatusKind::LoginFailed, error);
        m_loginBtn->setEnabled(true);
        m_regBtn->setEnabled(true);
    }
}

// ==================== 注册 ====================

void LoginDialog::onRegister() {
    QString uid     = m_regUniqueId->text().trimmed();
    QString name    = m_regDisplayName->text().trimmed();
    QString pass    = m_regPass->text();
    QString confirm = m_regPassConfirm->text();

    if (uid.isEmpty() || pass.isEmpty()) {
        setRegisterStatus(StatusKind::CredentialsRequired);
        return;
    }
    if (pass != confirm) {
        setRegisterStatus(StatusKind::PasswordsMismatch);
        return;
    }
    // 验证用户ID格式
    QRegularExpression idRegex("^[a-zA-Z0-9_]{6,20}$");
    if (!idRegex.match(uid).hasMatch()) {
        setRegisterStatus(StatusKind::UserIdInvalid);
        return;
    }
    if (name.isEmpty()) {
        name = uid; // 昵称默认与ID相同
    }
    if (name.length() > 20) {
        setRegisterStatus(StatusKind::NicknameTooLong);
        return;
    }
    if (pass.length() < 4) {
        setRegisterStatus(StatusKind::PasswordTooShort);
        return;
    }

    if (!m_connected) {
        m_pendingAction = Register;
        connectToServer();
        return;
    }

    setRegisterStatus(StatusKind::Registering);
    m_regBtn->setEnabled(false);

    NetworkManager::instance()->sendMessage(
        Protocol::makeRegisterReq(uid, name, pass));
}

void LoginDialog::onRegisterResponse(bool success, const QString &error) {
    m_regBtn->setEnabled(true);
    m_loginBtn->setEnabled(true);
    if (success) {
        setRegisterStatus(StatusKind::RegistrationSucceeded);
        // 自动填充到登录页
        m_loginUser->setText(m_regUniqueId->text());
        m_loginPass->clear();
        m_tabWidget->setCurrentIndex(0);
    } else {
        setRegisterStatus(StatusKind::RegistrationFailed, error);
    }
}
