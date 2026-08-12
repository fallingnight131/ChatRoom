#include "NetworkManager.h"

#include <QCoreApplication>
#include <QDebug>
#include <QFile>
#include <QSslCertificate>
#include <QSslConfiguration>
#include <QTimer>

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    if (app.arguments().size() < 4 || app.arguments().size() > 5) {
        qCritical() << "usage: NetworkTlsPolicyTest HOST PORT reject|accept [CA_FILE]";
        return 2;
    }
    bool portOk = false;
    const int parsedPort = app.arguments().at(2).toInt(&portOk);
    if (!portOk || parsedPort < 1 || parsedPort > 65535) {
        qCritical() << "invalid TLS test port";
        return 2;
    }
    const QString mode = app.arguments().at(3);
    if (mode != QStringLiteral("reject") && mode != QStringLiteral("accept")) {
        qCritical() << "invalid TLS test mode";
        return 2;
    }
    if (mode == QStringLiteral("accept")) {
        if (app.arguments().size() != 5) {
            qCritical() << "accept mode requires CA_FILE";
            return 2;
        }
        const QList<QSslCertificate> certificates =
            QSslCertificate::fromPath(app.arguments().at(4));
        if (certificates.size() != 1) {
            qCritical() << "test CA certificate is unreadable";
            return 2;
        }
        QSslConfiguration configuration = QSslConfiguration::defaultConfiguration();
        configuration.addCaCertificate(certificates.first());
        QSslConfiguration::setDefaultConfiguration(configuration);
    }

    NetworkManager *network = NetworkManager::instance();
    bool connected = false;
    bool certificateRejected = false;
    QObject::connect(network, &NetworkManager::connected, &app, [&] {
        connected = true;
        app.quit();
    });
    QObject::connect(network, &NetworkManager::connectionError, &app,
                     [&](const QString &error) {
        if (error == QStringLiteral("TLS certificate validation failed")) {
            certificateRejected = true;
            app.quit();
        }
    });
    QTimer::singleShot(8000, &app, &QCoreApplication::quit);
    network->connectToServer(
        app.arguments().at(1), static_cast<quint16>(parsedPort), true);
    app.exec();
    network->disconnectFromServer();

    const bool passed = mode == QStringLiteral("reject")
        ? (!connected && certificateRejected)
        : (connected && !certificateRejected);
    if (!passed) {
        qCritical() << "TLS trust policy outcome was unexpected"
                    << "mode=" << mode
                    << "connected=" << connected
                    << "certificateRejected=" << certificateRejected;
        return 1;
    }
    return 0;
}
