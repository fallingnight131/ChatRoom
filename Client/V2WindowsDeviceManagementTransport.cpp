#include "V2WindowsDeviceManagementTransport.h"

#include <QRandomGenerator>
#include <QStringList>
#include <algorithm>
#include <stdexcept>

namespace {
constexpr auto subprotocol = "chat.v2";
constexpr int connectTimeoutMs = 10'000;
constexpr int helloTimeoutMs = 5'000;
constexpr int authenticationTimeoutMs = 15'000;
constexpr int reconnectMaximumMs = 30'000;
constexpr quint64 maximumWireBytes = 1024U * 1024U + 1024U;

std::string standard(const QString &value) {
    const QByteArray bytes = value.toUtf8();
    return std::string(bytes.constData(), static_cast<std::size_t>(bytes.size()));
}

QString qt(const std::string &value) {
    return QString::fromUtf8(value.data(), static_cast<qsizetype>(value.size()));
}
}

V2WindowsDeviceManagementTransport::V2WindowsDeviceManagementTransport(
        QUrl endpoint,
        QString appVersion,
        QString clientDeviceId,
        QWebSocket *socket,
        SocketHooks hooks,
        QObject *parent)
    : QObject(parent),
      m_endpoint(std::move(endpoint)),
      m_appVersion(std::move(appVersion)),
      m_clientDeviceId(std::move(clientDeviceId)),
      m_socket(socket ? socket
                      : new QWebSocket(QString(), QWebSocketProtocol::VersionLatest, this)),
      m_ownsSocket(!socket),
      m_hooks(std::move(hooks)) {
    if (!isValidEndpoint(m_endpoint))
        throw std::invalid_argument("Windows V2 endpoint must be exact wss /v2/windows");
    if (!m_hooks.subprotocol) m_hooks.subprotocol = [this] { return m_socket->subprotocol(); };
    if (!m_hooks.open) m_hooks.open = [this](const QNetworkRequest &request,
                                             const QWebSocketHandshakeOptions &options) {
        m_socket->open(request, options);
    };
    if (!m_hooks.sendBinary) m_hooks.sendBinary = [this](const QByteArray &bytes) {
        return m_socket->sendBinaryMessage(bytes);
    };
    if (!m_hooks.abort) m_hooks.abort = [this] { m_socket->abort(); };
    if (!m_hooks.connected) m_hooks.connected = [this] {
        return m_socket->state() == QAbstractSocket::ConnectedState;
    };
    m_socket->setMaxAllowedIncomingFrameSize(maximumWireBytes);
    m_socket->setMaxAllowedIncomingMessageSize(maximumWireBytes);
    m_phaseTimer.setSingleShot(true);
    m_reconnectTimer.setSingleShot(true);
    connect(&m_phaseTimer, &QTimer::timeout, this, [this] { failProtocol(m_timeoutReason); });
    connect(&m_reconnectTimer, &QTimer::timeout, this, &V2WindowsDeviceManagementTransport::connectSocket);
    connect(m_socket, &QWebSocket::connected, this, &V2WindowsDeviceManagementTransport::handleConnected);
    connect(m_socket, &QWebSocket::binaryMessageReceived,
            this, &V2WindowsDeviceManagementTransport::handleBinary);
    connect(m_socket, &QWebSocket::textMessageReceived, this, [this] {
        failProtocol(QStringLiteral("V2 仅接受二进制消息"));
    });
    connect(m_socket, &QWebSocket::disconnected,
            this, &V2WindowsDeviceManagementTransport::handleDisconnected);
    connect(m_socket, &QWebSocket::errorOccurred, this, [this] {
        emit failure(QStringLiteral("V2 连接发生错误"));
    });
}

V2WindowsDeviceManagementTransport::~V2WindowsDeviceManagementTransport() {
    stop();
    if (!m_ownsSocket) disconnect(m_socket, nullptr, this, nullptr);
}

V2WindowsDeviceManagementTransport::State
V2WindowsDeviceManagementTransport::state() const {
    return m_state;
}

void V2WindowsDeviceManagementTransport::start() {
    if (m_desired) return;
    m_desired = true;
    m_reconnectAttempt = 0;
    connectSocket();
}

void V2WindowsDeviceManagementTransport::stop() {
    m_desired = false;
    m_phaseTimer.stop();
    m_reconnectTimer.stop();
    clearProtocol();
    clearResumeCredential();
    if (m_socket && m_socket->state() != QAbstractSocket::UnconnectedState) m_hooks.abort();
    transition(State::Stopped);
}

void V2WindowsDeviceManagementTransport::authenticate(
        const QString &username, QByteArray passwordUtf8) {
    if (m_state != State::ReadyForAuthentication || !m_protocol) {
        passwordUtf8.fill('\0');
        throw std::logic_error("Windows V2 transport is not ready for authentication");
    }
    std::vector<unsigned char> password(
        reinterpret_cast<const unsigned char *>(passwordUtf8.constData()),
        reinterpret_cast<const unsigned char *>(passwordUtf8.constData()) + passwordUtf8.size());
    passwordUtf8.fill('\0');
    send(m_protocol->authenticate(standard(username), std::move(password)));
    transition(State::Authenticating);
    armPhaseTimeout(authenticationTimeoutMs, QStringLiteral("V2 认证超时"));
}

QString V2WindowsDeviceManagementTransport::listDevices() {
    if (m_state != State::Authenticated || !m_protocol)
        throw std::logic_error("Windows V2 transport is not authenticated");
    const auto command = m_protocol->listDevices();
    send(command);
    return qt(command.requestId);
}

QString V2WindowsDeviceManagementTransport::revokeDevice(const QString &targetDeviceId) {
    if (m_state != State::Authenticated || !m_protocol)
        throw std::logic_error("Windows V2 transport is not authenticated");
    const auto command = m_protocol->revokeDevice(standard(targetDeviceId));
    send(command);
    return qt(command.requestId);
}

bool V2WindowsDeviceManagementTransport::isValidEndpoint(const QUrl &endpoint) {
    return endpoint.isValid() && endpoint.scheme() == QStringLiteral("wss")
        && !endpoint.host().isEmpty() && endpoint.userInfo().isEmpty()
        && endpoint.path(QUrl::FullyEncoded) == QStringLiteral("/v2/windows")
        && !endpoint.hasQuery() && !endpoint.hasFragment()
        && endpoint.port(-1) != 0;
}

void V2WindowsDeviceManagementTransport::connectSocket() {
    if (!m_desired || m_socket->state() != QAbstractSocket::UnconnectedState) return;
    m_phaseTimer.stop();
    transition(State::Connecting);
    QNetworkRequest request(m_endpoint);
    QWebSocketHandshakeOptions options;
    options.setSubprotocols({QString::fromLatin1(subprotocol)});
    m_hooks.open(request, options);
    armPhaseTimeout(connectTimeoutMs, QStringLiteral("V2 连接超时"));
}

void V2WindowsDeviceManagementTransport::handleConnected() {
    if (!m_desired) return;
    m_phaseTimer.stop();
    if (m_hooks.subprotocol() != QString::fromLatin1(subprotocol)) {
        failProtocol(QStringLiteral("V2 子协议不匹配"));
        return;
    }
    try {
        clearProtocol();
        m_protocol = std::make_unique<V2WindowsSessionProtocolClient>(
            standard(m_appVersion), standard(m_clientDeviceId));
        transition(State::Negotiating);
        send(m_protocol->createClientHello());
        armPhaseTimeout(helloTimeoutMs, QStringLiteral("V2 协商超时"));
    } catch (...) {
        failProtocol(QStringLiteral("无法启动 V2 协商"));
    }
}

void V2WindowsDeviceManagementTransport::handleBinary(const QByteArray &message) {
    if (!m_protocol) return;
    try {
        const auto event = m_protocol->receive(
            std::string(message.constData(), static_cast<std::size_t>(message.size())));
        switch (event.type) {
        case V2WindowsSessionProtocolClient::EventType::ServerHello:
            m_phaseTimer.stop();
            if (m_resumeToken.size() == 32 && !m_resumeSessionId.isEmpty()) {
                std::vector<unsigned char> token(
                    reinterpret_cast<const unsigned char *>(m_resumeToken.constData()),
                    reinterpret_cast<const unsigned char *>(m_resumeToken.constData())
                        + m_resumeToken.size());
                send(m_protocol->resumeSession(standard(m_resumeSessionId), std::move(token)));
                transition(State::Resuming);
                armPhaseTimeout(authenticationTimeoutMs, QStringLiteral("V2 恢复会话超时"));
            } else {
                transition(State::ReadyForAuthentication);
            }
            break;
        case V2WindowsSessionProtocolClient::EventType::SessionEstablished: {
            m_phaseTimer.stop();
            const auto *session = m_protocol->session();
            if (!session) throw std::runtime_error("missing established session");
            clearResumeCredential();
            m_resumeSessionId = qt(session->sessionId);
            m_resumeToken = QByteArray(
                reinterpret_cast<const char *>(session->resumeToken.data()),
                static_cast<qsizetype>(session->resumeToken.size()));
            m_reconnectAttempt = 0;
            transition(State::Authenticated);
            emit authenticated(qt(session->accountId), qt(session->deviceId),
                               qt(session->sessionId), qt(session->displayName));
            break;
        }
        case V2WindowsSessionProtocolClient::EventType::AuthenticationRejected:
            m_phaseTimer.stop();
            clearResumeCredential();
            emit authenticationRejected(event.retryAfterMs);
            if (m_state != State::Stopped) m_hooks.abort();
            break;
        case V2WindowsSessionProtocolClient::EventType::ProtocolError:
            emit protocolError(qt(event.requestId));
            if (m_protocol->state() == V2WindowsSessionProtocolClient::State::Closed)
                m_hooks.abort();
            break;
        case V2WindowsSessionProtocolClient::EventType::DeviceDirectory: {
            QVector<DeviceManagementViewModel::Device> devices;
            devices.reserve(static_cast<qsizetype>(event.device.devices.size()));
            for (const auto &device : event.device.devices) {
                devices.push_back({
                    qt(device.deviceId),
                    device.platform == V2DeviceManagementProtocolClient::Platform::Windows
                        ? DeviceManagementViewModel::Platform::Windows
                        : DeviceManagementViewModel::Platform::Web,
                    device.createdAtEpochMs, device.lastSeenAtEpochMs, device.current});
            }
            emit deviceDirectory(qt(event.requestId), devices);
            break;
        }
        case V2WindowsSessionProtocolClient::EventType::DeviceRevoked:
            emit deviceRevoked(qt(event.requestId), qt(event.device.targetDeviceId));
            break;
        }
    } catch (...) {
        failProtocol(QStringLiteral("V2 协议数据无效"));
    }
}

void V2WindowsDeviceManagementTransport::handleDisconnected() {
    m_phaseTimer.stop();
    clearProtocol();
    if (m_desired) scheduleReconnect();
}

void V2WindowsDeviceManagementTransport::send(
        const V2WindowsSessionProtocolClient::Command &command) {
    if (!m_hooks.connected())
        throw std::runtime_error("V2 socket is not connected");
    const QByteArray bytes(command.bytes.data(), static_cast<qsizetype>(command.bytes.size()));
    if (m_hooks.sendBinary(bytes) != bytes.size())
        throw std::runtime_error("V2 send did not accept the full message");
}

void V2WindowsDeviceManagementTransport::failProtocol(const QString &reason) {
    m_phaseTimer.stop();
    emit failure(reason);
    clearProtocol();
    m_hooks.abort();
}

void V2WindowsDeviceManagementTransport::scheduleReconnect() {
    if (m_reconnectTimer.isActive() || !m_desired) return;
    const int exponent = std::min(m_reconnectAttempt++, 15);
    const int ceiling = std::min(reconnectMaximumMs, 500 * (1 << exponent));
    const int delay = QRandomGenerator::global()->bounded(ceiling + 1);
    transition(State::ReconnectWait);
    m_reconnectTimer.start(delay);
}

void V2WindowsDeviceManagementTransport::armPhaseTimeout(
        int milliseconds, const QString &reason) {
    m_timeoutReason = reason;
    m_phaseTimer.start(milliseconds);
}

void V2WindowsDeviceManagementTransport::transition(State state) {
    if (state == m_state) return;
    m_state = state;
    emit stateChanged(state);
}

void V2WindowsDeviceManagementTransport::clearProtocol() {
    if (m_protocol) m_protocol->close();
    m_protocol.reset();
}

void V2WindowsDeviceManagementTransport::clearResumeCredential() {
    m_resumeToken.fill('\0');
    m_resumeToken.clear();
    m_resumeSessionId.clear();
}
