#include "DatabaseManager.h"

#include <QCoreApplication>
#include <QDebug>
#include <QDir>
#include <QSet>
#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>
#include <QTemporaryDir>

namespace {

struct SchemaState {
    QStringList objects;
    QMap<QString, QSet<QString>> columns;
};

bool fail(const QString &message) {
    qCritical().noquote() << "[DatabaseSchemaTest]" << message;
    return false;
}

bool openProbe(const QString &connectionName,
               const QString &databasePath,
               QSqlDatabase *database) {
    *database = QSqlDatabase::addDatabase(QStringLiteral("QSQLITE"), connectionName);
    database->setDatabaseName(databasePath);
    if (!database->open()) {
        return fail(QStringLiteral("cannot open probe database: %1")
                        .arg(database->lastError().text()));
    }
    return true;
}

QSet<QString> tableColumns(QSqlDatabase &database, const QString &table) {
    QSet<QString> result;
    QSqlQuery query(database);
    if (!query.exec(QStringLiteral("PRAGMA table_info(%1)").arg(table))) {
        fail(QStringLiteral("cannot inspect table %1: %2")
                 .arg(table, query.lastError().text()));
        return result;
    }
    while (query.next()) {
        result.insert(query.value(1).toString());
    }
    return result;
}

bool readSchema(const QString &connectionName,
                const QString &databasePath,
                SchemaState *state) {
    bool ok = true;
    {
        QSqlDatabase database;
        if (!openProbe(connectionName, databasePath, &database)) {
            return false;
        }

        QSqlQuery objects(database);
        if (!objects.exec(QStringLiteral(
                "SELECT type, name, tbl_name, COALESCE(sql, '') "
                "FROM sqlite_master "
                "WHERE name NOT LIKE 'sqlite_%' "
                "ORDER BY type, name"))) {
            ok = fail(QStringLiteral("cannot read sqlite_master: %1")
                          .arg(objects.lastError().text()));
        } else {
            while (objects.next()) {
                state->objects.append(
                    QStringLiteral("%1|%2|%3|%4")
                        .arg(objects.value(0).toString(),
                             objects.value(1).toString(),
                             objects.value(2).toString(),
                             objects.value(3).toString()));
            }
        }

        const QStringList requiredTables = {
            QStringLiteral("users"),
            QStringLiteral("rooms"),
            QStringLiteral("room_members"),
            QStringLiteral("messages"),
            QStringLiteral("files"),
            QStringLiteral("room_admins"),
            QStringLiteral("room_settings"),
            QStringLiteral("user_avatars"),
            QStringLiteral("room_avatars"),
            QStringLiteral("friend_requests"),
            QStringLiteral("friendships"),
            QStringLiteral("friend_messages"),
            QStringLiteral("friend_files"),
        };
        for (const QString &table : requiredTables) {
            state->columns.insert(table, tableColumns(database, table));
            if (state->columns.value(table).isEmpty()) {
                ok = fail(QStringLiteral("required table is missing or empty: %1").arg(table));
            }
        }

        QSqlQuery integrity(database);
        if (!integrity.exec(QStringLiteral("PRAGMA integrity_check")) ||
            !integrity.next() || integrity.value(0).toString() != QStringLiteral("ok")) {
            ok = fail(QStringLiteral("PRAGMA integrity_check failed"));
        }

        database.close();
    }
    QSqlDatabase::removeDatabase(connectionName);
    return ok;
}

bool requireColumns(const SchemaState &state,
                    const QString &table,
                    const QSet<QString> &required) {
    const QSet<QString> missing = required - state.columns.value(table);
    if (!missing.isEmpty()) {
        QStringList names = missing.values();
        names.sort();
        return fail(QStringLiteral("table %1 is missing columns: %2")
                        .arg(table, names.join(QStringLiteral(", "))));
    }
    return true;
}

bool initializeDatabase() {
    DatabaseManager manager;
    if (!manager.initialize()) {
        return fail(QStringLiteral("DatabaseManager::initialize returned false"));
    }
    return true;
}

} // namespace

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QCoreApplication::setApplicationName(QStringLiteral("DatabaseSchemaTest"));

    QTemporaryDir directory;
    if (!directory.isValid()) {
        qCritical() << "[DatabaseSchemaTest] cannot create temporary directory";
        return 1;
    }

    const QString databasePath = directory.filePath(QStringLiteral("schema-test.db"));
    qputenv("CHATROOM_DB_PATH", QDir::toNativeSeparators(databasePath).toUtf8());

    if (!initializeDatabase()) {
        return 1;
    }

    SchemaState firstStart;
    if (!readSchema(QStringLiteral("schema_probe_first"), databasePath, &firstStart)) {
        return 1;
    }

    bool ok = true;
    ok &= requireColumns(firstStart, QStringLiteral("users"),
                         {QStringLiteral("display_name"), QStringLiteral("last_uid_change")});
    ok &= requireColumns(firstStart, QStringLiteral("room_members"),
                         {QStringLiteral("last_read_msg_id")});
    ok &= requireColumns(firstStart, QStringLiteral("friendships"),
                         {QStringLiteral("user1_last_read_msg_id"),
                          QStringLiteral("user2_last_read_msg_id")});
    ok &= requireColumns(firstStart, QStringLiteral("messages"),
                         {QStringLiteral("thumbnail"), QStringLiteral("file_cleared"),
                          QStringLiteral("clear_reason")});
    ok &= requireColumns(firstStart, QStringLiteral("files"),
                         {QStringLiteral("cleared"), QStringLiteral("clear_reason"),
                          QStringLiteral("cleared_at"), QStringLiteral("cos_url")});
    ok &= requireColumns(firstStart, QStringLiteral("friend_messages"),
                         {QStringLiteral("file_cleared"), QStringLiteral("clear_reason")});
    ok &= requireColumns(firstStart, QStringLiteral("friend_files"),
                         {QStringLiteral("cleared"), QStringLiteral("clear_reason"),
                          QStringLiteral("cleared_at"), QStringLiteral("cos_url")});
    if (!ok) {
        return 1;
    }

    if (!initializeDatabase()) {
        return 1;
    }

    SchemaState secondStart;
    if (!readSchema(QStringLiteral("schema_probe_second"), databasePath, &secondStart)) {
        return 1;
    }
    if (firstStart.objects != secondStart.objects || firstStart.columns != secondStart.columns) {
        return fail(QStringLiteral("first-start and second-start schemas differ")) ? 0 : 1;
    }

    qInfo() << "[DatabaseSchemaTest] PASS: clean and restarted schemas are complete and identical";
    return 0;
}

