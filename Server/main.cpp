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
        "TCP 监听端口 (默认 9527)",
        "port",
        QString::number(Protocol::DEFAULT_PORT));
    parser.addOption(portOption);

    QCommandLineOption wsPortOption(
        QStringList() << "w" << "ws-port",
        "WebSocket 监听端口 (默认 TCP端口+1)",
        "wsport",
        "0");
    parser.addOption(wsPortOption);

    QCommandLineOption httpPortOption(
        QStringList() << "H" << "http-port",
        "HTTP 下载端口 (默认 TCP端口+2)",
        "httpport",
        "0");
    parser.addOption(httpPortOption);

    parser.process(app);

    quint16 port   = parser.value(portOption).toUShort();
    quint16 wsPort = parser.value(wsPortOption).toUShort();
    quint16 httpPort = parser.value(httpPortOption).toUShort();
    if (wsPort == 0) wsPort = port + 1;
    if (httpPort == 0) httpPort = port + 2;

    ChatServer server;
    if (!server.startServer(port, wsPort, httpPort)) {
        qCritical() << "服务器启动失败!";
        return 1;
    }

    qInfo() << "========================================";
    qInfo() << "  Qt聊天室服务器 v2.0 (TCP + WebSocket + HTTP)";
    qInfo() << "  TCP 端口:" << port;
    qInfo() << "  WebSocket 端口:" << wsPort;
    qInfo() << "  HTTP 下载端口:" << httpPort;
    qInfo() << "========================================";

    return app.exec();
}
