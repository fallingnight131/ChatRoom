#include "V2DeviceManagementProtocolClient.h"

#include "chat/v2/control.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/envelope.pb.h"
#include <google/protobuf/message_lite.h>
#include <iostream>
#include <stdexcept>
#include <string>
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
    std::string encoded;
    if (!message.SerializeToString(&encoded)) throw std::runtime_error("encode failed");
    return encoded;
}

template <typename Payload>
std::string response(int type, chat::v2::MessageKind kind, const std::string &requestId,
                     const std::string &sessionId, const Payload &payload) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(kind);
    envelope.set_message_type(type);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_payload(serialize(payload));
    return serialize(envelope);
}

chat::v2::DeviceDirectory directory(
        const std::string &currentId, const std::string &targetId) {
    chat::v2::DeviceDirectory result;
    auto *current = result.add_devices();
    current->set_device_id(currentId);
    current->set_platform(chat::v2::CLIENT_PLATFORM_WINDOWS);
    current->set_created_at_epoch_ms(100);
    current->set_last_seen_at_epoch_ms(200);
    current->set_current(true);
    auto *target = result.add_devices();
    target->set_device_id(targetId);
    target->set_platform(chat::v2::CLIENT_PLATFORM_WEB);
    target->set_created_at_epoch_ms(150);
    target->set_last_seen_at_epoch_ms(250);
    return result;
}
}

int main() {
    const std::string sessionId = "10000000-0000-4000-8000-000000000001";
    const std::string currentId = "20000000-0000-4000-8000-000000000001";
    const std::string targetId = "20000000-0000-4000-8000-000000000002";
    std::vector<std::string> requestIds{
        "30000000-0000-4000-8000-000000000003",
        "30000000-0000-4000-8000-000000000002",
        "30000000-0000-4000-8000-000000000001"};
    V2DeviceManagementProtocolClient client([&] {
        const auto result = requestIds.back();
        requestIds.pop_back();
        return result;
    });

    checkThrows([&] { client.listDevices(); },
                "unauthenticated commands must be rejected");
    checkThrows([&] { client.bindSession("bad", currentId); },
                "invalid session identifiers must be rejected");
    client.bindSession(sessionId, currentId);

    const auto list = client.listDevices();
    chat::v2::Envelope listEnvelope;
    check(listEnvelope.ParseFromString(list.bytes),
          "list command must be a protobuf envelope");
    check(listEnvelope.protocol_version() == 2
              && listEnvelope.kind() == chat::v2::MESSAGE_KIND_COMMAND
              && listEnvelope.message_type() == chat::v2::MESSAGE_TYPE_LIST_DEVICES
              && listEnvelope.request_id() == list.requestId
              && listEnvelope.session_id() == sessionId,
          "list command must bind protocol, request and session");
    chat::v2::ListDevices listPayload;
    check(listPayload.ParseFromString(listEnvelope.payload()),
          "list command must contain the registered payload");
    check(client.pendingCount() == 1, "list command must be correlated");

    chat::v2::Envelope wrongSession = listEnvelope;
    wrongSession.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    wrongSession.set_message_type(chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY);
    wrongSession.set_session_id("10000000-0000-4000-8000-000000000099");
    wrongSession.set_payload(serialize(directory(currentId, targetId)));
    checkThrows([&] { client.receive(serialize(wrongSession)); },
                "cross-session responses must be rejected");
    check(client.pendingCount() == 1,
          "rejected traffic must not consume the legitimate request");

    auto duplicateDirectory = directory(currentId, targetId);
    duplicateDirectory.mutable_devices(1)->set_device_id(currentId);
    checkThrows([&] {
        client.receive(response(chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY,
                                chat::v2::MESSAGE_KIND_RESPONSE,
                                list.requestId, sessionId, duplicateDirectory));
    }, "duplicate server device identifiers must be rejected");
    check(client.pendingCount() == 1,
          "invalid projections must not consume the legitimate request");

    const auto listed = client.receive(response(
        chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY, chat::v2::MESSAGE_KIND_RESPONSE,
        list.requestId, sessionId, directory(currentId, targetId)));
    check(listed.type == V2DeviceManagementProtocolClient::EventType::Directory
              && listed.requestId == list.requestId && listed.devices.size() == 2
              && listed.devices.at(0).current
              && listed.devices.at(0).platform
                 == V2DeviceManagementProtocolClient::Platform::Windows,
          "valid directory must produce a typed server projection");
    check(client.pendingCount() == 0, "accepted list response must complete");

    checkThrows([&] { client.revokeDevice(currentId); },
                "the current device must never be revocable");
    const auto revoke = client.revokeDevice(targetId);
    chat::v2::Envelope revokeEnvelope;
    check(revokeEnvelope.ParseFromString(revoke.bytes),
          "revoke command must be a protobuf envelope");
    chat::v2::RevokeDevice revokePayload;
    check(revokeEnvelope.message_type() == chat::v2::MESSAGE_TYPE_REVOKE_DEVICE
              && revokePayload.ParseFromString(revokeEnvelope.payload())
              && revokePayload.target_device_id() == targetId,
          "revoke command must carry only the validated target");

    checkThrows([&] {
        client.receive(response(chat::v2::MESSAGE_TYPE_DEVICE_DIRECTORY,
                                chat::v2::MESSAGE_KIND_RESPONSE,
                                revoke.requestId, sessionId,
                                directory(currentId, targetId)));
    }, "response type confusion must be rejected");
    check(client.pendingCount() == 1,
          "type confusion must not consume the pending revoke");

    chat::v2::DeviceRevoked revoked;
    revoked.set_target_device_id(targetId);
    revoked.set_revoked_at_epoch_ms(500);
    revoked.set_revoked_sessions(2);
    revoked.set_changed(true);
    const auto revokedEvent = client.receive(response(
        chat::v2::MESSAGE_TYPE_DEVICE_REVOKED, chat::v2::MESSAGE_KIND_RESPONSE,
        revoke.requestId, sessionId, revoked));
    check(revokedEvent.type == V2DeviceManagementProtocolClient::EventType::Revoked
              && revokedEvent.targetDeviceId == targetId && client.pendingCount() == 0,
          "valid revoke response must complete exactly its request");

    const auto denied = client.listDevices();
    chat::v2::ProtocolError error;
    error.set_code(chat::v2::PROTOCOL_ERROR_CODE_NOT_AUTHORIZED);
    error.set_safe_message("denied");
    const auto errorEvent = client.receive(response(
        chat::v2::MESSAGE_TYPE_PROTOCOL_ERROR, chat::v2::MESSAGE_KIND_ERROR,
        denied.requestId, sessionId, error));
    check(errorEvent.type == V2DeviceManagementProtocolClient::EventType::ProtocolError
              && errorEvent.requestId == denied.requestId && client.pendingCount() == 0,
          "correlated protocol errors must become opaque typed failures");

    client.clearSession();
    check(client.pendingCount() == 0, "disconnect must abandon pending state");
    checkThrows([&] { client.listDevices(); },
                "cleared sessions must require fresh authentication");

    if (failures) return 1;
    std::cout << "[V2DeviceManagementProtocolClientTest] PASS\n";
    return 0;
}
