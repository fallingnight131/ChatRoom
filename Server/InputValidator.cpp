#include "InputValidator.h"

#include "Protocol.h"

#include <QFileInfo>
#include <QSet>

namespace InputValidator {

bool validatePassword(const QString &password, QString *error, bool requireMinimum) {
    if (requireMinimum && password.size() < 4) {
        if (error) *error = QStringLiteral("密码至少4个字符");
        return false;
    }
    if (password.size() > MAX_PASSWORD_CHARS) {
        if (error) *error = QStringLiteral("密码过长");
        return false;
    }
    return true;
}

bool validateMessage(const QString &content, const QString &contentType, QString *error) {
    static const QSet<QString> allowedTypes = {
        QStringLiteral("text"), QStringLiteral("emoji"), QStringLiteral("image"),
        QStringLiteral("file"), QStringLiteral("video")
    };
    if (content.isEmpty()) {
        if (error) *error = QStringLiteral("消息内容不能为空");
        return false;
    }
    if (content.toUtf8().size() > MAX_MESSAGE_BYTES) {
        if (error) *error = QStringLiteral("消息内容过长");
        return false;
    }
    if (!allowedTypes.contains(contentType)) {
        if (error) *error = QStringLiteral("不支持的消息类型");
        return false;
    }
    return true;
}

bool validateFileName(const QString &fileName, QString *safeName, QString *error) {
    const QString baseName = QFileInfo(fileName).fileName();
    if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\')
        || baseName != fileName || baseName == QLatin1String(".")
        || baseName == QLatin1String("..") || baseName.toUtf8().size() > MAX_FILE_NAME_BYTES) {
        if (error) *error = QStringLiteral("文件名无效或过长");
        return false;
    }
    if (safeName) *safeName = baseName;
    return true;
}

bool decodeInlineFile(const QString &encoded, qint64 declaredSize, qint64 maximumSize,
                      QByteArray *decoded, QString *error) {
    if (declaredSize <= 0 || declaredSize > maximumSize) {
        if (error) *error = QStringLiteral("文件大小无效");
        return false;
    }
    const auto result = QByteArray::fromBase64Encoding(
        encoded.toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    if (!result || result.decoded.size() != declaredSize) {
        if (error) *error = QStringLiteral("文件数据与声明大小不一致");
        return false;
    }
    if (decoded) *decoded = result.decoded;
    return true;
}

bool decodeUploadChunk(const QString &encoded, qint64 remainingBytes,
                       QByteArray *decoded, QString *error) {
    const auto result = QByteArray::fromBase64Encoding(
        encoded.toLatin1(), QByteArray::AbortOnBase64DecodingErrors);
    if (!result || result.decoded.isEmpty()
        || result.decoded.size() > Protocol::FILE_CHUNK_SIZE
        || result.decoded.size() > remainingBytes) {
        if (error) *error = QStringLiteral("上传分片无效或超过声明大小");
        return false;
    }
    if (decoded) *decoded = result.decoded;
    return true;
}

int boundedHistoryCount(int requested) {
    if (requested <= 0) return 50;
    return qMin(requested, MAX_HISTORY_COUNT);
}

} // namespace InputValidator
