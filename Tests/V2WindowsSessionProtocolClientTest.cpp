#include "V2WindowsSessionProtocolClient.h"

#include "chat/v2/authentication.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {
int failures = 0;

void check(bool condition, const std::string &message) {
    if (!condition) {
        ++failures;
        std::cerr << message << '\n';
    }
}

template <typename Function>
void checkThrows(Function action, const std::string &message) {
    try {
        action();
        check(false, message);
    } catch (const std::exception &) {
    }
}

std::string serialize(const google::protobuf::MessageLite &message) {
    std::string result;
    if (!message.SerializeToString(&result)) throw std::runtime_error("encode failed");
    return result;
}

template <typename Payload>
std::string response(
        int type,
        chat::v2::MessageKind kind,
        const std::string &requestId,
        const std::string &sessionId,
        const Payload &payload) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(kind);
    envelope.set_message_type(type);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(900);
    envelope.set_payload(serialize(payload));
    return serialize(envelope);
}

class Ids final {
public:
    std::string next() {
        return "30000000-0000-4000-8000-" + suffix(m_next++);
    }

private:
    static std::string suffix(int value) {
        std::string digits = std::to_string(value);
        return std::string(12 - digits.size(), '0') + digits;
    }
    int m_next = 1;
};
}

int main() {
    const std::string accountId = "10000000-0000-4000-8000-000000000001";
    const std::string deviceId = "20000000-0000-4000-8000-000000000001";
    const std::string targetId = "20000000-0000-4000-8000-000000000002";
    const std::string sessionId = "40000000-0000-4000-8000-000000000001";
    Ids ids;
    V2WindowsSessionProtocolClient client(
        "2.0.0-test", deviceId, [&] { return ids.next(); }, [] { return 800; });

    check(client.state() == V2WindowsSessionProtocolClient::State::New,
          "Windows V2 client must start new");
    checkThrows([&] { client.authenticate("user", {'p'}); },
                "authentication before negotiation must fail");
    const auto hello = client.createClientHello();
    chat::v2::Envelope helloEnvelope;
    chat::v2::ClientHello helloPayload;
    check(helloEnvelope.ParseFromString(hello.bytes)
              && helloPayload.ParseFromString(helloEnvelope.payload())
              && helloEnvelope.message_type() == chat::v2::MESSAGE_TYPE_CLIENT_HELLO
              && helloEnvelope.sent_at_epoch_ms() == 800
              && helloEnvelope.session_id().empty()
              && helloPayload.platform() == chat::v2::CLIENT_PLATFORM_WINDOWS
              && helloPayload.client_device_id() == deviceId
              && helloPayload.capabilities_size() == 4
              && helloPayload.capabilities(0)
                    == chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS
              && helloPayload.capabilities(1)
                    == chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS
              && helloPayload.capabilities(2)
                    == chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS
              && helloPayload.capabilities(3)
                    == chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS,
          "hello must identify the exact capable Windows client without session authority");

    chat::v2::ServerHello serverHello;
    serverHello.set_selected_protocol_version(2);
    serverHello.set_connection_id("connection-1");
    serverHello.set_server_time_epoch_ms(900);
    serverHello.set_maximum_frame_bytes(1024 * 1024 + 1024);
    serverHello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_REACTIONS);
    serverHello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_PINS);
    serverHello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_EDITS);
    serverHello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_MENTIONS);
    Ids downgradeIds;
    V2WindowsSessionProtocolClient downgradeClient(
        "2.0.0-test", deviceId,
        [&] { return downgradeIds.next(); }, [] { return 800; });
    const auto downgradeHello = downgradeClient.createClientHello();
    auto incompleteHello = serverHello;
    incompleteHello.mutable_enabled_capabilities()->RemoveLast();
    checkThrows([&] {
        downgradeClient.receive(response(
            chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
            downgradeHello.requestId, "", incompleteHello));
    }, "Windows mention UI must fail closed when capability 4 is not negotiated");
    const auto helloEvent = client.receive(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        hello.requestId, "", serverHello));
    check(helloEvent.type == V2WindowsSessionProtocolClient::EventType::ServerHello
              && client.state() == V2WindowsSessionProtocolClient::State::Negotiated,
          "valid ServerHello must complete exact-version negotiation");

    checkThrows([&] { client.authenticate("user_01", {0xc0U, 0x80U}); },
                "fresh authentication must reject non-canonical UTF-8 passwords");

    const std::vector<unsigned char> password{'s', 'e', 'c', 'r', 'e', 't'};
    const auto authentication = client.authenticate("user_01", password);
    chat::v2::Envelope authenticationEnvelope;
    chat::v2::Authenticate authenticationPayload;
    check(authenticationEnvelope.ParseFromString(authentication.bytes)
              && authenticationPayload.ParseFromString(authenticationEnvelope.payload())
              && authenticationEnvelope.message_type() == chat::v2::MESSAGE_TYPE_AUTHENTICATE
              && authenticationPayload.username() == "user_01"
              && authenticationPayload.password_utf8() == "secret",
          "fresh authentication must use the registered bounded binary payload");
    check(password == std::vector<unsigned char>({'s', 'e', 'c', 'r', 'e', 't'}),
          "the caller retains ownership of its credential buffer");

    chat::v2::SessionEstablished established;
    established.set_account_id(accountId);
    established.set_device_id(deviceId);
    established.set_session_id(sessionId);
    established.set_resume_token(std::string(32, 'r'));
    established.set_expires_at_epoch_ms(10'000);
    established.set_display_name("Test User");
    const auto sessionEvent = client.receive(response(
        chat::v2::MESSAGE_TYPE_SESSION_ESTABLISHED, chat::v2::MESSAGE_KIND_RESPONSE,
        authentication.requestId, sessionId, established));
    check(sessionEvent.type
              == V2WindowsSessionProtocolClient::EventType::SessionEstablished
              && sessionEvent.session.resumeToken.empty()
              && client.state() == V2WindowsSessionProtocolClient::State::Authenticated
              && client.session() && client.session()->sessionId == sessionId
              && client.session()->resumeToken.size() == 32,
          "established session must bind server identity and memory-only resume material");

    const auto list = client.listDevices();
    chat::v2::DeviceDirectory directory;
    auto *current = directory.add_devices();
    current->set_device_id(deviceId);
    current->set_platform(chat::v2::CLIENT_PLATFORM_WINDOWS);
    current->set_created_at_epoch_ms(100);
    current->set_last_seen_at_epoch_ms(200);
    current->set_current(true);
    auto *target = directory.add_devices();
    target->set_device_id(targetId);
    target->set_platform(chat::v2::CLIENT_PLATFORM_WEB);
    target->set_created_at_epoch_ms(100);
    target->set_last_seen_at_epoch_ms(200);
    const auto directoryEvent = client.receive(response(
        chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY, chat::v2::MESSAGE_KIND_RESPONSE,
        list.requestId, sessionId, directory));
    check(directoryEvent.type
              == V2WindowsSessionProtocolClient::EventType::DeviceDirectory
              && directoryEvent.device.devices.size() == 2,
          "authenticated device responses must pass through the hardened codec");

    Ids resumeIds;
    V2WindowsSessionProtocolClient resumeClient(
        "2.0.0-test", deviceId,
        [&] { return resumeIds.next(); }, [] { return 800; });
    const auto resumeHello = resumeClient.createClientHello();
    resumeClient.receive(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        resumeHello.requestId, "", serverHello));
    const auto resume = resumeClient.resumeSession(
        sessionId, std::vector<unsigned char>(32, static_cast<unsigned char>('r')));
    chat::v2::Envelope resumeEnvelope;
    chat::v2::ResumeSession resumePayload;
    check(resumeEnvelope.ParseFromString(resume.bytes)
              && resumePayload.ParseFromString(resumeEnvelope.payload())
              && resumeEnvelope.message_type() == chat::v2::MESSAGE_TYPE_RESUME_SESSION
              && resumePayload.session_id() == sessionId
              && resumePayload.resume_token().size() == 32,
          "reconnect must encode an exact 32-byte in-memory resume credential");

    chat::v2::AuthenticationRejected rejected;
    rejected.set_reason(chat::v2::AUTHENTICATION_REJECTION_REASON_REJECTED);
    rejected.set_retry_after_ms(0);
    const auto rejectedEvent = resumeClient.receive(response(
        chat::v2::MESSAGE_TYPE_AUTHENTICATION_REJECTED, chat::v2::MESSAGE_KIND_ERROR,
        resume.requestId, "", rejected));
    check(rejectedEvent.type
              == V2WindowsSessionProtocolClient::EventType::AuthenticationRejected
              && resumeClient.state() == V2WindowsSessionProtocolClient::State::Closed,
          "rejected resume must terminate the unauthenticated protocol state");

    client.close();
    check(client.session() == nullptr
              && client.state() == V2WindowsSessionProtocolClient::State::Closed,
          "close must remove session and resume authority");

    if (failures) return 1;
    std::cout << "[V2WindowsSessionProtocolClientTest] PASS\n";
    return 0;
}
