#pragma once

#include "DatabaseManager.h"

#include <QList>
#include <QString>

class AdministrativeDeletionService {
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
        int operatorUserId = 0;
        QString operatorName;
        QString clientOperationId;
        QString mode;
        QList<int> messageIds;
        QList<int> sourceFileIds;
        qint64 cutoffMs = 0;
    };

    struct Result {
        Status status = Status::StorageFailure;
        int roomId = 0;
        int deletedCount = 0;
        qint64 sequence = 0;
        qint64 cutoffMs = 0;
        qint64 createdAtMs = 0;
        QString mode;
        QString clientOperationId;
        QJsonArray messageIds;
        QJsonArray deletedFileIds;
        QString errorCode;
        QString error;
    };

    explicit AdministrativeDeletionService(DatabaseManager *database);

    Result execute(const Command &command) const;

private:
    DatabaseManager *m_database;
};
