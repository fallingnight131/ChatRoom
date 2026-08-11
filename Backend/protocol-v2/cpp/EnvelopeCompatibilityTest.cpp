#include "chat/v2/envelope.pb.h"
#include "chat/v2/control.pb.h"
#include "chat/v2/authentication.pb.h"
#include "chat/v2/messaging.pb.h"

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
    "10641a026869";

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
            || submit.content_type() != 100
            || submit.content() != "hi"
            || submit.SerializeAsString() != submitGolden) {
        std::cerr << "generated C++ binding changed the SubmitMessage golden payload\n";
        return 1;
    }
    return 0;
}
