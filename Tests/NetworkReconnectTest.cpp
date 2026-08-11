#include "NetworkManager.h"
#include "Protocol.h"

#include <QCoreApplication>
#include <QJsonObject>
#include <QTcpServer>
#include <QTcpSocket>
#include <QTimer>
#include <QDebug>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QTcpServer server;
    if (!server.listen(QHostAddress::LocalHost, 0)) {
        qCritical() << "reconnect test server failed to listen";
        return 1;
    }

    NetworkManager *network = NetworkManager::instance();
    int acceptedConnections = 0;
    int connectedSignals = 0;
    int successfulLogins = 0;
    bool prematureRestoredSignal = false;
    bool sawRestoredCredentials = false;
    bool sawRejectedRestoreCredentials = false;
    bool restoreFailureReported = false;

    QObject::connect(&server, &QTcpServer::newConnection, [&] {
        QTcpSocket *socket = server.nextPendingConnection();
        ++acceptedConnections;
        if ((acceptedConnections == 2 && connectedSignals != 1) ||
            (acceptedConnections == 3 && connectedSignals != 2))
            prematureRestoredSignal = true;
        auto *buffer = new QByteArray;
        QObject::connect(socket, &QTcpSocket::readyRead, socket, [&, socket, buffer] {
            buffer->append(socket->readAll());
            QJsonObject request;
            while (Protocol::unpack(*buffer, request)) {
                if (request["type"].toString() != Protocol::MsgType::LOGIN_REQ)
                    continue;
                const QJsonObject credentials = request["data"].toObject();
                if (acceptedConnections >= 2 &&
                    credentials["username"].toString() == QStringLiteral("reconnect_user") &&
                    credentials["password"].toString() == QStringLiteral("memory-only-secret")) {
                    if (acceptedConnections == 2) sawRestoredCredentials = true;
                    if (acceptedConnections == 3) sawRejectedRestoreCredentials = true;
                }
                QJsonObject response;
                response["success"] = acceptedConnections < 3;
                if (acceptedConnections == 3)
                    response["error"] = QStringLiteral("rejected restore");
                response["userId"] = 7;
                response["username"] = QStringLiteral("reconnect_user");
                response["displayName"] = QStringLiteral("Reconnect User");
                socket->write(Protocol::pack(
                    Protocol::makeMessage(Protocol::MsgType::LOGIN_RSP, response)));
                socket->flush();
            }
        });
    });

    QObject::connect(network, &NetworkManager::connected, [&] {
        ++connectedSignals;
        if (connectedSignals == 1) {
            network->loginWithCredentials(QStringLiteral("reconnect_user"),
                                          QStringLiteral("memory-only-secret"));
        } else if (connectedSignals == 2) {
            const auto sockets = server.findChildren<QTcpSocket *>();
            for (QTcpSocket *socket : sockets) {
                if (socket->state() == QAbstractSocket::ConnectedState)
                    socket->disconnectFromHost();
            }
        }
    });
    QObject::connect(network, &NetworkManager::loginResponse,
                     [&](bool success, const QString &, int, const QString &, const QString &) {
        if (!success) return;
        ++successfulLogins;
        if (successfulLogins == 1) {
            const auto sockets = server.findChildren<QTcpSocket *>();
            for (QTcpSocket *socket : sockets) {
                if (socket->state() == QAbstractSocket::ConnectedState)
                    socket->disconnectFromHost();
            }
        }
    });
    QObject::connect(network, &NetworkManager::forceOffline,
                     [&](const QString &) {
        restoreFailureReported = true;
        app.quit();
    });

    QTimer::singleShot(20000, &app, &QCoreApplication::quit);
    network->connectToServer(QStringLiteral("127.0.0.1"), server.serverPort());
    app.exec();

    const bool passed = acceptedConnections == 3 && connectedSignals == 2 &&
        successfulLogins == 2 && sawRestoredCredentials &&
        sawRejectedRestoreCredentials && restoreFailureReported &&
        network->currentUsername().isEmpty() && !prematureRestoredSignal;
    network->disconnectFromServer();
    if (!passed) {
        qCritical() << "Network reconnect verification failed"
                    << acceptedConnections << connectedSignals << successfulLogins
                    << sawRestoredCredentials << sawRejectedRestoreCredentials
                    << restoreFailureReported << prematureRestoredSignal;
    }
    return passed ? 0 : 1;
}
