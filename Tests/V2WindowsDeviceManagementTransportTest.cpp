#include "V2WindowsDeviceManagementTransport.h"

#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <QCoreApplication>
#include <QDebug>
#include <google/protobuf/message_lite.h>
#include <stdexcept>

namespace {
int failures = 0;

void check(bool condition, const QString &message) {
    if (!condition) {
        ++failures;
        qCritical().noquote() << message;
    }
}

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}

template <typename Payload>
QByteArray response(int type, chat::v2::MessageKind kind, const std::string &requestId,
                    const std::string &sessionId, const Payload &payload) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(kind);
    envelope.set_message_type(type);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(900);
    envelope.set_payload(serialize(payload));
    const std::string bytes = serialize(envelope);
    return QByteArray(bytes.data(), static_cast<qsizetype>(bytes.size()));
}

chat::v2::Envelope envelope(const QByteArray &bytes) {
    chat::v2::Envelope result;
    if (!result.ParseFromArray(bytes.constData(), static_cast<int>(bytes.size())))
        throw std::runtime_error("invalid test envelope");
    return result;
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    check(V2WindowsDeviceManagementTransport::isValidEndpoint(
              QUrl(QStringLiteral("wss://chat.example.test/v2/windows"))),
          QStringLiteral("exact Windows WSS endpoint must be accepted"));
    check(!V2WindowsDeviceManagementTransport::isValidEndpoint(
              QUrl(QStringLiteral("ws://chat.example.test/v2/windows")))
              && !V2WindowsDeviceManagementTransport::isValidEndpoint(
                  QUrl(QStringLiteral("wss://chat.example.test/v2/web")))
              && !V2WindowsDeviceManagementTransport::isValidEndpoint(
                  QUrl(QStringLiteral("wss://user@chat.example.test/v2/windows"))),
          QStringLiteral("insecure, Web, and user-info endpoints must be rejected"));

    QWebSocket socket;
    bool openCalled = false;
    bool connected = true;
    bool aborted = false;
    QList<QByteArray> sent;
    V2WindowsDeviceManagementTransport::SocketHooks hooks;
    hooks.subprotocol = [] { return QStringLiteral("chat.v2"); };
    hooks.open = [&](const QNetworkRequest &request,
                     const QWebSocketHandshakeOptions &options) {
        openCalled = request.url() == QUrl(QStringLiteral("wss://chat.example.test/v2/windows"))
            && !request.hasRawHeader("Origin")
            && options.subprotocols() == QStringList{QStringLiteral("chat.v2")};
    };
    hooks.sendBinary = [&](const QByteArray &bytes) {
        sent.push_back(bytes);
        return static_cast<qint64>(bytes.size());
    };
    hooks.abort = [&] { aborted = true; };
    hooks.connected = [&] { return connected; };

    const QString deviceId = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2WindowsDeviceManagementTransport transport(
        QUrl(QStringLiteral("wss://chat.example.test/v2/windows")),
        QStringLiteral("2.0.0-test"), deviceId, &socket, std::move(hooks));
    transport.start();
    check(openCalled, QStringLiteral("start must request exact endpoint and chat.v2 only"));
    socket.connected();
    check(transport.state()
              == V2WindowsDeviceManagementTransport::State::Negotiating
              && sent.size() == 1,
          QStringLiteral("connected socket must send one ClientHello"));
    const auto helloEnvelope = envelope(sent.takeFirst());

    chat::v2::ServerHello hello;
    hello.set_selected_protocol_version(2);
    hello.set_connection_id("connection-1");
    hello.set_server_time_epoch_ms(900);
    hello.set_maximum_frame_bytes(1024 * 1024 + 1024);
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        helloEnvelope.request_id(), "", hello));
    check(transport.state()
              == V2WindowsDeviceManagementTransport::State::ReadyForAuthentication,
          QStringLiteral("valid hello must expose fresh authentication readiness"));

    transport.authenticate(QStringLiteral("user_01"), QByteArrayLiteral("secret"));
    check(transport.state() == V2WindowsDeviceManagementTransport::State::Authenticating
              && sent.size() == 1,
          QStringLiteral("fresh credential must send one bounded authentication command"));
    const auto authenticationEnvelope = envelope(sent.takeFirst());
    chat::v2::Authenticate authentication;
    check(authentication.ParseFromString(authenticationEnvelope.payload())
              && authentication.password_utf8() == "secret",
          QStringLiteral("transport must preserve UTF-8 password bytes on the wire"));

    const std::string accountId = "10000000-0000-4000-8000-000000000001";
    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    chat::v2::SessionEstablished established;
    established.set_account_id(accountId);
    established.set_device_id(deviceId.toStdString());
    established.set_session_id(sessionId);
    established.set_resume_token(std::string(32, 'r'));
    established.set_expires_at_epoch_ms(10'000);
    established.set_display_name("Test User");
    QString authenticatedDevice;
    QObject::connect(&transport, &V2WindowsDeviceManagementTransport::authenticated,
                     [&](const QString &, const QString &value, const QString &, const QString &) {
        authenticatedDevice = value;
    });
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED, chat::v2::MESSAGE_KIND_RESPONSE,
        authenticationEnvelope.request_id(), sessionId, established));
    check(transport.state() == V2WindowsDeviceManagementTransport::State::Authenticated
              && authenticatedDevice == deviceId,
          QStringLiteral("server session authority must activate the Windows transport"));

    const QString listRequest = transport.listDevices();
    sent.clear();
    chat::v2::DeviceDirectory directory;
    auto *current = directory.add_devices();
    current->set_device_id(deviceId.toStdString());
    current->set_platform(chat::v2::CLIENT_PLATFORM_WINDOWS);
    current->set_created_at_epoch_ms(100);
    current->set_last_seen_at_epoch_ms(200);
    current->set_current(true);
    QVector<DeviceManagementViewModel::Device> projected;
    QObject::connect(&transport, &V2WindowsDeviceManagementTransport::deviceDirectory,
                     [&](const QString &, const QVector<DeviceManagementViewModel::Device> &value) {
        projected = value;
    });
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY, chat::v2::MESSAGE_KIND_RESPONSE,
        listRequest.toStdString(), sessionId, directory));
    check(projected.size() == 1 && projected.first().current,
          QStringLiteral("validated device response must map to the Qt projection"));

    socket.textMessageReceived(QStringLiteral("not binary"));
    check(aborted, QStringLiteral("text frames must fail closed"));
    transport.stop();

    if (failures) return 1;
    qInfo() << "[V2WindowsDeviceManagementTransportTest] PASS";
    return 0;
}
