#include "Message.h"
#include <QJsonDocument>

// ==================== 工厂方法 ====================

Message Message::fromJson(const QJsonObject &json) {
    Message m;
    const QJsonObject data = json["data"].toObject();

    m.m_id          = data["id"].toInt();
    m.m_roomId      = data["roomId"].toInt();
    m.m_sender      = data["sender"].toString();
    m.m_content     = data["content"].toString();
    m.m_contentType = stringToContentType(data["contentType"].toString());
    m.m_recalled    = data["recalled"].toBool(false);
    m.m_fileName    = data["fileName"].toString();
    m.m_fileSize    = static_cast<qint64>(data["fileSize"].toDouble());
    m.m_fileId      = data["fileId"].toInt();

    qint64 ts = static_cast<qint64>(json["timestamp"].toDouble());
    if (ts > 0)
        m.m_timestamp = QDateTime::fromMSecsSinceEpoch(ts);

    // imageData 以 base64 字符串传输
    if (data.contains("imageData")) {
        m.m_imageData = QByteArray::fromBase64(data["imageData"].toString().toUtf8());
    }

    return m;
}

Message Message::createTextMessage(int roomId, const QString &sender, const QString &content) {
    Message m;
    m.m_roomId      = roomId;
    m.m_sender      = sender;
    m.m_content     = content;
    m.m_contentType = Text;
    return m;
}

Message Message::createEmojiMessage(int roomId, const QString &sender, const QString &emoji) {
    Message m;
    m.m_roomId      = roomId;
    m.m_sender      = sender;
    m.m_content     = emoji;
    m.m_contentType = Emoji;
    return m;
}

Message Message::createImageMessage(int roomId, const QString &sender, const QString &imagePath,
                                     const QByteArray &imageData) {
    Message m;
    m.m_roomId      = roomId;
    m.m_sender      = sender;
    m.m_content     = imagePath;
    m.m_contentType = Image;
    m.m_imageData   = imageData;
    return m;
}

Message Message::createFileMessage(int roomId, const QString &sender, const QString &fileName,
                                    qint64 fileSize, int fileId) {
    Message m;
    m.m_roomId      = roomId;
    m.m_sender      = sender;
    m.m_content     = fileName;
    m.m_fileName    = fileName;
    m.m_fileSize    = fileSize;
    m.m_fileId      = fileId;
    m.m_contentType = File;
    return m;
}

Message Message::createSystemMessage(int roomId, const QString &content) {
    Message m;
    m.m_roomId      = roomId;
    m.m_sender      = QStringLiteral("System");
    m.m_content     = content;
    m.m_contentType = System;
    return m;
}

// ==================== 序列化 ====================

QJsonObject Message::toJson() const {
    QJsonObject data;
    data["id"]          = m_id;
    data["roomId"]      = m_roomId;
    data["sender"]      = m_sender;
    data["content"]     = m_content;
    data["contentType"] = contentTypeToString(m_contentType);
    data["recalled"]    = m_recalled;
    data["fileName"]    = m_fileName;
    data["fileSize"]    = static_cast<double>(m_fileSize);
    data["fileId"]      = m_fileId;

    if (!m_imageData.isEmpty())
        data["imageData"] = QString::fromUtf8(m_imageData.toBase64());

    QJsonObject msg;
    msg["type"]      = (m_contentType == System)
                        ? QStringLiteral("SYSTEM_MSG")
                        : QStringLiteral("CHAT_MSG");
    msg["timestamp"] = m_timestamp.toMSecsSinceEpoch();
    msg["data"]      = data;
    return msg;
}

// ==================== 类型转换 ====================

QString Message::contentTypeToString(ContentType t) {
    switch (t) {
    case Text:   return QStringLiteral("text");
    case Emoji:  return QStringLiteral("emoji");
    case Image:  return QStringLiteral("image");
    case File:   return QStringLiteral("file");
    case System: return QStringLiteral("system");
    }
    return QStringLiteral("text");
}

Message::ContentType Message::stringToContentType(const QString &s) {
    if (s == "emoji")  return Emoji;
    if (s == "image")  return Image;
    if (s == "file")   return File;
    if (s == "system") return System;
    return Text;
}
