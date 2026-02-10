#include <QApplication>
#include <QMessageBox>
#include <QIcon>
#include "LoginDialog.h"
#include "ChatWindow.h"
#include "NetworkManager.h"
#include "ThemeManager.h"

// 全局退出函数，供 TrayManager 调用
void cleanupAndQuit() {
    NetworkManager::instance()->disconnectFromServer();
    qApp->quit();
}

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);
    app.setApplicationName("ChatClient");
    app.setApplicationVersion("1.0.0");
    app.setOrganizationName("QtChatRoom");
    app.setQuitOnLastWindowClosed(false);
    app.setWindowIcon(QIcon(":/icons/app.png"));

    // 应用默认主题
    ThemeManager::instance()->applyTheme(&app);

    ChatWindow *chatWindow = nullptr;

    // 强制下线处理
    QObject::connect(NetworkManager::instance(), &NetworkManager::forceOffline,
                     [&](const QString &reason) {
        if (chatWindow) {
            chatWindow->hide();
            chatWindow->deleteLater();
            chatWindow = nullptr;
        }
        QMessageBox::warning(nullptr, "异地登录", reason);

        // 重新显示登录对话框
        LoginDialog *loginDialog = new LoginDialog;
        QObject::connect(loginDialog, &LoginDialog::loginSuccess,
                         [&](int userId, const QString &username) {
            chatWindow = new ChatWindow;
            chatWindow->setCurrentUser(userId, username);
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
                     [&](int userId, const QString &username) {
        chatWindow = new ChatWindow;
        chatWindow->setCurrentUser(userId, username);
        chatWindow->show();
    });

    if (loginDialog.exec() != QDialog::Accepted) {
        return 0;
    }

    int ret = app.exec();

    delete chatWindow;
    return ret;
}
