#include "chat/v2/envelope.pb.h"

#include <cstdint>
#include <iostream>
#include <string>

namespace {

constexpr char kGoldenHex[] =
    "08021001186422057265712d312a0973657373696f6e2d31"
    "3208636c69656e742d313880d095ffbc314203616263";

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
    return 0;
}
