#include <QApplication>
#include "LoginDialog.h"
#include "ChatWindow.h"
#include "NetworkManager.h"
#include "ThemeManager.h"

int main(int argc, char *argv[]) {
    QApplication app(argc, argv);
    app.setApplicationName("ChatClient");
    app.setApplicationVersion("1.0.0");
    app.setOrganizationName("QtChatRoom");
    app.setQuitOnLastWindowClosed(false);

    // 应用默认主题
    ThemeManager::instance()->applyTheme(&app);

    // 显示登录对话框
    LoginDialog loginDialog;

    ChatWindow *chatWindow = nullptr;

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
