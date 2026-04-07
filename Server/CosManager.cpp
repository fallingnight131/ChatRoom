#include "CosManager.h"

#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QFile>
#include <QFileInfo>
#include <QUrl>
#include <QUrlQuery>
#include <QCoreApplication>
#include <QDir>
#include <QDateTime>
#include <QMessageAuthenticationCode>
#include <QCryptographicHash>
#include <QXmlStreamReader>
#include <QTimer>
#include <algorithm>

// ---------- .env 读取辅助 ----------

static QString readEnvValue(const QString &key) {
    // 优先环境变量
    QString val = qEnvironmentVariable(key.toUtf8().constData()).trimmed();
    if (!val.isEmpty()) return val;

    // 遍历候选 .env 文件
    const QString appDir = QCoreApplication::applicationDirPath();
    const QStringList candidates = {
        QDir::current().filePath(QStringLiteral(".env")),
        QDir(appDir).filePath(QStringLiteral(".env")),
        QDir(appDir).filePath(QStringLiteral("../.env"))
    };

    for (const QString &path : candidates) {
        QFile f(path);
        if (!f.open(QIODevice::ReadOnly | QIODevice::Text)) continue;
        QTextStream stream(&f);
        while (!stream.atEnd()) {
            QString line = stream.readLine().trimmed();
            if (line.isEmpty() || line.startsWith('#')) continue;
            if (line.startsWith(QStringLiteral("export "))) line = line.mid(7).trimmed();
            const int eq = line.indexOf('=');
            if (eq <= 0) continue;
            if (line.left(eq).trimmed() != key) continue;
            QString v = line.mid(eq + 1).trimmed();
            if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))
                v = v.mid(1, v.size() - 2);
            return v;
        }
    }
    return {};
}

// ---------- CosManager ----------

CosManager::CosManager(QObject *parent)
    : QObject(parent)
    , m_nam(new QNetworkAccessManager(this))
{
}

CosManager::~CosManager() = default;

bool CosManager::loadConfig() {
    m_secretId         = readEnvValue(QStringLiteral("COS_SECRET_ID"));
    m_secretKey        = readEnvValue(QStringLiteral("COS_SECRET_KEY"));
    m_bucket           = readEnvValue(QStringLiteral("COS_BUCKET"));
    m_region           = readEnvValue(QStringLiteral("COS_REGION"));
    m_internalEndpoint = readEnvValue(QStringLiteral("COS_INTERNAL_ENDPOINT"));
    m_externalEndpoint = readEnvValue(QStringLiteral("COS_EXTERNAL_ENDPOINT"));
    m_prefix           = readEnvValue(QStringLiteral("COS_PREFIX"));

    if (m_prefix.isEmpty())
        m_prefix = QStringLiteral("chatroom/");
    if (!m_prefix.endsWith('/'))
        m_prefix += '/';

    m_enabled = !m_secretId.isEmpty() && !m_secretKey.isEmpty()
                && !m_bucket.isEmpty() && !m_region.isEmpty();

    if (m_enabled) {
        // 自动推导默认 Endpoint
        if (m_internalEndpoint.isEmpty())
            m_internalEndpoint = QStringLiteral("cos-internal.%1.myqcloud.com").arg(m_region);
        if (m_externalEndpoint.isEmpty())
            m_externalEndpoint = QStringLiteral("cos.%1.myqcloud.com").arg(m_region);

        qInfo() << "[COS] 已启用 — Bucket:" << m_bucket << " Region:" << m_region;
    } else {
        qInfo() << "[COS] 未配置或配置不完整，使用本地文件存储";
    }

    return m_enabled;
}

QString CosManager::internalHost() const {
    return QStringLiteral("%1.%2").arg(m_bucket, m_internalEndpoint);
}

QString CosManager::externalHost() const {
    return QStringLiteral("%1.%2").arg(m_bucket, m_externalEndpoint);
}

QString CosManager::externalUrl(const QString &objectKey) const {
    return QStringLiteral("https://%1/%2").arg(externalHost(), objectKey);
}

// ==================== COS V5 签名 ====================

QByteArray CosManager::sign(const QByteArray &method,
                             const QString &path,
                             const QMap<QString, QString> &headers,
                             const QMap<QString, QString> &params,
                             int expireSeconds) const
{
    // 参考：https://cloud.tencent.com/document/product/436/7778
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    const QByteArray keyTime = QByteArray::number(now) + ";" + QByteArray::number(now + expireSeconds);

    // SignKey = HMAC-SHA1(SecretKey, KeyTime)
    const QByteArray signKey = QMessageAuthenticationCode::hash(
        keyTime, m_secretKey.toUtf8(), QCryptographicHash::Sha1).toHex();

    // HttpString
    QByteArray httpString = method.toLower() + "\n" + path.toUtf8() + "\n";

    // UrlParamList + HttpParameters
    QStringList paramKeys = params.keys();
    std::sort(paramKeys.begin(), paramKeys.end());
    QByteArray urlParamList, httpParams;
    for (const QString &k : paramKeys) {
        if (!urlParamList.isEmpty()) { urlParamList += ";"; httpParams += "&"; }
        const QByteArray lk = k.toLower().toUtf8();
        urlParamList += lk;
        httpParams += lk + "=" + QUrl::toPercentEncoding(params[k]);
    }
    httpString += httpParams + "\n";

    // HeaderList + HttpHeaders
    QStringList headerKeys = headers.keys();
    std::sort(headerKeys.begin(), headerKeys.end());
    QByteArray headerList, httpHeaders;
    for (const QString &k : headerKeys) {
        if (!headerList.isEmpty()) { headerList += ";"; httpHeaders += "&"; }
        const QByteArray lk = k.toLower().toUtf8();
        headerList += lk;
        httpHeaders += lk + "=" + QUrl::toPercentEncoding(headers[k]);
    }
    httpString += httpHeaders + "\n";

    // StringToSign
    const QByteArray sha1OfHttp = QCryptographicHash::hash(httpString, QCryptographicHash::Sha1).toHex();
    const QByteArray stringToSign = "sha1\n" + keyTime + "\n" + sha1OfHttp + "\n";

    // Signature
    const QByteArray signature = QMessageAuthenticationCode::hash(
        stringToSign, signKey, QCryptographicHash::Sha1).toHex();

    return "q-sign-algorithm=sha1"
           "&q-ak=" + m_secretId.toUtf8() +
           "&q-sign-time=" + keyTime +
           "&q-key-time=" + keyTime +
           "&q-header-list=" + headerList +
           "&q-url-param-list=" + urlParamList +
           "&q-signature=" + signature;
}

// ==================== 上传入口 ====================

void CosManager::uploadFile(const QString &localPath,
                             const QString &objectKey,
                             std::function<void(qint64, qint64)> onProgress,
                             std::function<void(bool, const QString &)> onFinished)
{
    QFileInfo fi(localPath);
    if (!fi.exists()) {
        if (onFinished) onFinished(false, QStringLiteral("本地文件不存在: %1").arg(localPath));
        return;
    }

    const qint64 fileSize = fi.size();
    if (fileSize <= MULTIPART_THRESHOLD) {
        putObject(objectKey, localPath, onProgress, onFinished);
    } else {
        initiateMultipartUpload(objectKey, localPath, onProgress, onFinished);
    }
}

// ==================== 单次 PUT 上传(小文件) ====================

void CosManager::putObject(const QString &objectKey,
                            const QString &localPath,
                            std::function<void(qint64, qint64)> onProgress,
                            std::function<void(bool, const QString &)> onFinished)
{
    QFile *file = new QFile(localPath, this);
    if (!file->open(QIODevice::ReadOnly)) {
        delete file;
        if (onFinished) onFinished(false, QStringLiteral("无法打开文件"));
        return;
    }

    const qint64 fileSize = file->size();
    const QString path = "/" + objectKey;
    const QString host = internalHost();

    QMap<QString, QString> signHeaders;
    signHeaders["host"] = host;

    const QByteArray auth = sign("PUT", path, signHeaders, {});

    QUrl url;
    url.setScheme(QStringLiteral("http")); // 内网用 http
    url.setHost(host);
    url.setPath(path);

    QNetworkRequest req(url);
    req.setRawHeader("Host", host.toUtf8());
    req.setRawHeader("Authorization", auth);
    req.setRawHeader("Content-Length", QByteArray::number(fileSize));

    QNetworkReply *reply = m_nam->put(req, file);
    file->setParent(reply); // reply destroyed → file destroyed

    if (onProgress) {
        connect(reply, &QNetworkReply::uploadProgress, this,
                [onProgress](qint64 sent, qint64 total) {
                    onProgress(sent, total);
                });
    }

    connect(reply, &QNetworkReply::finished, this,
            [reply, objectKey, onFinished, this]() {
                reply->deleteLater();
                if (reply->error() == QNetworkReply::NoError) {
                    if (onFinished) onFinished(true, externalUrl(objectKey));
                } else {
                    const QString err = QStringLiteral("COS PUT 失败: %1 %2")
                        .arg(reply->error()).arg(QString::fromUtf8(reply->readAll().left(500)));
                    qWarning() << "[COS]" << err;
                    if (onFinished) onFinished(false, err);
                }
            });
}

// ==================== 分片上传 ====================

void CosManager::initiateMultipartUpload(const QString &objectKey,
                                           const QString &localPath,
                                           std::function<void(qint64, qint64)> onProgress,
                                           std::function<void(bool, const QString &)> onFinished)
{
    const QString path = "/" + objectKey;
    const QString host = internalHost();

    QMap<QString, QString> signHeaders;
    signHeaders["host"] = host;
    QMap<QString, QString> signParams;
    signParams["uploads"] = "";

    const QByteArray auth = sign("POST", path, signHeaders, signParams);

    QUrl url;
    url.setScheme(QStringLiteral("http"));
    url.setHost(host);
    url.setPath(path);
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("uploads"), QString());
    url.setQuery(q);

    QNetworkRequest req(url);
    req.setRawHeader("Host", host.toUtf8());
    req.setRawHeader("Authorization", auth);

    QNetworkReply *reply = m_nam->post(req, QByteArray());

    connect(reply, &QNetworkReply::finished, this,
            [this, reply, objectKey, localPath, onProgress, onFinished]() {
                reply->deleteLater();
                if (reply->error() != QNetworkReply::NoError) {
                    const QString err = QStringLiteral("COS InitMultipart 失败: %1")
                        .arg(QString::fromUtf8(reply->readAll().left(500)));
                    qWarning() << "[COS]" << err;
                    if (onFinished) onFinished(false, err);
                    return;
                }

                // 解析 UploadId
                QXmlStreamReader xml(reply->readAll());
                QString uploadId;
                while (!xml.atEnd()) {
                    xml.readNext();
                    if (xml.isStartElement() && xml.name() == QStringLiteral("UploadId")) {
                        uploadId = xml.readElementText();
                        break;
                    }
                }

                if (uploadId.isEmpty()) {
                    if (onFinished) onFinished(false, QStringLiteral("COS 未返回 UploadId"));
                    return;
                }

                qInfo() << "[COS] Multipart initiated, uploadId:" << uploadId;

                QFileInfo fi(localPath);
                uploadParts(objectKey, uploadId, localPath, fi.size(), onProgress, onFinished);
            });
}

void CosManager::uploadParts(const QString &objectKey,
                              const QString &uploadId,
                              const QString &localPath,
                              qint64 fileSize,
                              std::function<void(qint64, qint64)> onProgress,
                              std::function<void(bool, const QString &)> onFinished)
{
    // 使用共享状态对象跟踪进度
    struct UploadCtx {
        QString objectKey;
        QString uploadId;
        QString localPath;
        qint64 fileSize;
        int totalParts;
        int currentPart;       // 下一个要上传的分片号 (1-based)
        qint64 uploadedBytes;  // 已完成分片的总字节
        QList<QPair<int, QString>> eTags;
        bool aborted = false;
        std::function<void(qint64, qint64)> onProgress;
        std::function<void(bool, const QString &)> onFinished;
    };

    auto *ctx = new UploadCtx;
    ctx->objectKey = objectKey;
    ctx->uploadId = uploadId;
    ctx->localPath = localPath;
    ctx->fileSize = fileSize;
    ctx->totalParts = static_cast<int>((fileSize + PART_SIZE - 1) / PART_SIZE);
    ctx->currentPart = 1;
    ctx->uploadedBytes = 0;
    ctx->onProgress = onProgress;
    ctx->onFinished = onFinished;

    // 递归 lambda 上传每一片
    auto uploadNext = std::make_shared<std::function<void()>>();
    *uploadNext = [this, ctx, uploadNext]() {
        if (ctx->aborted) {
            delete ctx;
            return;
        }

        if (ctx->currentPart > ctx->totalParts) {
            // 所有分片完成，调用 Complete
            completeMultipartUpload(ctx->objectKey, ctx->uploadId, ctx->eTags, ctx->onFinished);
            delete ctx;
            return;
        }

        const int partNumber = ctx->currentPart;
        const qint64 offset = static_cast<qint64>(partNumber - 1) * PART_SIZE;
        const qint64 partLen = qMin(PART_SIZE, ctx->fileSize - offset);

        QFile *file = new QFile(ctx->localPath);
        if (!file->open(QIODevice::ReadOnly)) {
            delete file;
            abortMultipartUpload(ctx->objectKey, ctx->uploadId);
            if (ctx->onFinished) ctx->onFinished(false, QStringLiteral("无法读取文件分片"));
            ctx->aborted = true;
            delete ctx;
            return;
        }
        file->seek(offset);
        QByteArray partData = file->read(partLen);
        file->close();
        delete file;

        const QString path = "/" + ctx->objectKey;
        const QString host = internalHost();

        QMap<QString, QString> signHeaders;
        signHeaders["host"] = host;
        QMap<QString, QString> signParams;
        signParams["partNumber"] = QString::number(partNumber);
        signParams["uploadId"] = ctx->uploadId;

        const QByteArray auth = sign("PUT", path, signHeaders, signParams);

        QUrl url;
        url.setScheme(QStringLiteral("http"));
        url.setHost(host);
        url.setPath(path);
        QUrlQuery q;
        q.addQueryItem(QStringLiteral("partNumber"), QString::number(partNumber));
        q.addQueryItem(QStringLiteral("uploadId"), ctx->uploadId);
        url.setQuery(q);

        QNetworkRequest req(url);
        req.setRawHeader("Host", host.toUtf8());
        req.setRawHeader("Authorization", auth);
        req.setRawHeader("Content-Length", QByteArray::number(partData.size()));

        QNetworkReply *reply = m_nam->put(req, partData);

        // 分片级别进度
        const qint64 prevUploaded = ctx->uploadedBytes;
        if (ctx->onProgress) {
            connect(reply, &QNetworkReply::uploadProgress, this,
                    [ctx, prevUploaded](qint64 sent, qint64 /*total*/) {
                        ctx->onProgress(prevUploaded + sent, ctx->fileSize);
                    });
        }

        connect(reply, &QNetworkReply::finished, this,
                [this, reply, ctx, partNumber, partLen, uploadNext]() {
                    reply->deleteLater();
                    if (reply->error() != QNetworkReply::NoError) {
                        const QString err = QStringLiteral("COS UploadPart %1 失败: %2")
                            .arg(partNumber).arg(QString::fromUtf8(reply->readAll().left(500)));
                        qWarning() << "[COS]" << err;
                        abortMultipartUpload(ctx->objectKey, ctx->uploadId);
                        if (ctx->onFinished) ctx->onFinished(false, err);
                        ctx->aborted = true;
                        delete ctx;
                        return;
                    }

                    const QString etag = QString::fromUtf8(reply->rawHeader("ETag"));
                    ctx->eTags.append(qMakePair(partNumber, etag));
                    ctx->uploadedBytes += partLen;
                    ctx->currentPart++;

                    // 上传下一片（使用 QTimer::singleShot 避免递归栈溢出）
                    QTimer::singleShot(0, this, [uploadNext]() { (*uploadNext)(); });
                });
    };

    // 开始第一片
    (*uploadNext)();
}

void CosManager::completeMultipartUpload(const QString &objectKey,
                                          const QString &uploadId,
                                          const QList<QPair<int, QString>> &partETags,
                                          std::function<void(bool, const QString &)> onFinished)
{
    // 按 partNumber 排序后组装 XML
    auto sorted = partETags;
    std::sort(sorted.begin(), sorted.end(),
              [](const QPair<int, QString> &a, const QPair<int, QString> &b) {
                  return a.first < b.first;
              });

    QByteArray body = "<CompleteMultipartUpload>";
    for (const auto &p : sorted) {
        body += "<Part><PartNumber>" + QByteArray::number(p.first) + "</PartNumber>"
                "<ETag>" + p.second.toUtf8() + "</ETag></Part>";
    }
    body += "</CompleteMultipartUpload>";

    const QString path = "/" + objectKey;
    const QString host = internalHost();

    QMap<QString, QString> signHeaders;
    signHeaders["host"] = host;
    QMap<QString, QString> signParams;
    signParams["uploadId"] = uploadId;

    const QByteArray auth = sign("POST", path, signHeaders, signParams);

    QUrl url;
    url.setScheme(QStringLiteral("http"));
    url.setHost(host);
    url.setPath(path);
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("uploadId"), uploadId);
    url.setQuery(q);

    QNetworkRequest req(url);
    req.setRawHeader("Host", host.toUtf8());
    req.setRawHeader("Authorization", auth);
    req.setHeader(QNetworkRequest::ContentTypeHeader, "application/xml");

    QNetworkReply *reply = m_nam->post(req, body);

    connect(reply, &QNetworkReply::finished, this,
            [this, reply, objectKey, onFinished]() {
                reply->deleteLater();
                if (reply->error() != QNetworkReply::NoError) {
                    const QString err = QStringLiteral("COS CompleteMultipart 失败: %1")
                        .arg(QString::fromUtf8(reply->readAll().left(500)));
                    qWarning() << "[COS]" << err;
                    if (onFinished) onFinished(false, err);
                    return;
                }
                qInfo() << "[COS] Multipart upload completed:" << objectKey;
                if (onFinished) onFinished(true, externalUrl(objectKey));
            });
}

void CosManager::abortMultipartUpload(const QString &objectKey, const QString &uploadId) {
    const QString path = "/" + objectKey;
    const QString host = internalHost();

    QMap<QString, QString> signHeaders;
    signHeaders["host"] = host;
    QMap<QString, QString> signParams;
    signParams["uploadId"] = uploadId;

    const QByteArray auth = sign("DELETE", path, signHeaders, signParams);

    QUrl url;
    url.setScheme(QStringLiteral("http"));
    url.setHost(host);
    url.setPath(path);
    QUrlQuery q;
    q.addQueryItem(QStringLiteral("uploadId"), uploadId);
    url.setQuery(q);

    QNetworkRequest req(url);
    req.setRawHeader("Host", host.toUtf8());
    req.setRawHeader("Authorization", auth);

    QNetworkReply *reply = m_nam->deleteResource(req);
    connect(reply, &QNetworkReply::finished, reply, &QNetworkReply::deleteLater);

    qInfo() << "[COS] Aborted multipart upload:" << objectKey << uploadId;
}
