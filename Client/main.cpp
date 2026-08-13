#include <QApplication>
#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QMessageBox>
#include <QIcon>
#include <QPointer>
#include <QStandardPaths>
#include <QDir>
#include <QTimer>
#include <cstdio>
#include <utility>
#include "LoginDialog.h"
#include "ChatWindow.h"
#include "NetworkManager.h"
#include "ThemeManager.h"
#include "WindowsClientInstanceGuard.h"
#include "WindowsUpdateRuntimePaths.h"
#include "WindowsUpdateStartupService.h"
#include "WindowsUpdateController.h"
#include "WindowsUpdateProductConfiguration.h"
#include "WindowsUpdateTrustDiagnostic.h"
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
#include "WindowsDeviceIdentityRepository.h"
#include "WindowsV2ConfigurationDiagnostic.h"
#include "WindowsV2ProductConfiguration.h"
#endif

#ifndef CHAT_APP_VERSION
#error "CHAT_APP_VERSION must come from the repository VERSION file"
#endif

// 全局退出函数，供 TrayManager 调用
void cleanupAndQuit() {
    NetworkManager::instance()->disconnectFromServer();
    qApp->quit();
}

#ifdef Q_OS_WIN
namespace {
bool handleWindowsUpdateStartup(const WindowsUpdateRuntimePaths &paths,
                                const QString &currentVersion) {
    WindowsUpdateStartupService service(
        paths.lifecycleStateDirectory, paths.resultDirectory,
        paths.runRootDirectory);
    const auto result = service.inspect(currentVersion, QDateTime::currentDateTimeUtc());
    using Outcome = WindowsUpdateStartupService::Outcome;
    switch (result.outcome) {
    case Outcome::None:
        return true;
    case Outcome::UpdateInProgress:
        QMessageBox::information(
            nullptr, QStringLiteral("正在完成更新"),
            QStringLiteral("聊天软件正在完成更新，请稍候。\n\n"
                           "更新完成后应用会自动重新打开。"));
        return false;
    case Outcome::StalePending:
        qWarning().noquote()
            << "[Updater] operation=startup-result outcome=stale-pending detail="
            << result.error;
        QMessageBox::warning(
            nullptr, QStringLiteral("更新未完成"),
            QStringLiteral("上次自动更新未在预期时间内完成。\n\n"
                           "您可以继续使用当前版本，稍后再次检查更新。"));
        return true;
    case Outcome::Installed:
        QMessageBox::information(
            nullptr, QStringLiteral("更新完成"),
            QStringLiteral("聊天软件已成功更新到版本 %1。")
                .arg(result.targetVersion));
        return true;
    case Outcome::Failed:
        qWarning().noquote()
            << "[Updater] operation=startup-result outcome="
            << result.launcherOutcome
            << "installerExitCode=" << result.installerExitCode
            << "detail=" << result.error;
        QMessageBox::warning(
            nullptr, QStringLiteral("更新失败"),
            QStringLiteral("自动更新未能完成，当前版本未被标记为更新成功。\n\n"
                           "请继续使用当前版本，或从官方渠道重新下载。"));
        return true;
    case Outcome::Rejected:
        qWarning().noquote()
            << "[Updater] operation=startup-result outcome=rejected detail="
            << result.error;
        QMessageBox::warning(
            nullptr, QStringLiteral("无法验证更新结果"),
            QStringLiteral("无法安全验证上次自动更新的结果，本次不会将其视为更新成功。\n\n"
                           "请使用当前版本，或从官方渠道重新下载。"));
        return true;
    }
    return true;
}
}
#endif

int main(int argc, char *argv[]) {
#ifdef Q_OS_WIN
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    if (argc == 2
            && QByteArray(argv[1]) == "--chatroom-print-v2-configuration-json") {
        QCoreApplication diagnosticApplication(argc, argv);
        const QByteArray output = WindowsV2ConfigurationDiagnostic::canonicalJson(
            WindowsV2ProductConfiguration::fromBuild());
        if (std::fwrite(output.constData(), 1,
                        static_cast<size_t>(output.size()), stdout)
                != static_cast<size_t>(output.size())) return 2;
        return 0;
    }
#endif
    if (argc == 2
            && QByteArray(argv[1]) == "--chatroom-print-update-trust-json") {
        QCoreApplication diagnosticApplication(argc, argv);
        const QByteArray output = WindowsUpdateTrustDiagnostic::canonicalJson(
            WindowsUpdateProductConfiguration::fromBuild());
        if (std::fwrite(output.constData(), 1,
                        static_cast<size_t>(output.size()), stdout)
                != static_cast<size_t>(output.size())) return 2;
        return 0;
    }
#endif
    QApplication app(argc, argv);
    app.setApplicationName("ChatClient");
    app.setApplicationVersion(CHAT_APP_VERSION);
    app.setOrganizationName("QtChatRoom");
    app.setQuitOnLastWindowClosed(false);
    app.setWindowIcon(QIcon(":/icons/app.png"));

#ifdef Q_OS_WIN
    WindowsClientInstanceGuard instanceGuard;
    QString instanceError;
    const auto instanceResult = instanceGuard.acquire(&instanceError);
    if (instanceResult == WindowsClientInstanceGuard::Result::AlreadyRunning) {
        QMessageBox::information(nullptr, QStringLiteral("聊天软件"),
                                 QStringLiteral("聊天软件已经在运行。"));
        return 0;
    }
    if (instanceResult != WindowsClientInstanceGuard::Result::Acquired) {
        QMessageBox::critical(nullptr, QStringLiteral("启动失败"), instanceError);
        return 1;
    }
    const auto updatePaths = WindowsUpdateRuntimePaths::fromAppLocalData(
        QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation));
    if (!handleWindowsUpdateStartup(updatePaths, app.applicationVersion())) return 0;
    const auto updateConfiguration = WindowsUpdateProductConfiguration::fromBuild();
    if (!updateConfiguration.enabled && !updateConfiguration.error.isEmpty()) {
        qWarning().noquote()
            << "[Updater] operation=configuration outcome=disabled detail="
            << updateConfiguration.error;
    }
    WindowsUpdateController updateController(
        updateConfiguration, updatePaths, &app);
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    const auto v2Configuration = WindowsV2ProductConfiguration::fromBuild();
    QString v2DeviceId;
    if (v2Configuration.enabled) {
        WindowsDeviceIdentityRepository identityRepository(
            QDir(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation))
                .filePath(QStringLiteral("security")));
        QString identityError;
        if (!identityRepository.loadOrCreate(&v2DeviceId, &identityError)) {
            qWarning().noquote()
                << "[WindowsV2] operation=device-identity outcome=disabled detail="
                << identityError;
            v2DeviceId.clear();
        }
    } else if (!v2Configuration.error.isEmpty()) {
        qWarning().noquote()
            << "[WindowsV2] operation=configuration outcome=disabled detail="
            << v2Configuration.error;
    }
#endif
#endif

    // 应用默认主题
    ThemeManager::instance()->applyTheme(&app);

    ChatWindow *chatWindow = nullptr;
#ifdef Q_OS_WIN
    bool automaticUpdateCheckStarted = false;
    auto configureUpdateUi = [&](ChatWindow *window) {
        if (!window) return;
        window->setUpdateCheckAvailable(updateController.isEnabled());
        QObject::connect(window, &ChatWindow::checkForUpdatesRequested,
                         &updateController, [&updateController, window] {
            QString error;
            if (!updateController.checkForUpdates(window, true, &error)) {
                QMessageBox::information(window, QStringLiteral("检查更新"),
                                         QStringLiteral("当前无法开始更新检查。"));
                qWarning().noquote()
                    << "[Updater] operation=manual-check-start detail=" << error;
            }
        });
        if (!automaticUpdateCheckStarted && updateController.isEnabled()) {
            automaticUpdateCheckStarted = true;
            const QPointer<ChatWindow> guardedWindow(window);
            QTimer::singleShot(0, &updateController, [&updateController, guardedWindow] {
                if (!guardedWindow) return;
                QString error;
                if (!updateController.checkForUpdates(
                        guardedWindow.data(), false, &error))
                    qWarning().noquote()
                        << "[Updater] operation=automatic-check-start detail=" << error;
            });
        }
    };
#ifdef CHAT_WINDOWS_V2_PRODUCT_AVAILABLE
    auto configureDeviceUi = [&](ChatWindow *window, LoginDialog *dialog) {
        if (!window || !dialog) return;
        QByteArray password = dialog->takePasswordUtf8();
        if (!v2Configuration.enabled || v2DeviceId.isEmpty()) {
            password.fill('\0');
            return;
        }
        if (!window->configureDeviceManagement(
                v2Configuration.endpoint, v2DeviceId, std::move(password),
                v2Configuration.messageForwardingEnabled)) {
            qWarning().noquote()
                << "[WindowsV2] operation=device-management outcome=disabled";
        }
    };
#else
    auto configureDeviceUi = [](ChatWindow *, LoginDialog *dialog) {
        if (!dialog) return;
        QByteArray password = dialog->takePasswordUtf8();
        password.fill('\0');
    };
#endif
    QObject::connect(&updateController, &WindowsUpdateController::quitRequested,
                     &app, [&] {
        if (chatWindow) chatWindow->requestApplicationQuit();
        else cleanupAndQuit();
    });
#endif

    // 强制下线处理（包括异地登录和用户主动注销）
    QObject::connect(NetworkManager::instance(), &NetworkManager::forceOffline,
                     [&](const QString &reason) {
        if (chatWindow) {
            chatWindow->hide();
            chatWindow->deleteLater();
            chatWindow = nullptr;
        }

        // 主动注销不需要弹出警告
        if (reason != "用户主动注销") {
            QMessageBox::warning(nullptr, "异地登录", reason);
        }

        // 重新显示登录对话框
        LoginDialog *loginDialog = new LoginDialog;
        QObject::connect(loginDialog, &LoginDialog::loginSuccess,
                         [&, loginDialog](int userId, const QString &username,
                                          const QString &displayName) {
            chatWindow = new ChatWindow;
            chatWindow->setCurrentUser(userId, username, displayName);
#ifdef Q_OS_WIN
            configureUpdateUi(chatWindow);
            configureDeviceUi(chatWindow, loginDialog);
#else
            QByteArray password = loginDialog->takePasswordUtf8();
            password.fill('\0');
#endif
            chatWindow->show();
        });
        QObject::connect(loginDialog, &QDialog::rejected, [&]() {
            qApp->quit();
        });
        loginDialog->setAttribute(Qt::WA_DeleteOnClose);
        loginDialog->show();
    });

    // 显示登录对话框
    LoginDialog loginDialog;

    QObject::connect(&loginDialog, &LoginDialog::loginSuccess,
                     [&](int userId, const QString &username, const QString &displayName) {
        chatWindow = new ChatWindow;
        chatWindow->setCurrentUser(userId, username, displayName);
#ifdef Q_OS_WIN
        configureUpdateUi(chatWindow);
        configureDeviceUi(chatWindow, &loginDialog);
#else
        QByteArray password = loginDialog.takePasswordUtf8();
        password.fill('\0');
#endif
        chatWindow->show();
    });

    if (loginDialog.exec() != QDialog::Accepted) {
        return 0;
    }

    // 断开栈上 LoginDialog 与 NetworkManager 的连接，
    // 避免强制下线后重新登录时，旧 Dialog 仍响应 loginResponse 创建重复窗口
    QObject::disconnect(NetworkManager::instance(), nullptr, &loginDialog, nullptr);

    int ret = app.exec();

    delete chatWindow;
    return ret;
}
