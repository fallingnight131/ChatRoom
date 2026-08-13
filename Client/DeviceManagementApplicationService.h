#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>
#include <QTimer>
#include <functional>

class DeviceManagementViewModel;

class DeviceManagementApplicationService final : public QObject {
public:
    using StartCommand = std::function<void()>;
    using StopCommand = std::function<void()>;
    using AuthenticateCommand = std::function<void(const QString &, QByteArray)>;

    DeviceManagementApplicationService(
        DeviceManagementViewModel *viewModel,
        QString username,
        QByteArray passwordUtf8,
        StartCommand start,
        StopCommand stop,
        AuthenticateCommand authenticate,
        int credentialLifetimeMs = 60'000,
        QObject *parent = nullptr);
    ~DeviceManagementApplicationService() override;

    bool start();
    bool readyForAuthentication();
    void authenticated(const QString &currentDeviceId);
    void unavailable();
    void authenticationRejected();
    void stop();
    bool credentialAvailable() const;

private:
    void eraseCredential();
    void expireCredential();

    DeviceManagementViewModel *m_viewModel;
    QString m_username;
    QByteArray m_passwordUtf8;
    StartCommand m_start;
    StopCommand m_stop;
    AuthenticateCommand m_authenticate;
    QTimer m_credentialTimer;
    bool m_started = false;
    bool m_stopped = false;
};
