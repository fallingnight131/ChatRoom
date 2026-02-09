#include <QCoreApplication>
#include <QCommandLineParser>
#include "ChatServer.h"
#include "Protocol.h"

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    app.setApplicationName("ChatServer");
    app.setApplicationVersion("1.0.0");

    QCommandLineParser parser;
    parser.setApplicationDescription("Qt聊天室服务器");
    parser.addHelpOption();
    parser.addVersionOption();

    QCommandLineOption portOption(
        QStringList() << "p" << "port",
        "监听端口 (默认 9527)",
        "port",
        QString::number(Protocol::DEFAULT_PORT));
    parser.addOption(portOption);

    parser.process(app);

    quint16 port = parser.value(portOption).toUShort();

    ChatServer server;
    if (!server.startServer(port)) {
        qCritical() << "服务器启动失败!";
        return 1;
    }

    qInfo() << "========================================";
    qInfo() << "  Qt聊天室服务器 v1.0";
    qInfo() << "  监听端口:" << port;
    qInfo() << "========================================";

    return app.exec();
}
