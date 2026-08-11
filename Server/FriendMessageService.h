#pragma once

#include "DatabaseManager.h"

#include <QString>

class FriendMessageService {
public:
    enum class Status {
        Accepted,
        Duplicate,
        Unauthorized,
        Invalid,
        Conflict,
        StorageFailure
    };

    struct Command {
        int senderId = 0;
        QString friendUsername;
        QString clientMessageId;
        QString content;
        QString contentType;
    };

    struct Result {
        Status status = Status::StorageFailure;
        int friendshipId = -1;
        int messageId = -1;
        qint64 sequence = 0;
        qint64 createdAtMs = 0;
        QString errorCode;
        QString error;
    };

    explicit FriendMessageService(DatabaseManager *database);

    Result submit(const Command &command) const;

private:
    DatabaseManager *m_database;
};
