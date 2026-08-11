#pragma once

#include "DatabaseManager.h"

#include <QString>

class RoomMessageService {
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
        int roomId = 0;
        int senderId = 0;
        QString clientMessageId;
        QString content;
        QString contentType;
    };

    struct Result {
        Status status = Status::StorageFailure;
        int messageId = -1;
        qint64 sequence = 0;
        qint64 createdAtMs = 0;
        QString errorCode;
        QString error;
    };

    explicit RoomMessageService(DatabaseManager *database);

    Result submit(const Command &command) const;

private:
    DatabaseManager *m_database;
};
