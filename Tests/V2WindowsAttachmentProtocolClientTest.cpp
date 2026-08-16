#include "V2WindowsAttachmentProtocolClient.h"

#include "chat/v2/attachment.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/envelope.pb.h"

#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void check(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

template <typename Payload>
std::string response(const std::string &requestId, const std::string &sessionId,
                     chat::v2::MessageType type, const Payload &payload) {
    chat::v2::Envelope envelope;
    envelope.set_protocol_version(2);
    envelope.set_kind(chat::v2::MESSAGE_KIND_RESPONSE);
    envelope.set_message_type(type);
    envelope.set_request_id(requestId);
    envelope.set_session_id(sessionId);
    envelope.set_sent_at_epoch_ms(2000);
    envelope.set_payload(payload.SerializeAsString());
    return envelope.SerializeAsString();
}

bool rejects(const std::function<void()> &operation) {
    try {
        operation();
        return false;
    } catch (const std::exception &) {
        return true;
    }
}
}

int main() {
    const std::string session = "50000000-0000-4000-8000-000000000001";
    const std::string conversation = "20000000-0000-4000-8000-000000000001";
    const std::string attachment = "60000000-0000-4000-8000-000000000001";
    std::vector<std::string> requestIds{
        "70000000-0000-4000-8000-000000000001",
        "70000000-0000-4000-8000-000000000002",
        "70000000-0000-4000-8000-000000000003",
        "70000000-0000-4000-8000-000000000004",
        "70000000-0000-4000-8000-000000000005",
        "70000000-0000-4000-8000-000000000006"};
    std::size_t requestIndex = 0;
    V2WindowsAttachmentProtocolClient client(
        [&] { return requestIds.at(requestIndex++); }, [] { return 1000; });
    check(rejects([&] {
        client.registerAttachment(conversation, "client-file-1", "photo.png",
            "image/png", 3, std::string(32, 'a'));
    }), "unbound attachment protocol must reject commands");
    client.bindSession(session);
    check(rejects([&] {
        client.registerAttachment(conversation, "client-file-1", "../photo.png",
            "image/png", 3, std::string(32, 'a'));
    }), "attachment basename traversal must be rejected");
    check(rejects([&] {
        client.registerAttachment(conversation, "client-file-1", "photo.png",
            "Image/PNG", 3, std::string(32, 'a'));
    }), "non-canonical media type must be rejected");

    const auto registration = client.registerAttachment(
        conversation, "client-file-1", "照片.png", "image/png", 3,
        std::string(32, 'a'));
    chat::v2::Envelope registrationEnvelope;
    chat::v2::RegisterAttachment registrationPayload;
    check(registrationEnvelope.ParseFromString(registration.bytes)
              && registrationEnvelope.message_type()
                    == chat::v2::MESSAGE_TYPE_REGISTER_ATTACHMENT
              && registrationPayload.ParseFromString(registrationEnvelope.payload())
              && registrationPayload.content_sha256().size() == 32,
          "registration command lost bounded metadata");

    chat::v2::AttachmentRegistered registered;
    registered.set_attachment_id(attachment);
    registered.set_conversation_id(conversation);
    registered.set_client_attachment_id("client-file-1");
    chat::v2::AttachmentRegistered wrongRegistration = registered;
    wrongRegistration.set_conversation_id(
        "20000000-0000-4000-8000-000000000099");
    check(rejects([&] {
        client.receive(response(registration.requestId, session,
            chat::v2::MESSAGE_TYPE_ATTACHMENT_REGISTERED, wrongRegistration));
    }) && client.pendingCount() == 1,
          "registration identity mismatch must fail without consuming correlation");
    const auto registeredEvent = client.receive(response(
        registration.requestId, session, chat::v2::MESSAGE_TYPE_ATTACHMENT_REGISTERED,
        registered));
    check(registeredEvent.type
                == V2WindowsAttachmentProtocolClient::EventType::Registered
              && registeredEvent.attachmentId == attachment
              && client.pendingCount() == 0 && client.trackedAttachmentCount() == 1,
          "registration response did not retain stable lifecycle identity");

    const auto authorization = client.authorizeUpload(attachment);
    chat::v2::AttachmentUploadAuthorized authorized;
    authorized.set_attachment_id(attachment);
    authorized.set_upload_uri("https://objects.example.test/upload?signature=secret");
    authorized.set_expires_at_epoch_ms(5000);
    auto *checksum = authorized.add_required_headers();
    checksum->set_name("x-amz-checksum-sha256");
    checksum->set_value("digest");
    chat::v2::AttachmentUploadAuthorized forbiddenHeader = authorized;
    auto *host = forbiddenHeader.add_required_headers();
    host->set_name("host");
    host->set_value("objects.example.test");
    check(rejects([&] {
        client.receive(response(authorization.requestId, session,
            chat::v2::MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED, forbiddenHeader));
    }) && client.pendingCount() == 1,
          "stack-managed upload headers must fail without consuming correlation");
    const auto grant = client.receive(response(
        authorization.requestId, session,
        chat::v2::MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED, authorized));
    check(grant.type
                == V2WindowsAttachmentProtocolClient::EventType::UploadAuthorized
              && grant.uploadUri == authorized.upload_uri()
              && grant.requiredHeaders.size() == 1
              && client.trackedAttachmentCount() == 1,
          "upload grant did not remain transient and correlated");

    client.abandon(client.authorizeUpload(attachment).requestId);
    chat::v2::AttachmentUploadAuthorized invalidGrant = authorized;
    invalidGrant.set_upload_uri("https://:443/upload");
    const auto invalidAuthorization = client.authorizeUpload(attachment);
    check(rejects([&] {
        client.receive(response(invalidAuthorization.requestId, session,
            chat::v2::MESSAGE_TYPE_ATTACHMENT_UPLOAD_AUTHORIZED, invalidGrant));
    }), "upload grant without an HTTPS host must be rejected");
    client.abandon(invalidAuthorization.requestId);

    const auto completion = client.completeUpload(attachment);
    chat::v2::AttachmentReady ready;
    ready.set_attachment_id(attachment);
    ready.set_conversation_id(conversation);
    ready.set_ready_at_epoch_ms(6000);
    const auto readyEvent = client.receive(response(
        completion.requestId, session, chat::v2::MESSAGE_TYPE_ATTACHMENT_READY, ready));
    check(readyEvent.type == V2WindowsAttachmentProtocolClient::EventType::Ready
              && readyEvent.readyAtEpochMs == 6000
              && client.pendingCount() == 0 && client.trackedAttachmentCount() == 0,
          "ready response did not close the transient attachment lifecycle");

    const auto abandoned = client.registerAttachment(
        conversation, "client-file-2", "file.bin", "application/octet-stream", 1,
        std::string(32, 'b'));
    client.abandon(abandoned.requestId);
    check(client.pendingCount() == 0, "abandon must remove request correlation");
    client.clearSession();
    check(client.trackedAttachmentCount() == 0
              && rejects([&] { client.authorizeUpload(attachment); }),
          "disconnect must erase attachment and grant correlation state");

    std::cout << "[V2WindowsAttachmentProtocolClientTest] PASS\n";
    return 0;
}
