#pragma once

#include <QByteArray>
#include <QString>

namespace InputValidator {

constexpr int MAX_USERNAME_CHARS = 20;
constexpr int MAX_DISPLAY_NAME_CHARS = 64;
constexpr int MAX_PASSWORD_CHARS = 1024;
constexpr int MAX_MESSAGE_BYTES = 64 * 1024;
constexpr int MAX_FILE_NAME_BYTES = 255;
constexpr int MAX_HISTORY_COUNT = 100;

bool validatePassword(const QString &password, QString *error, bool requireMinimum = true);
bool validateMessage(const QString &content, const QString &contentType, QString *error);
bool validateFileName(const QString &fileName, QString *safeName, QString *error);
bool decodeInlineFile(const QString &encoded, qint64 declaredSize, qint64 maximumSize,
                      QByteArray *decoded, QString *error);
bool decodeUploadChunk(const QString &encoded, qint64 remainingBytes,
                       QByteArray *decoded, QString *error);
int boundedHistoryCount(int requested);

} // namespace InputValidator
