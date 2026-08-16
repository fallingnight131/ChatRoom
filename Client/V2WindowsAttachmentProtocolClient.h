#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <unordered_map>
#include <vector>

class V2WindowsAttachmentProtocolClient final {
public:
    struct UploadHeader { std::string name; std::string value; };
    struct Command { std::string requestId; std::string bytes; };
    enum class EventType { Registered, UploadAuthorized, Ready, ProtocolError };
    struct Event {
        EventType type = EventType::ProtocolError;
        std::string requestId;
        std::string attachmentId;
        std::string conversationId;
        std::string clientAttachmentId;
        bool duplicate = false;
        std::string uploadUri;
        std::vector<UploadHeader> requiredHeaders;
        std::int64_t expiresAtEpochMs = 0;
        std::int64_t readyAtEpochMs = 0;
        bool retryable = false;
    };
    using RequestIdFactory = std::function<std::string()>;
    using Clock = std::function<std::int64_t()>;

    explicit V2WindowsAttachmentProtocolClient(
        RequestIdFactory factory = {}, Clock clock = {});
    void bindSession(const std::string &sessionId);
    void clearSession();
    Command registerAttachment(
        const std::string &conversationId, const std::string &clientAttachmentId,
        const std::string &fileName, const std::string &mediaType,
        std::uint64_t byteSize, const std::string &contentSha256);
    Command authorizeUpload(const std::string &attachmentId);
    Command completeUpload(const std::string &attachmentId);
    Event receive(const std::string &bytes);
    void abandon(const std::string &requestId);
    std::size_t pendingCount() const { return m_pending.size(); }
    std::size_t trackedAttachmentCount() const { return m_attachments.size(); }

private:
    enum class PendingType { Register, Authorize, Complete };
    struct Pending {
        PendingType type;
        std::string conversationId;
        std::string clientAttachmentId;
        std::string attachmentId;
    };
    Command command(int messageType, const std::string &payload, Pending pending);
    static bool canonicalUuid(const std::string &value);
    static bool validUtf8Text(const std::string &value, std::size_t maximumBytes);
    static bool validMediaType(const std::string &value);
    static bool validUploadUri(const std::string &value);
    static std::string randomUuid();

    RequestIdFactory m_factory;
    Clock m_clock;
    std::string m_sessionId;
    std::unordered_map<std::string, Pending> m_pending;
    std::unordered_map<std::string, std::string> m_attachments;
};
