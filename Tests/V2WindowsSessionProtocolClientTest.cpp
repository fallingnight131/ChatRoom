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
    Ids forwardingIds;
    V2WindowsSessionProtocolClient forwardingClient(
        "2.0.0-test", deviceId,
        [&] { return forwardingIds.next(); }, [] { return 800; }, true);
    const auto forwardingHello = forwardingClient.createClientHello();
    chat::v2::Envelope forwardingHelloEnvelope;
    chat::v2::ClientHello forwardingHelloPayload;
    check(forwardingHelloEnvelope.ParseFromString(forwardingHello.bytes)
              && forwardingHelloPayload.ParseFromString(
                  forwardingHelloEnvelope.payload())
              && forwardingHelloPayload.capabilities_size() == 5
              && forwardingHelloPayload.capabilities(4)
                  == chat::v2::CLIENT_CAPABILITY_MESSAGE_FORWARDING,
          "enabled Windows forwarding must request capability 5 exactly");
    checkThrows([&] {
        forwardingClient.receive(response(
            chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
            forwardingHello.requestId, "", serverHello));
    }, "enabled Windows forwarding must fail closed when capability 5 is omitted");

    Ids capableForwardingIds;
    V2WindowsSessionProtocolClient capableForwardingClient(
        "2.0.0-test", deviceId,
        [&] { return capableForwardingIds.next(); }, [] { return 800; }, true);
    const auto capableForwardingHello = capableForwardingClient.createClientHello();
    auto forwardingServerHello = serverHello;
    forwardingServerHello.add_enabled_capabilities(
        chat::v2::CLIENT_CAPABILITY_MESSAGE_FORWARDING);
    const auto capableForwardingEvent = capableForwardingClient.receive(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        capableForwardingHello.requestId, "", forwardingServerHello));
    check(capableForwardingEvent.type
              == V2WindowsSessionProtocolClient::EventType::ServerHello,
          "enabled Windows forwarding must accept the exact five-capability hello");
    Ids searchIds;
    V2WindowsSessionProtocolClient searchClient(
        "2.0.0-test", deviceId,
        [&] { return searchIds.next(); }, [] { return 800; }, false, true);
    const auto searchHello = searchClient.createClientHello();
    chat::v2::Envelope searchHelloEnvelope;
    chat::v2::ClientHello searchHelloPayload;
    check(searchHelloEnvelope.ParseFromString(searchHello.bytes)
              && searchHelloPayload.ParseFromString(searchHelloEnvelope.payload())
              && searchHelloPayload.capabilities_size() == 5
              && searchHelloPayload.capabilities(4)
                  == chat::v2::CLIENT_CAPABILITY_MESSAGE_SEARCH,
          "enabled Windows search must request capability 6 without forwarding");
    checkThrows([&] {
        searchClient.receive(response(
            chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
            searchHello.requestId, "", serverHello));
    }, "enabled Windows search must fail closed when capability 6 is omitted");

    Ids capableSearchIds;
    V2WindowsSessionProtocolClient capableSearchClient(
        "2.0.0-test", deviceId,
        [&] { return capableSearchIds.next(); }, [] { return 800; }, true, true);
    const auto capableSearchHello = capableSearchClient.createClientHello();
    auto searchServerHello = forwardingServerHello;
    searchServerHello.add_enabled_capabilities(chat::v2::CLIENT_CAPABILITY_MESSAGE_SEARCH);
    const auto capableSearchEvent = capableSearchClient.receive(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        capableSearchHello.requestId, "", searchServerHello));
    check(capableSearchEvent.type
              == V2WindowsSessionProtocolClient::EventType::ServerHello,
          "enabled Windows forwarding and search must accept the exact six-capability hello");
    Ids accountBlockIds;
    V2WindowsSessionProtocolClient accountBlockClient(
        "2.0.0-test", deviceId,
        [&] { return accountBlockIds.next(); }, [] { return 800; }, false, false, true);
    const auto accountBlockHello = accountBlockClient.createClientHello();
    chat::v2::Envelope accountBlockHelloEnvelope;
    chat::v2::ClientHello accountBlockHelloPayload;
    check(accountBlockHelloEnvelope.ParseFromString(accountBlockHello.bytes)
              && accountBlockHelloPayload.ParseFromString(
                  accountBlockHelloEnvelope.payload())
              && accountBlockHelloPayload.capabilities_size() == 5
              && accountBlockHelloPayload.capabilities(4)
                  == chat::v2::CLIENT_CAPABILITY_ACCOUNT_BLOCKING,
          "enabled Windows account blocking must request capability 7 independently");
    checkThrows([&] {
        accountBlockClient.receive(response(
            chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
            accountBlockHello.requestId, "", serverHello));
    }, "enabled Windows account blocking must fail closed when capability 7 is omitted");

    Ids allCapabilityIds;
    V2WindowsSessionProtocolClient allCapabilityClient(
        "2.0.0-test", deviceId,
        [&] { return allCapabilityIds.next(); }, [] { return 800; }, true, true, true);
    const auto allCapabilityHello = allCapabilityClient.createClientHello();
    auto allCapabilityServerHello = searchServerHello;
    allCapabilityServerHello.add_enabled_capabilities(
        chat::v2::CLIENT_CAPABILITY_ACCOUNT_BLOCKING);
    const auto allCapabilityEvent = allCapabilityClient.receive(response(
        chat::v2::MESSAGE_TYPE_SERVER_HELLO, chat::v2::MESSAGE_KIND_RESPONSE,
        allCapabilityHello.requestId, "", allCapabilityServerHello));
    check(allCapabilityEvent.type
              == V2WindowsSessionProtocolClient::EventType::ServerHello,
          "enabled Windows features must accept the exact seven-capability hello");
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
