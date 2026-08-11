#include "HttpDownloadTransport.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QHostAddress>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const QByteArray expected("qt-raw-download\0bytes", 21);
    QTcpServer server;
    if (!server.listen(QHostAddress::LocalHost, 0)) return 1;

    bool requestValid = false;
    QObject::connect(&server, &QTcpServer::newConnection, &app, [&]() {
        QTcpSocket *socket = server.nextPendingConnection();
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket]() {
            const QByteArray request = socket->readAll().toLower();
            if (!request.contains("\r\n\r\n")) return;
            const bool successRequest = request.startsWith("get /api/download/-42?");
            requestValid = successRequest &&
                           request.contains("token=test-token") &&
                           request.contains("friend=1") &&
                           request.contains("disposition=attachment");
            if (successRequest) {
                const QByteArray header =
                    "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                    "Content-Length: " + QByteArray::number(expected.size()) +
                    "\r\nConnection: close\r\n\r\n";
                socket->write(header + expected);
            } else {
                socket->write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n"
                              "Connection: close\r\n\r\n");
            }
            socket->disconnectFromHost();
        });
    });

    HttpDownloadTransport transport;
    transport.configure(QStringLiteral("127.0.0.1"), server.serverPort(),
                        QStringLiteral("test-token"), false);
    int result = 1;
    bool successPassed = false;
    QObject::connect(
        &transport, &HttpDownloadTransport::finished, &app,
        [&](int fileId, bool success, const QString &path, const QString &) {
            QByteArray downloaded;
            if (!path.isEmpty()) {
                QFile file(path);
                if (file.open(QIODevice::ReadOnly)) downloaded = file.readAll();
                file.close();
                QFile::remove(path);
            }
            if (fileId == -42) {
                successPassed = success && requestValid && downloaded == expected;
                if (!successPassed || !transport.download(43)) app.quit();
                return;
            }
            result = successPassed && fileId == 43 && !success && path.isEmpty()
                         ? 0 : 1;
            app.quit();
        });
    QTimer::singleShot(5000, &app, &QCoreApplication::quit);
    if (!transport.download(-42)) return 1;
    app.exec();
    if (result != 0) qCritical() << "HTTP download transport verification failed";
    return result;
}
