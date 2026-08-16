#include "V2WindowsDeviceManagementTransport.h"

#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <QCoreApplication>
#include <QDebug>
#include <QEventLoop>
#include <QTimer>
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
    QList<QUrl> openedEndpoints;
    QEventLoop *reconnectLoop = nullptr;
    bool connected = true;
    bool aborted = false;
    QList<QByteArray> sent;
    V2WindowsDeviceManagementTransport::SocketHooks hooks;
    hooks.subprotocol = [] { return QStringLiteral("chat.v2"); };
    hooks.open = [&](const QNetworkRequest &request,
                     const QWebSocketHandshakeOptions &options) {
        if (!request.hasRawHeader("Origin")
                && options.subprotocols() == QStringList{QStringLiteral("chat.v2")})
            openedEndpoints.push_back(request.url());
        if (reconnectLoop) reconnectLoop->quit();
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
        QStringLiteral("2.0.0-test"), deviceId, &socket, std::move(hooks), nullptr,
        false, {QUrl(QStringLiteral("wss://chat-secondary.example.test/v2/windows"))},
        true);
    transport.start();
    check(openedEndpoints == QList<QUrl>{
              QUrl(QStringLiteral("wss://chat.example.test/v2/windows"))},
          QStringLiteral("start must request exact endpoint and chat.v2 only"));
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
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_SEARCH);
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

    chat::v2::Envelope messagingCommand;
    messagingCommand.set_protocol_version(2);
    messagingCommand.set_kind(chat::v2::MESSAGE_KIND_COMMAND);
    messagingCommand.set_message_type(chat::v2::MESSAGE_TYPE_READ_MESSAGE_HISTORY);
    messagingCommand.set_request_id("50000000-0000-4000-8000-000000000001");
    messagingCommand.set_session_id(sessionId);
    messagingCommand.set_sent_at_epoch_ms(901);
    messagingCommand.set_payload("history-request");
    const auto messagingBytes = serialize(messagingCommand);
    auto disabledForwardCommand = messagingCommand;
    disabledForwardCommand.set_message_type(chat::v2::MESSAGE_TYPE_FORWARD_MESSAGE);
    disabledForwardCommand.set_request_id("50000000-0000-4000-8000-000000000009");
    disabledForwardCommand.set_payload("forward-request");
    const auto disabledForwardBytes = serialize(disabledForwardCommand);
    check(!transport.sendMessagingFrame(QByteArray(
              disabledForwardBytes.data(),
              static_cast<qsizetype>(disabledForwardBytes.size()))),
          QStringLiteral("default transport must reject an unnegotiated forward command"));
    auto wrongSessionCommand = messagingCommand;
    wrongSessionCommand.set_session_id("40000000-0000-4000-8000-000000000002");
    const auto wrongSessionBytes = serialize(wrongSessionCommand);
    check(!transport.sendMessagingFrame(QByteArray(
              wrongSessionBytes.data(), static_cast<qsizetype>(wrongSessionBytes.size()))),
          QStringLiteral("messaging command for another session must be rejected"));
    check(transport.sendMessagingFrame(QByteArray(
              messagingBytes.data(), static_cast<qsizetype>(messagingBytes.size())))
              && sent.size() == 1,
          QStringLiteral("authenticated messaging command must share the product socket"));
    sent.clear();
    QByteArray routedMessagingFrame;
    QObject::connect(&transport,
                     &V2WindowsDeviceManagementTransport::messagingFrameReceived,
                     [&](const QByteArray &value) { routedMessagingFrame = value; });
    chat::v2::Envelope messagingResponse;
    messagingResponse.set_protocol_version(2);
    messagingResponse.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    messagingResponse.set_message_type(chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE);
    messagingResponse.set_request_id(messagingCommand.request_id());
    messagingResponse.set_session_id(sessionId);
    messagingResponse.set_sent_at_epoch_ms(902);
    messagingResponse.set_payload("history-page");
    const auto messagingResponseBytes = serialize(messagingResponse);
    socket.binaryMessageReceived(QByteArray(
        messagingResponseBytes.data(), static_cast<qsizetype>(messagingResponseBytes.size())));
    check(routedMessagingFrame.size() == static_cast<qsizetype>(messagingResponseBytes.size())
              && !aborted,
          QStringLiteral("correlated messaging response must bypass device decoding"));

    auto participantCommand = messagingCommand;
    participantCommand.set_message_type(
        chat::v2::MESSAGE_TYPE_LIST_CONVERSATION_PARTICIPANTS);
    participantCommand.set_request_id("50000000-0000-4000-8000-000000000002");
    participantCommand.set_payload("participant-request");
    const auto participantBytes = serialize(participantCommand);
    check(transport.sendMessagingFrame(QByteArray(
              participantBytes.data(), static_cast<qsizetype>(participantBytes.size())))
              && sent.size() == 1,
          QStringLiteral("participant directory command must share the product socket"));
    sent.clear();
    auto participantResponse = messagingResponse;
    participantResponse.set_message_type(
        chat::v2::MESSAGE_TYPE_CONVERSATION_PARTICIPANT_PAGE);
    participantResponse.set_request_id(participantCommand.request_id());
    participantResponse.set_payload("participant-page");
    const auto participantResponseBytes = serialize(participantResponse);
    routedMessagingFrame.clear();
    socket.binaryMessageReceived(QByteArray(participantResponseBytes.data(),
        static_cast<qsizetype>(participantResponseBytes.size())));
    check(routedMessagingFrame.size()
              == static_cast<qsizetype>(participantResponseBytes.size())
              && !aborted,
          QStringLiteral("correlated participant response must bypass device decoding"));

    auto searchCommand = messagingCommand;
    searchCommand.set_message_type(
        chat::v2::MESSAGE_TYPE_SEARCH_CONVERSATION_MESSAGES);
    searchCommand.set_request_id("50000000-0000-4000-8000-000000000003");
    searchCommand.set_payload("search-request");
    const auto searchBytes = serialize(searchCommand);
    check(transport.sendMessagingFrame(QByteArray(
              searchBytes.data(), static_cast<qsizetype>(searchBytes.size())))
              && sent.size() == 1,
          QStringLiteral("negotiated search command must share the product socket"));
    sent.clear();
    auto searchResponse = messagingResponse;
    searchResponse.set_message_type(
        chat::v2::MESSAGE_TYPE_CONVERSATION_MESSAGE_SEARCH_PAGE);
    searchResponse.set_request_id(searchCommand.request_id());
    searchResponse.set_payload("search-page");
    const auto searchResponseBytes = serialize(searchResponse);
    routedMessagingFrame.clear();
    socket.binaryMessageReceived(QByteArray(searchResponseBytes.data(),
        static_cast<qsizetype>(searchResponseBytes.size())));
    check(routedMessagingFrame.size()
              == static_cast<qsizetype>(searchResponseBytes.size())
              && !aborted,
          QStringLiteral("correlated search response must bypass device decoding"));

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

    sent.clear();
    socket.disconnected();
    QEventLoop loop;
    reconnectLoop = &loop;
    QTimer::singleShot(700, &loop, &QEventLoop::quit);
    loop.exec();
    reconnectLoop = nullptr;
    check(openedEndpoints.size() == 2
              && openedEndpoints.last()
                  == QUrl(QStringLiteral("wss://chat-secondary.example.test/v2/windows")),
          QStringLiteral("socket loss must rotate to the compiled fallback endpoint"));
    socket.connected();
    check(sent.size() == 1, QStringLiteral("fallback connection must restart negotiation"));
    const auto fallbackHelloEnvelope = envelope(sent.takeFirst());
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        fallbackHelloEnvelope.request_id(), "", hello));
    check(transport.state() == V2WindowsDeviceManagementTransport::State::Resuming
              && sent.size() == 1
              && envelope(sent.first()).message_type()
                  == chat::v2::MESSAGE_TYPE_RESUME_SESSION,
          QStringLiteral("fallback negotiation must reuse the memory-only session proof"));
    transport.stop();

    if (failures) return 1;
    qInfo() << "[V2WindowsDeviceManagementTransportTest] PASS";
    return 0;
}
