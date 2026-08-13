#include "WindowsDeviceManagementController.h"
#include "DeviceManagementViewModel.h"
#include "V2LocalMessageRepository.h"

#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <QCoreApplication>
#include <QDebug>
#include <QTemporaryDir>
#include <google/protobuf/message_lite.h>
#include <stdexcept>

namespace {
bool check(bool condition, const QString &message) {
    if (!condition)
        qCritical().noquote() << "[WindowsDeviceManagementControllerTest]" << message;
    return condition;
}

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}

chat::v2::Envelope parse(const QByteArray &bytes) {
    chat::v2::Envelope value;
    if (!value.ParseFromArray(bytes.constData(), static_cast<int>(bytes.size())))
        throw std::runtime_error("parse failed");
    return value;
}

template <typename Payload>
QByteArray response(int type, const std::string &requestId,
                    const std::string &sessionId, const Payload &payload) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(type);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(900);
    envelope.set_payload(serialize(payload));
    const auto bytes = serialize(envelope);
    return QByteArray(bytes.data(), static_cast<qsizetype>(bytes.size()));
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QWebSocket socket;
    QTemporaryDir temporaryDirectory;
    QList<QByteArray> sent;
    V2WindowsDeviceManagementTransport::SocketHooks hooks;
    hooks.subprotocol = [] { return QStringLiteral("chat.v2"); };
    hooks.open = [](const QNetworkRequest &, const QWebSocketHandshakeOptions &) {};
    hooks.sendBinary = [&](const QByteArray &bytes) {
        sent.push_back(bytes);
        return static_cast<qint64>(bytes.size());
    };
    hooks.abort = [] {};
    hooks.connected = [] { return true; };
    const QString deviceId = QStringLiteral("20000000-0000-4000-8000-000000000001");
    WindowsDeviceManagementController controller(
        QUrl(QStringLiteral("wss://chat.example.test/v2/windows")),
        QStringLiteral("2.0.0-test"), deviceId, QStringLiteral("user_01"),
        QByteArrayLiteral("secret"), &socket, std::move(hooks),
        [&](const QString &) {
            return std::make_unique<V2LocalMessageRepository>(
                temporaryDirectory.filePath(QStringLiteral("messages.sqlite")));
        });
    bool messagingReady = false;
    bool messagingUnavailable = false;
    QObject::connect(&controller,
        &WindowsDeviceManagementController::messagingReady,
        [&] { messagingReady = true; });
    QObject::connect(&controller,
        &WindowsDeviceManagementController::messagingUnavailable,
        [&] { messagingUnavailable = true; });
    if (!check(controller.start(), QStringLiteral("controller did not start"))) return 1;
    socket.connected();
    const auto clientHello = parse(sent.takeFirst());
    chat::v2::ServerHello hello;
    hello.set_selected_protocol_version(2);
    hello.set_connection_id("connection-1");
    hello.set_server_time_epoch_ms(900);
    hello.set_maximum_frame_bytes(1024 * 1024 + 1024);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS);
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, clientHello.request_id(), "", hello));
    if (!check(sent.size() == 1,
               QStringLiteral("negotiation did not consume the credential"))) return 1;
    const auto authentication = parse(sent.takeFirst());

    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    chat::v2::SessionEstablished established;
    established.set_account_id("10000000-0000-4000-8000-000000000001");
    established.set_device_id(deviceId.toStdString());
    established.set_session_id(sessionId);
    established.set_resume_token(std::string(32, 'r'));
    established.set_expires_at_epoch_ms(10'000);
    established.set_display_name("Test User");
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED,
        authentication.request_id(), sessionId, established));
    if (!check(sent.size() == 2 && controller.viewModel()->authenticated()
                   && messagingReady && controller.messagingViewModel(),
               QStringLiteral("session establishment did not request devices and conversations"))) return 1;
    chat::v2::Envelope list;
    for (const auto &frame : std::as_const(sent)) {
        const auto candidate = parse(frame);
        if (candidate.message_type() == chat::v2::MESSAGE_TYPE_LIST_DEVICES)
            list = candidate;
    }
    sent.clear();
    if (!check(list.message_type() == chat::v2::MESSAGE_TYPE_LIST_DEVICES,
               QStringLiteral("device request was not composed"))) return 1;

    chat::v2::DeviceDirectory directory;
    auto *current = directory.add_devices();
    current->set_device_id(deviceId.toStdString());
    current->set_platform(chat::v2::CLIENT_PLATFORM_WINDOWS);
    current->set_created_at_epoch_ms(100);
    current->set_last_seen_at_epoch_ms(200);
    current->set_current(true);
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY,
        list.request_id(), sessionId, directory));
    if (!check(controller.viewModel()->devices().size() == 1
                   && controller.viewModel()->devices().first().current,
               QStringLiteral("controller did not project the device directory"))) return 1;
    controller.stop();
    if (!check(!controller.viewModel()->authenticated() && messagingUnavailable,
               QStringLiteral("controller stop retained authenticated UI state"))) return 1;
    qInfo() << "[WindowsDeviceManagementControllerTest] PASS";
    return 0;
}
