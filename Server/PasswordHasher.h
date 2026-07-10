#pragma once

#include <QString>

class PasswordHasher {
public:
    enum class Verification {
        Failed,
        Valid,
        ValidNeedsRehash,
    };

    static QString createHash(const QString &password);
    static Verification verify(const QString &password,
                               const QString &storedHash,
                               const QString &legacySalt);
    static bool isModernHash(const QString &storedHash);

private:
    static bool ensureInitialized();
    static bool verifyLegacySha256(const QString &password,
                                   const QString &storedHash,
                                   const QString &legacySalt);
};
