#pragma once

#include <QObject>
#include <QString>
#include <QByteArray>
#include <functional>

class QNetworkAccessManager;
class QNetworkReply;
class QFile;
class QTimer;

/// 腾讯云 COS 对象存储管理器
/// 使用内网 Endpoint 上传，外网 Endpoint 生成访问 URL
/// 支持分片上传（Multipart Upload），不阻塞主线程
class CosManager : public QObject {
    Q_OBJECT
public:
    explicit CosManager(QObject *parent = nullptr);
    ~CosManager() override;

    /// 从 .env 文件加载配置，返回是否启用 COS
    bool loadConfig();

    /// COS 是否已启用（配置完整）
    bool isEnabled() const { return m_enabled; }

    /// 异步上传文件到 COS
    /// @param localPath  本地文件路径
    /// @param objectKey  COS 上的对象键名 (e.g. "room/1/Image/2026-04/xxx.jpg")
    /// @param onProgress 进度回调 (已上传字节, 总字节)
    /// @param onFinished 完成回调 (成功, 外网URL或错误信息)
    void uploadFile(const QString &localPath,
                    const QString &objectKey,
                    std::function<void(qint64 sent, qint64 total)> onProgress,
                    std::function<void(bool ok, const QString &urlOrError)> onFinished);

    /// 生成外网访问 URL
    QString externalUrl(const QString &objectKey) const;

    /// 生成带签名的预签名下载 URL（私有 bucket 必须使用）
    /// @param cosUrl  数据库中存储的未签名 URL
    /// @param expireSeconds  签名有效期（默认 3600 秒）
    QString presignedUrl(const QString &cosUrl, int expireSeconds = 3600) const;

    /// 异步删除 COS 上的对象（fire-and-forget）
    /// @param cosUrl  数据库中存储的未签名 URL（与 setCosUrl 保存的值相同）
    void deleteCosFile(const QString &cosUrl);

private:
    // COS API 签名（V5 + HMAC-SHA1）
    QByteArray sign(const QByteArray &method,
                    const QString &path,
                    const QMap<QString, QString> &headers,
                    const QMap<QString, QString> &params,
                    int expireSeconds = 600) const;

    // 分片上传的三个阶段
    void initiateMultipartUpload(const QString &objectKey,
                                  const QString &localPath,
                                  std::function<void(qint64, qint64)> onProgress,
                                  std::function<void(bool, const QString &)> onFinished);

    void uploadParts(const QString &objectKey,
                     const QString &uploadId,
                     const QString &localPath,
                     qint64 fileSize,
                     std::function<void(qint64, qint64)> onProgress,
                     std::function<void(bool, const QString &)> onFinished);

    void completeMultipartUpload(const QString &objectKey,
                                  const QString &uploadId,
                                  const QList<QPair<int, QString>> &partETags,
                                  std::function<void(bool, const QString &)> onFinished);

    void abortMultipartUpload(const QString &objectKey, const QString &uploadId);

    // 单次 PUT 上传（小文件 < 分片阈值）
    void putObject(const QString &objectKey,
                   const QString &localPath,
                   std::function<void(qint64, qint64)> onProgress,
                   std::function<void(bool, const QString &)> onFinished);

    /// 从数据库存储的未签名 URL 中提取 objectKey
    QString objectKeyFromUrl(const QString &cosUrl) const;

    QString internalHost() const;
    QString externalHost() const;

    bool m_enabled = false;
    QString m_secretId;
    QString m_secretKey;
    QString m_bucket;
    QString m_region;
    QString m_internalEndpoint;  // 内网 Endpoint (e.g. cos-internal.ap-guangzhou.myqcloud.com)
    QString m_externalEndpoint;  // 外网 Endpoint (e.g. cos.ap-guangzhou.myqcloud.com)
    QString m_prefix;            // 对象键前缀 (e.g. "chatroom/")

    QNetworkAccessManager *m_nam = nullptr;

    static constexpr qint64 MULTIPART_THRESHOLD = 20 * 1024 * 1024;  // 20MB 以上用分片
    static constexpr qint64 PART_SIZE = 8 * 1024 * 1024;             // 每片 8MB
};
