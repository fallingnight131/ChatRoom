#pragma once

#include <QString>
#include <QDateTime>
#include <QJsonObject>
#include <QJsonArray>

/// 聊天消息数据模型 —— 对应一条显示在 UI 上的消息
class Message {
public:
    enum ContentType {
        Text,
        Emoji,
        Image,
        File,
        System
    };

    enum DownloadState {
        NotDownloaded = 0,
        Downloading   = 1,
        Downloaded    = 2
    };

    Message() = default;

    // --- 工厂方法 ---
    static Message fromJson(const QJsonObject &json);
    static Message createTextMessage(int roomId, const QString &sender, const QString &content);
    static Message createEmojiMessage(int roomId, const QString &sender, const QString &emoji);
    static Message createImageMessage(int roomId, const QString &sender, const QString &imagePath,
                                       const QByteArray &imageData);
    static Message createFileMessage(int roomId, const QString &sender, const QString &fileName,
                                      qint64 fileSize, int fileId = 0);
    static Message createSystemMessage(int roomId, const QString &content);

    QJsonObject toJson() const;

    // --- Getters ---
    int id() const { return m_id; }
    int roomId() const { return m_roomId; }
    QString sender() const { return m_sender; }
    QString content() const { return m_content; }
    ContentType contentType() const { return m_contentType; }
    QDateTime timestamp() const { return m_timestamp; }
    bool recalled() const { return m_recalled; }
    QString fileName() const { return m_fileName; }
    qint64 fileSize() const { return m_fileSize; }
    int fileId() const { return m_fileId; }
    QByteArray imageData() const { return m_imageData; }
    bool isMine() const { return m_isMine; }
    DownloadState downloadState() const { return m_downloadState; }
    double downloadProgress() const { return m_downloadProgress; }

    // --- Setters ---
    void setId(int id) { m_id = id; }
    void setRecalled(bool v) { m_recalled = v; }
    void setIsMine(bool v) { m_isMine = v; }
    void setImageData(const QByteArray &d) { m_imageData = d; }
    void setContentType(ContentType t) { m_contentType = t; }
    void setSender(const QString &s) { m_sender = s; }
    void setDownloadState(DownloadState s) { m_downloadState = s; }
    void setDownloadProgress(double p) { m_downloadProgress = p; }

    static QString contentTypeToString(ContentType t);
    static ContentType stringToContentType(const QString &s);

private:
    int         m_id          = 0;
    int         m_roomId      = 0;
    QString     m_sender;
    QString     m_content;
    ContentType m_contentType = Text;
    QDateTime   m_timestamp   = QDateTime::currentDateTime();
    bool        m_recalled    = false;
    QString     m_fileName;
    qint64      m_fileSize    = 0;
    int         m_fileId      = 0;
    QByteArray  m_imageData;
    bool        m_isMine      = false;
    DownloadState m_downloadState = NotDownloaded;
    double      m_downloadProgress = 0.0;
};
