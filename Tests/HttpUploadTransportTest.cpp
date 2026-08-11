#include "HttpUploadTransport.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QHostAddress>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTemporaryDir>
#include <QTimer>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    const QByteArray expected("qt-raw-upload\0bytes", 19);
    QTemporaryDir directory;
    if (!directory.isValid()) {
        qCritical() << "temporary directory failed";
        return 1;
    }
    const QString filePath = directory.filePath(QStringLiteral("payload.bin"));
    QFile output(filePath);
    if (!output.open(QIODevice::WriteOnly) || output.write(expected) != expected.size()) {
        qCritical() << "temporary file failed";
        return 1;
    }
    output.close();

    QTcpServer server;
    if (!server.listen(QHostAddress::LocalHost, 0)) {
        qCritical() << "listen failed";
        return 1;
    }
    QByteArray requestBytes;
    QByteArray receivedBody;
    bool requestValid = false;
    QObject::connect(&server, &QTcpServer::newConnection, &app, [&]() {
        QTcpSocket *socket = server.nextPendingConnection();
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket]() {
            requestBytes += socket->readAll();
            const int split = requestBytes.indexOf("\r\n\r\n");
            if (split < 0) return;
            receivedBody = requestBytes.mid(split + 4);
            const QByteArray lowerRequest = requestBytes.toLower();
            requestValid = lowerRequest.startsWith("put /api/upload/test-upload?token=test-token http/1.1\r\n") &&
                           lowerRequest.contains("content-type: application/octet-stream") &&
                           receivedBody == expected;
            if (receivedBody.size() < expected.size()) return;
            socket->write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            socket->disconnectFromHost();
        });
    });

    HttpUploadTransport transport;
    transport.configure(QStringLiteral("127.0.0.1"), server.serverPort(),
                        QStringLiteral("test-token"), false);
    int result = 1;
    QObject::connect(&transport, &HttpUploadTransport::finished, &app,
                     [&](const QString &uploadId, bool success, const QString &) {
        result = uploadId == QStringLiteral("test-upload") && success &&
                         requestValid && receivedBody == expected
                     ? 0 : 1;
        app.quit();
    });
    QTimer::singleShot(5000, &app, &QCoreApplication::quit);
    if (!transport.upload(QStringLiteral("test-upload"),
                          QStringLiteral("/api/upload/test-upload"), filePath)) {
        qCritical() << "transport rejected upload" << transport.isConfigured();
        return 1;
    }
    app.exec();
    if (result != 0)
        qCritical().noquote() << "[HttpUploadTransportTest] request=" << requestBytes
                              << "bodySize=" << receivedBody.size()
                              << "expectedSize=" << expected.size()
                              << "valid=" << requestValid;
    return result;
}
