#pragma once

#include "DeviceManagementViewModel.h"
#include "V2WindowsSessionProtocolClient.h"
#include <QByteArray>
#include <QNetworkRequest>
#include <QObject>
#include <QSet>
#include <QTimer>
#include <QUrl>
#include <QWebSocket>
#include <QWebSocketHandshakeOptions>
#include <functional>
#include <memory>

class V2WindowsDeviceManagementTransport final : public QObject {
    Q_OBJECT
public:
    enum class State {
        Idle,
        Connecting,
        Negotiating,
        ReadyForAuthentication,
        Authenticating,
        Resuming,
        Authenticated,
        ReconnectWait,
        Stopped
    };
    Q_ENUM(State)

    struct SocketHooks {
        std::function<QString()> subprotocol;
        std::function<void(const QNetworkRequest &, const QWebSocketHandshakeOptions &)> open;
        std::function<qint64(const QByteArray &)> sendBinary;
        std::function<void()> abort;
        std::function<bool()> connected;
    };

    V2WindowsDeviceManagementTransport(
        QUrl endpoint,
        QString appVersion,
        QString clientDeviceId,
        QWebSocket *socket = nullptr,
        SocketHooks hooks = {},
        QObject *parent = nullptr);
    ~V2WindowsDeviceManagementTransport() override;

    State state() const;
    void start();
    void stop();
    void authenticate(const QString &username, QByteArray passwordUtf8);
    QString listDevices();
    QString revokeDevice(const QString &targetDeviceId);
    bool sendMessagingFrame(const QByteArray &frame);
    void rejectMessagingProtocol();
    static bool isValidEndpoint(const QUrl &endpoint);

signals:
    void stateChanged(State state);
    void authenticated(const QString &accountId, const QString &deviceId,
                       const QString &sessionId, const QString &displayName);
    void authenticationRejected(qint64 retryAfterMs);
    void deviceDirectory(const QString &requestId,
                         const QVector<DeviceManagementViewModel::Device> &devices);
    void deviceRevoked(const QString &requestId, const QString &targetDeviceId);
    void messagingFrameReceived(const QByteArray &frame);
    void protocolError(const QString &requestId);
    void failure(const QString &safeReason);

private:
    void connectSocket();
    void handleConnected();
    void handleBinary(const QByteArray &message);
    void handleDisconnected();
    void send(const V2WindowsSessionProtocolClient::Command &command);
    void failProtocol(const QString &reason);
    void scheduleReconnect();
    void armPhaseTimeout(int milliseconds, const QString &reason);
    void transition(State state);
    void clearProtocol();
    void clearResumeCredential();
    bool routeAuthenticatedMessagingFrame(const QByteArray &message);

    QUrl m_endpoint;
    QString m_appVersion;
    QString m_clientDeviceId;
    QWebSocket *m_socket = nullptr;
    bool m_ownsSocket = false;
    SocketHooks m_hooks;
    QTimer m_phaseTimer;
    QTimer m_reconnectTimer;
    std::unique_ptr<V2WindowsSessionProtocolClient> m_protocol;
    QByteArray m_resumeToken;
    QString m_resumeSessionId;
    QSet<QString> m_pendingMessagingRequestIds;
    QString m_timeoutReason;
    State m_state = State::Idle;
    bool m_desired = false;
    int m_reconnectAttempt = 0;
};

Q_DECLARE_METATYPE(V2WindowsDeviceManagementTransport::State)
