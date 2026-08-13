#include "WindowsV2MessagingController.h"

#include "V2LocalMessageRepository.h"
#include "V2WindowsMessagingViewModel.h"
#include "V2WindowsConversationDirectoryViewModel.h"
#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/conversation.pb.h"
#include "chat/v2/envelope.pb.h"
#include "chat/v2/messaging.pb.h"

#include <QCoreApplication>
#include <QDebug>
#include <QTemporaryDir>
#include <google/protobuf/message_lite.h>
#include <stdexcept>

namespace {
int failures = 0;

void check(bool condition, const QString &message) {
    if (!condition) { ++failures; qCritical().noquote() << message; }
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

chat::v2::Envelope parseEnvelope(const QByteArray &bytes) {
    chat::v2::Envelope result;
    if (!result.ParseFromArray(bytes.constData(), static_cast<int>(bytes.size())))
        throw std::runtime_error("invalid envelope");
    return result;
}
}

int main(int argc, char **argv) {
    QCoreApplication application(argc, argv);
    QTemporaryDir directory;
    check(directory.isValid(), QStringLiteral("temporary repository directory must exist"));

    QWebSocket socket;
    QList<QByteArray> sent;
    V2WindowsDeviceManagementTransport::SocketHooks hooks;
    hooks.subprotocol = [] { return QStringLiteral("chat.v2"); };
    hooks.open = [](const QNetworkRequest &, const QWebSocketHandshakeOptions &) {};
    hooks.sendBinary = [&](const QByteArray &frame) {
        sent.append(frame);
        return static_cast<qint64>(frame.size());
    };
    hooks.abort = [] {};
    hooks.connected = [] { return true; };

    const QString deviceId = QStringLiteral("20000000-0000-4000-8000-000000000001");
    V2WindowsDeviceManagementTransport transport(
        QUrl(QStringLiteral("wss://chat.example.test/v2/windows")),
        QStringLiteral("2.0.0-test"), deviceId, &socket, std::move(hooks));
    WindowsV2MessagingController controller(
        &transport, [&](const QString &) {
            return std::make_unique<V2LocalMessageRepository>(
                directory.filePath(QStringLiteral("messages.sqlite")));
        });
    bool ready = false;
    bool unavailable = false;
    QObject::connect(&controller, &WindowsV2MessagingController::ready,
                     [&] { ready = true; });
    QObject::connect(&controller, &WindowsV2MessagingController::unavailable,
                     [&] { unavailable = true; });

    transport.start();
    socket.connected();
    auto command = parseEnvelope(sent.takeFirst());
    chat::v2::ServerHello hello;
    hello.set_selected_protocol_version(2);
    hello.set_connection_id("connection-1");
    hello.set_server_time_epoch_ms(900);
    hello.set_maximum_frame_bytes(1024 * 1024 + 1024);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    hello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS);
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        command.request_id(), "", hello));
    transport.authenticate(QStringLiteral("user_01"), QByteArrayLiteral("secret"));
    command = parseEnvelope(sent.takeFirst());

    const std::string accountId = "10000000-0000-4000-8000-000000000001";
    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    chat::v2::SessionEstablished established;
    established.set_account_id(accountId);
    established.set_device_id(deviceId.toStdString());
    established.set_session_id(sessionId);
    established.set_resume_token(std::string(32, 'r'));
    established.set_expires_at_epoch_ms(10'000);
    established.set_display_name("Test User");
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED, chat::v2::MESSAGE_KIND_RESPONSE,
        command.request_id(), sessionId, established));
    check(ready && controller.viewModel(),
          QStringLiteral("authentication must compose the account-isolated message runtime"));

    check(sent.size() == 1,
          QStringLiteral("authenticated runtime must request the first conversation page"));
    command = parseEnvelope(sent.takeFirst());
    chat::v2::ConversationDirectoryPage directoryPage;
    auto *conversation = directoryPage.add_conversations();
    conversation->set_conversation_id(
        "60000000-0000-4000-8000-000000000001");
    conversation->set_kind(chat::v2::CONVERSATION_KIND_GROUP);
    conversation->set_display_name("Engineering");
    conversation->set_role(chat::v2::CONVERSATION_ROLE_MEMBER);
    conversation->set_latest_sequence(1);
    conversation->set_last_read_sequence(0);
    conversation->set_updated_at_epoch_ms(901);
    directoryPage.set_next_updated_at_epoch_ms(901);
    directoryPage.set_next_conversation_id(conversation->conversation_id());
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_CONVERSATION_DIRECTORY_PAGE,
        chat::v2::MESSAGE_KIND_RESPONSE, command.request_id(), sessionId,
        directoryPage));
    check(controller.directoryViewModel()->rows().size() == 1
              && controller.directoryViewModel()->rows().first().displayName
                    == QStringLiteral("Engineering")
              && controller.directoryViewModel()->rows().first().unreadCount == 1,
          QStringLiteral("directory response must project a user-facing unread row"));

    const QString conversationId = QStringLiteral("60000000-0000-4000-8000-000000000001");
    check(controller.directoryViewModel()->openConversation(conversationId)
              && sent.size() == 1,
          QStringLiteral("opening a conversation must hydrate cache and request cursor history"));
    command = parseEnvelope(sent.takeFirst());
    chat::v2::ReadMessageHistory historyRequest;
    check(historyRequest.ParseFromString(command.payload())
              && historyRequest.conversation_id() == conversationId.toStdString()
              && historyRequest.after_sequence() == 0,
          QStringLiteral("first product history request must start at durable cursor zero"));

    chat::v2::MessageHistoryPage page;
    page.set_conversation_id(conversationId.toStdString());
    auto *message = page.add_messages();
    message->set_conversation_id(conversationId.toStdString());
    message->set_message_id("70000000-0000-4000-8000-000000000001");
    message->set_conversation_sequence(1);
    message->set_sender_account_id(accountId);
    message->set_sender_device_id(deviceId.toStdString());
    message->set_client_message_id("80000000-0000-4000-8000-000000000001");
    message->set_content_type(chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8);
    message->set_content("hello");
    message->set_accepted_at_epoch_ms(950);
    page.set_next_sequence(1);
    page.set_latest_sequence(1);
    socket.binaryMessageReceived(response(
        chat::v2::MESSAGE_TYPE_MESSAGE_HISTORY_PAGE, chat::v2::MESSAGE_KIND_RESPONSE,
        command.request_id(), sessionId, page));
    const auto rows = controller.viewModel()->rows();
    check(rows.size() == 1 && rows.first().text == QStringLiteral("hello"),
          QStringLiteral("routed server history must commit before refreshing the view model"));

    transport.stop();
    check(unavailable,
          QStringLiteral("transport stop must abandon only in-memory messaging session state"));
    if (failures) return 1;
    qInfo() << "[WindowsV2MessagingControllerTest] PASS";
    return 0;
}
