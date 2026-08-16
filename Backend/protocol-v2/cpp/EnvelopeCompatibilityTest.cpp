#include "chat/v2/envelope.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/contact.pb.h"
#include "chat/v2/authentication.pb.h"
#include "chat/v2/messaging.pb.h"
#include "chat/v2/conversation.pb.h"
#include "chat/v2/attachment.pb.h"
#include "chat/v2/device_management.pb.h"
#include "chat/v2/web_push.pb.h"

#include <cstdint>
#include <iostream>
#include <string>

namespace {

constexpr char kGoldenHex[] =
    "08021001186422057265712d312a0973657373696f6e2d31"
    "3208636c69656e742d313880d095ffbc314203616263";
constexpr char kClientHelloGoldenHex[] =
    "0802100218012205302e312e302a086465766963652d31";
constexpr char kAuthenticateGoldenHex[] =
    "0a05616c696365120d746573742d70617373776f7264";
constexpr char kSubmitMessageGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "10011a026869";
constexpr char kMentionedSubmitMessageGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "10011a0740e69d8e20686922280a2430303030303030302d303030302d303030302d303030302d"
    "3030303030303030303030321804";
constexpr char kSubmitReplyMessageGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
    "180122026869";
constexpr char kSetMessageReactionGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
    "180120012a0a7265616374696f6e2d31";
constexpr char kSetMessagePinGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
    "1801220570696e2d31";
constexpr char kEditMessageGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
    "180320012a0268693206656469742d31";
constexpr char kForwardMessageGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d303030303030303030303032"
    "1803222430303030303030302d303030302d303030302d303030302d303030303030303030303033";
constexpr char kSearchMessagesGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "1206e8818ae5a4a918ac022019";
constexpr char kSetAccountBlockGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "10011a2430303030303030302d303030302d303030302d303030302d303030303030303030303032";
constexpr char kListAccountBlocksGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d3030303030303030303030311019";
constexpr char kListConversationsGoldenHex[] =
    "0880d095ffbc31122430303030303030302d303030302d303030302d303030302d"
    "3030303030303030303030321819";
constexpr char kListParticipantsGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "122430303030303030302d303030302d303030302d303030302d3030303030303030303030321819";
constexpr char kRegisterAttachmentGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031"
    "12086174746163682d311a05612e747874220a746578742f706c61696e28023220"
    "0101010101010101010101010101010101010101010101010101010101010101";
constexpr char kRevokeDeviceGoldenHex[] =
    "0a2430303030303030302d303030302d303030302d303030302d303030303030303030303031";
constexpr char kWebPushHttpCredentialGoldenHex[] =
    "0a2b61616161616161616161616161616161616161616161616161616161616161616161616161616161616161122b626262626262626262626262626262626262626262626262626262626262626262626262626262626262621880a0f1c2b134";

int hexDigit(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    return -1;
}

std::string fromHex(const std::string &hex) {
    std::string bytes;
    if (hex.size() % 2 != 0) return bytes;
    bytes.reserve(hex.size() / 2);
    for (std::size_t index = 0; index < hex.size(); index += 2) {
        const int high = hexDigit(hex[index]);
        const int low = hexDigit(hex[index + 1]);
        if (high < 0 || low < 0) return {};
        bytes.push_back(static_cast<char>((high << 4) | low));
    }
    return bytes;
}

bool matchesGolden(const chat::v2::Envelope &envelope) {
    return envelope.protocol_version() == 2
        && envelope.kind() == chat::v2::MESSAGE_KIND_COMMAND
        && envelope.message_type() == 100
        && envelope.request_id() == "req-1"
        && envelope.session_id() == "session-1"
        && envelope.client_message_id() == "client-1"
        && envelope.sent_at_epoch_ms() == INT64_C(1700000000000)
        && envelope.payload() == "abc";
}

}  // namespace

int main() {
    const std::string golden = fromHex(kGoldenHex);
    chat::v2::Envelope envelope;
    if (!envelope.ParseFromString(golden) || !matchesGolden(envelope)) {
        std::cerr << "generated C++ binding could not parse the V2 golden envelope\n";
        return 1;
    }
    if (envelope.SerializeAsString() != golden) {
        std::cerr << "generated C++ binding changed the deterministic V2 bytes\n";
        return 1;
    }
    const std::string helloGolden = fromHex(kClientHelloGoldenHex);
    chat::v2::ClientHello hello;
    if (!hello.ParseFromString(helloGolden)
            || hello.minimum_protocol_version() != 2
            || hello.maximum_protocol_version() != 2
            || hello.platform() != chat::v2::CLIENT_PLATFORM_WEB
            || hello.app_version() != "0.1.0"
            || hello.client_device_id() != "device-1"
            || hello.SerializeAsString() != helloGolden) {
        std::cerr << "generated C++ binding changed the ClientHello golden payload\n";
        return 1;
    }
    const std::string authenticateGolden = fromHex(kAuthenticateGoldenHex);
    chat::v2::Authenticate authenticate;
    if (!authenticate.ParseFromString(authenticateGolden)
            || authenticate.username() != "alice"
            || authenticate.password_utf8() != "test-password"
            || authenticate.SerializeAsString() != authenticateGolden) {
        std::cerr << "generated C++ binding changed the Authenticate golden payload\n";
        return 1;
    }
    const std::string submitGolden = fromHex(kSubmitMessageGoldenHex);
    chat::v2::SubmitMessage submit;
    if (!submit.ParseFromString(submitGolden)
            || submit.conversation_id() != "00000000-0000-0000-0000-000000000001"
            || submit.content_type() != chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8
            || submit.content() != "hi"
            || submit.SerializeAsString() != submitGolden) {
        std::cerr << "generated C++ binding changed the SubmitMessage golden payload\n";
        return 1;
    }
    const std::string mentionedSubmitGolden = fromHex(kMentionedSubmitMessageGoldenHex);
    chat::v2::SubmitMessage mentionedSubmit;
    if (!mentionedSubmit.ParseFromString(mentionedSubmitGolden)
            || mentionedSubmit.content() != "@\xe6\x9d\x8e hi"
            || mentionedSubmit.mentions_size() != 1
            || mentionedSubmit.mentions(0).target_account_id()
                    != "00000000-0000-0000-0000-000000000002"
            || mentionedSubmit.mentions(0).start_utf8_byte() != 0
            || mentionedSubmit.mentions(0).length_utf8_bytes() != 4
            || mentionedSubmit.SerializeAsString() != mentionedSubmitGolden) {
        std::cerr << "generated C++ binding changed the MessageMention golden payload\n";
        return 1;
    }
    const std::string submitReplyGolden = fromHex(kSubmitReplyMessageGoldenHex);
    chat::v2::SubmitReplyMessage submitReply;
    if (!submitReply.ParseFromString(submitReplyGolden)
            || submitReply.conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || submitReply.target_message_id()
                    != "00000000-0000-0000-0000-000000000002"
            || submitReply.content_type() != chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8
            || submitReply.content() != "hi"
            || submitReply.SerializeAsString() != submitReplyGolden) {
        std::cerr << "generated C++ binding changed the SubmitReplyMessage golden payload\n";
        return 1;
    }
    const std::string reactionGolden = fromHex(kSetMessageReactionGoldenHex);
    chat::v2::SetMessageReaction reaction;
    if (!reaction.ParseFromString(reactionGolden)
            || reaction.conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || reaction.message_id()
                    != "00000000-0000-0000-0000-000000000002"
            || reaction.reaction() != chat::v2::MESSAGE_REACTION_KIND_LIKE
            || !reaction.active()
            || reaction.client_operation_id() != "reaction-1"
            || reaction.SerializeAsString() != reactionGolden) {
        std::cerr << "generated C++ binding changed the SetMessageReaction golden payload\n";
        return 1;
    }
    const std::string pinGolden = fromHex(kSetMessagePinGoldenHex);
    chat::v2::SetMessagePin pin;
    if (!pin.ParseFromString(pinGolden)
            || pin.conversation_id() != "00000000-0000-0000-0000-000000000001"
            || pin.message_id() != "00000000-0000-0000-0000-000000000002"
            || !pin.pinned()
            || pin.client_operation_id() != "pin-1"
            || pin.SerializeAsString() != pinGolden) {
        std::cerr << "generated C++ binding changed the SetMessagePin golden payload\n";
        return 1;
    }
    const std::string listGolden = fromHex(kListConversationsGoldenHex);
    const std::string editGolden = fromHex(kEditMessageGoldenHex);
    chat::v2::EditMessage edit;
    if (!edit.ParseFromString(editGolden)
            || edit.conversation_id() != "00000000-0000-0000-0000-000000000001"
            || edit.message_id() != "00000000-0000-0000-0000-000000000002"
            || edit.expected_revision() != 3
            || edit.content_type() != chat::v2::MESSAGE_CONTENT_TYPE_TEXT_UTF8
            || edit.content() != "hi"
            || edit.client_operation_id() != "edit-1"
            || edit.SerializeAsString() != editGolden) {
        std::cerr << "generated C++ binding changed the EditMessage golden payload\n";
        return 1;
    }
    const std::string forwardGolden = fromHex(kForwardMessageGoldenHex);
    chat::v2::ForwardMessage forward;
    if (!forward.ParseFromString(forwardGolden)
            || forward.source_conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || forward.source_message_id()
                    != "00000000-0000-0000-0000-000000000002"
            || forward.expected_source_content_revision() != 3
            || forward.target_conversation_id()
                    != "00000000-0000-0000-0000-000000000003"
            || forward.SerializeAsString() != forwardGolden) {
        std::cerr << "generated C++ binding changed the ForwardMessage golden payload\n";
        return 1;
    }
    const std::string searchGolden = fromHex(kSearchMessagesGoldenHex);
    chat::v2::SearchConversationMessages search;
    if (!search.ParseFromString(searchGolden)
            || search.conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || search.literal_query() != "\xE8\x81\x8A\xE5\xA4\xA9"
            || search.before_sequence() != UINT64_C(300)
            || search.limit() != 25
            || search.SerializeAsString() != searchGolden) {
        std::cerr << "generated C++ binding changed the search golden payload\n";
        return 1;
    }
    const std::string blockGolden = fromHex(kSetAccountBlockGoldenHex);
    chat::v2::SetAccountBlock block;
    if (!block.ParseFromString(blockGolden)
            || block.target_account_id()
                    != "00000000-0000-0000-0000-000000000001"
            || !block.blocked()
            || block.client_operation_id()
                    != "00000000-0000-0000-0000-000000000002"
            || block.SerializeAsString() != blockGolden) {
        std::cerr << "generated C++ binding changed the account-block golden payload\n";
        return 1;
    }
    const std::string blockListGolden = fromHex(kListAccountBlocksGoldenHex);
    chat::v2::ListAccountBlocks blockList;
    if (!blockList.ParseFromString(blockListGolden)
            || blockList.after_target_account_id()
                    != "00000000-0000-0000-0000-000000000001"
            || blockList.limit() != 25
            || blockList.SerializeAsString() != blockListGolden) {
        std::cerr << "generated C++ binding changed the account-block-list golden payload\n";
        return 1;
    }
    chat::v2::ListConversations list;
    if (!list.ParseFromString(listGolden)
            || list.after_updated_at_epoch_ms() != INT64_C(1700000000000)
            || list.after_conversation_id() != "00000000-0000-0000-0000-000000000002"
            || list.limit() != 25
            || list.SerializeAsString() != listGolden) {
        std::cerr << "generated C++ binding changed the ListConversations golden payload\n";
        return 1;
    }
    const std::string participantGolden = fromHex(kListParticipantsGoldenHex);
    chat::v2::ListConversationParticipants participants;
    if (!participants.ParseFromString(participantGolden)
            || participants.conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || participants.after_account_id()
                    != "00000000-0000-0000-0000-000000000002"
            || participants.limit() != 25
            || participants.SerializeAsString() != participantGolden) {
        std::cerr << "generated C++ binding changed the participant cursor payload\n";
        return 1;
    }
    const std::string attachmentGolden = fromHex(kRegisterAttachmentGoldenHex);
    chat::v2::RegisterAttachment attachment;
    if (!attachment.ParseFromString(attachmentGolden)
            || attachment.conversation_id()
                    != "00000000-0000-0000-0000-000000000001"
            || attachment.client_attachment_id() != "attach-1"
            || attachment.file_name() != "a.txt"
            || attachment.media_type() != "text/plain"
            || attachment.byte_size() != 2
            || attachment.content_sha256().size() != 32
            || attachment.SerializeAsString() != attachmentGolden) {
        std::cerr << "generated C++ binding changed the RegisterAttachment golden payload\n";
        return 1;
    }
    const std::string revokeGolden = fromHex(kRevokeDeviceGoldenHex);
    chat::v2::RevokeDevice revoke;
    if (!revoke.ParseFromString(revokeGolden)
            || revoke.target_device_id() != "00000000-0000-0000-0000-000000000001"
            || revoke.SerializeAsString() != revokeGolden) {
        std::cerr << "generated C++ binding changed the RevokeDevice golden payload\n";
        return 1;
    }
    const std::string credentialGolden = fromHex(kWebPushHttpCredentialGoldenHex);
    chat::v2::WebPushHttpCredentialIssued credential;
    if (!credential.ParseFromString(credentialGolden)
            || credential.bearer_token_ascii() != std::string(43, 'a')
            || credential.csrf_token_ascii() != std::string(43, 'b')
            || credential.expires_at_epoch_ms() != INT64_C(1800000000000)
            || credential.SerializeAsString() != credentialGolden) {
        std::cerr << "generated C++ binding changed the Web Push credential payload\n";
        return 1;
    }
    return 0;
}
