#include "DatabaseManager.h"
#include "PasswordHasher.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QDebug>
#include <QDir>
#include <QSqlDatabase>
#include <QSqlError>
#include <QSqlQuery>
#include <QTemporaryDir>

#include <sodium.h>

namespace {

struct StoredPassword {
    int id = 0;
    QString hash;
    QString salt;
};

bool fail(const QString &message) {
    qCritical().noquote() << "[PasswordMigrationTest]" << message;
    return false;
}

StoredPassword readPassword(QSqlDatabase &database, const QString &username) {
    QSqlQuery query(database);
    query.prepare(QStringLiteral(
        "SELECT id, password_hash, salt FROM users WHERE username = ?"));
    query.addBindValue(username);
    if (!query.exec() || !query.next()) {
        fail(QStringLiteral("cannot read password row for %1: %2")
                 .arg(username, query.lastError().text()));
        return {};
    }
    return {query.value(0).toInt(), query.value(1).toString(), query.value(2).toString()};
}

QString readRoomPassword(QSqlDatabase &database, int roomId) {
    QSqlQuery query(database);
    query.prepare(QStringLiteral("SELECT password FROM rooms WHERE id = ?"));
    query.addBindValue(roomId);
    if (!query.exec() || !query.next()) {
        fail(QStringLiteral("cannot read room password row: %1")
                 .arg(query.lastError().text()));
        return {};
    }
    return query.value(0).toString();
}

int insertPassword(QSqlDatabase &database,
                   const QString &username,
                   const QString &hash,
                   const QString &salt) {
    QSqlQuery query(database);
    query.prepare(QStringLiteral(
        "INSERT INTO users (username, display_name, password_hash, salt) "
        "VALUES (?, ?, ?, ?)"));
    query.addBindValue(username);
    query.addBindValue(username);
    query.addBindValue(hash);
    query.addBindValue(salt);
    if (!query.exec()) {
        fail(QStringLiteral("cannot insert fixture user %1: %2")
                 .arg(username, query.lastError().text()));
        return 0;
    }
    return query.lastInsertId().toInt();
}

QString legacySha256(const QString &password, const QString &salt) {
    QByteArray material = (password + salt).toUtf8();
    const QString result = QString::fromLatin1(
        QCryptographicHash::hash(material, QCryptographicHash::Sha256).toHex());
    sodium_memzero(material.data(), static_cast<size_t>(material.size()));
    return result;
}

QString weakModernHash(const QString &password) {
    QByteArray passwordBytes = password.toUtf8();
    char output[crypto_pwhash_STRBYTES];
    if (crypto_pwhash_str(output,
                          passwordBytes.constData(),
                          static_cast<unsigned long long>(passwordBytes.size()),
                          crypto_pwhash_OPSLIMIT_MIN,
                          crypto_pwhash_MEMLIMIT_MIN) != 0) {
        sodium_memzero(passwordBytes.data(), static_cast<size_t>(passwordBytes.size()));
        return {};
    }
    sodium_memzero(passwordBytes.data(), static_cast<size_t>(passwordBytes.size()));
    const QString result = QString::fromLatin1(output);
    sodium_memzero(output, sizeof output);
    return result;
}

} // namespace

int main(int argc, char *argv[]) {
    QCoreApplication app(argc, argv);
    QCoreApplication::setApplicationName(QStringLiteral("PasswordMigrationTest"));
    if (sodium_init() < 0) {
        return fail(QStringLiteral("libsodium initialization failed")) ? 0 : 1;
    }

    QTemporaryDir directory;
    if (!directory.isValid()) {
        return fail(QStringLiteral("cannot create temporary directory")) ? 0 : 1;
    }
    const QString databasePath = directory.filePath(QStringLiteral("password-test.db"));
    qputenv("CHATROOM_DB_PATH", QDir::toNativeSeparators(databasePath).toUtf8());

    DatabaseManager manager;
    if (!manager.initialize()) {
        return fail(QStringLiteral("database initialization failed")) ? 0 : 1;
    }

    QSqlDatabase probe = QSqlDatabase::addDatabase(
        QStringLiteral("QSQLITE"), QStringLiteral("password_migration_probe"));
    probe.setDatabaseName(databasePath);
    if (!probe.open()) {
        return fail(QStringLiteral("cannot open probe database: %1")
                        .arg(probe.lastError().text())) ? 0 : 1;
    }

    bool ok = true;

    const QString modernUser = QStringLiteral("modern_user");
    const QString modernPassword = QStringLiteral("modern-password-密码");
    const int modernId = manager.registerUser(
        modernUser, QStringLiteral("Modern User"), modernPassword);
    const StoredPassword modern = readPassword(probe, modernUser);
    ok &= modernId > 0 && modern.id == modernId;
    ok &= PasswordHasher::isModernHash(modern.hash) && modern.salt.isEmpty();
    ok &= manager.authenticateUser(modernUser, QStringLiteral("wrong-password")) == -1;
    ok &= manager.authenticateUser(modernUser, modernPassword) == modernId;

    const QString legacyUser = QStringLiteral("legacy_user");
    const QString legacyPassword = QStringLiteral("legacy-password");
    const QString legacySalt = QStringLiteral("legacy-salt-1234");
    const QString legacyHash = legacySha256(legacyPassword, legacySalt);
    const int legacyId = insertPassword(probe, legacyUser, legacyHash, legacySalt);
    ok &= manager.authenticateUser(legacyUser, QStringLiteral("wrong-password")) == -1;
    ok &= readPassword(probe, legacyUser).hash == legacyHash;
    ok &= manager.authenticateUser(legacyUser, legacyPassword) == legacyId;
    const StoredPassword upgradedLegacy = readPassword(probe, legacyUser);
    ok &= PasswordHasher::isModernHash(upgradedLegacy.hash);
    ok &= upgradedLegacy.hash != legacyHash && upgradedLegacy.salt.isEmpty();
    ok &= manager.authenticateUser(legacyUser, legacyPassword) == legacyId;
    DatabaseManager restartedManager;
    ok &= restartedManager.initialize();
    ok &= restartedManager.authenticateUser(legacyUser, legacyPassword) == legacyId;

    const QString changedPassword = QStringLiteral("changed-password-新");
    ok &= !manager.changePassword(modernId, QStringLiteral("wrong-password"), changedPassword);
    ok &= manager.changePassword(modernId, modernPassword, changedPassword);
    ok &= manager.authenticateUser(modernUser, modernPassword) == -1;
    ok &= manager.authenticateUser(modernUser, changedPassword) == modernId;

    const QString rehashUser = QStringLiteral("rehash_user");
    const QString rehashPassword = QStringLiteral("rehash-password");
    const QString oldModernHash = weakModernHash(rehashPassword);
    const int rehashId = insertPassword(
        probe, rehashUser, oldModernHash, QStringLiteral(""));
    ok &= !oldModernHash.isEmpty() && PasswordHasher::isModernHash(oldModernHash);
    ok &= manager.authenticateUser(rehashUser, rehashPassword) == rehashId;
    const StoredPassword rehashed = readPassword(probe, rehashUser);
    ok &= rehashed.hash != oldModernHash && PasswordHasher::isModernHash(rehashed.hash);

    const QString roomPassword = QStringLiteral("room-password-房间");
    const int protectedRoomId = manager.createRoom(
        QStringLiteral("Protected Room"), modernId, roomPassword);
    const QString protectedHash = readRoomPassword(probe, protectedRoomId);
    ok &= protectedRoomId > 0 && protectedHash != roomPassword;
    ok &= PasswordHasher::isModernHash(protectedHash);
    ok &= !manager.verifyRoomPassword(protectedRoomId, QStringLiteral("wrong-room-password"));
    ok &= manager.verifyRoomPassword(protectedRoomId, roomPassword);

    QSqlQuery legacyRoom(probe);
    legacyRoom.prepare(QStringLiteral(
        "INSERT INTO rooms (name, creator_id, password) VALUES (?, ?, ?)"));
    legacyRoom.addBindValue(QStringLiteral("Legacy Plaintext Room"));
    legacyRoom.addBindValue(modernId);
    legacyRoom.addBindValue(QStringLiteral("legacy-room-password"));
    ok &= legacyRoom.exec();
    const int legacyRoomId = legacyRoom.lastInsertId().toInt();
    ok &= !manager.verifyRoomPassword(legacyRoomId, QStringLiteral("wrong-room-password"));
    ok &= readRoomPassword(probe, legacyRoomId) == QStringLiteral("legacy-room-password");
    ok &= manager.verifyRoomPassword(legacyRoomId, QStringLiteral("legacy-room-password"));
    const QString upgradedRoomHash = readRoomPassword(probe, legacyRoomId);
    ok &= upgradedRoomHash != QStringLiteral("legacy-room-password");
    ok &= PasswordHasher::isModernHash(upgradedRoomHash);
    ok &= restartedManager.verifyRoomPassword(
        legacyRoomId, QStringLiteral("legacy-room-password"));
    ok &= manager.setRoomPassword(legacyRoomId, QString());
    ok &= !manager.roomHasPassword(legacyRoomId);

    probe.close();
    if (!ok) {
        return fail(QStringLiteral(
            "account or room password hashing/migration behavior failed")) ? 0 : 1;
    }

    qInfo() << "[PasswordMigrationTest] PASS: Argon2id registration, verification,"
               " legacy upgrade, password change, parameter rehash, and room-secret migration";
    return 0;
}
