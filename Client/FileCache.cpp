#include "FileCache.h"

#include <QStandardPaths>
#include <QFile>
#include <QFileInfo>
#include <QDesktopServices>
#include <QUrl>
#include <QSettings>
#include <QDebug>
#include <QJsonDocument>
#include <QJsonObject>

FileCache *FileCache::s_instance = nullptr;

FileCache *FileCache::instance() {
    if (!s_instance)
        s_instance = new FileCache();
    return s_instance;
}

FileCache::FileCache(QObject *parent)
    : QObject(parent)
{
    // 从设置中读取用户自定义的缓存目录
    QSettings settings;
    m_baseDir = settings.value("cache/baseDir").toString();
    if (m_baseDir.isEmpty()) {
        m_baseDir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)
                    + "/cache";
    }

    // 初始使用 baseDir（无用户名隔离），登录后会调用 setUsername
    m_cacheDir = m_baseDir;
    QDir dir(m_cacheDir);
    if (!dir.exists())
        dir.mkpath(".");

    loadIndex();
    qDebug() << "[FileCache] 缓存目录:" << m_cacheDir << "已缓存文件:" << m_cache.size();
}

void FileCache::loadIndex() {
    // 从 cache_index.json 加载索引，只信任索引中记录的文件
    m_cache.clear();
    QString indexPath = m_cacheDir + "/cache_index.json";
    QFile f(indexPath);
    if (!f.open(QIODevice::ReadOnly)) return;

    QJsonDocument doc = QJsonDocument::fromJson(f.readAll());
    f.close();
    QJsonObject obj = doc.object();
    for (auto it = obj.begin(); it != obj.end(); ++it) {
        bool ok;
        int fileId = it.key().toInt(&ok);
        if (!ok) continue;
        QJsonObject entry = it.value().toObject();
        QString fileName = entry["file"].toString();
        qint64 expectedSize = static_cast<qint64>(entry["size"].toDouble());
        QString path = m_cacheDir + "/" + fileName;
        // 验证文件存在且大小匹配
        QFileInfo fi(path);
        if (fi.exists() && fi.size() == expectedSize) {
            m_cache[fileId] = path;
        }
    }
}

void FileCache::saveIndex() {
    QJsonObject obj;
    for (auto it = m_cache.constBegin(); it != m_cache.constEnd(); ++it) {
        QFileInfo fi(it.value());
        QJsonObject entry;
        entry["file"] = fi.fileName();
        entry["size"] = fi.size();
        obj[QString::number(it.key())] = entry;
    }
    QString indexPath = m_cacheDir + "/cache_index.json";
    QFile f(indexPath);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(obj).toJson(QJsonDocument::Compact));
    }
}

void FileCache::setUsername(const QString &username) {
    m_username = username;
    // 在 baseDir 下创建用户专属子目录
    m_cacheDir = m_baseDir + "/" + username;
    QDir dir(m_cacheDir);
    if (!dir.exists())
        dir.mkpath(".");
    loadIndex();
    qDebug() << "[FileCache] 用户缓存目录:" << m_cacheDir;
}

void FileCache::setCacheDir(const QString &baseDir, const QString &username) {
    m_baseDir = baseDir;
    m_username = username;
    m_cacheDir = m_baseDir + "/" + m_username;
    QDir dir(m_cacheDir);
    if (!dir.exists())
        dir.mkpath(".");

    // 保存到设置
    QSettings settings;
    settings.setValue("cache/baseDir", m_baseDir);

    loadIndex();
    qDebug() << "[FileCache] 缓存目录已更改:" << m_cacheDir;
}

QString FileCache::cachedFilePath(int fileId) const {
    QMutexLocker locker(&m_mutex);
    return m_cache.value(fileId);
}

bool FileCache::isCached(int fileId) const {
    QMutexLocker locker(&m_mutex);
    if (!m_cache.contains(fileId))
        return false;
    return QFile::exists(m_cache[fileId]);
}

QString FileCache::cacheFile(int fileId, const QString &fileName, const QByteArray &data) {
    QString safeName = QString("%1_%2").arg(fileId).arg(fileName);
    QString filePath = m_cacheDir + "/" + safeName;

    QFile file(filePath);
    if (file.open(QIODevice::WriteOnly)) {
        file.write(data);
        file.close();

        QMutexLocker locker(&m_mutex);
        m_cache[fileId] = filePath;
        saveIndex();
        qDebug() << "[FileCache] 已缓存:" << filePath;
        return filePath;
    }

    qWarning() << "[FileCache] 缓存失败:" << filePath;
    return {};
}

QString FileCache::cacheDir() const {
    return m_cacheDir;
}

bool FileCache::openWithSystem(const QString &filePath) {
    return QDesktopServices::openUrl(QUrl::fromLocalFile(filePath));
}

void FileCache::removeFile(int fileId) {
    QMutexLocker locker(&m_mutex);
    if (m_cache.contains(fileId)) {
        QString path = m_cache[fileId];
        QFile::remove(path);
        m_cache.remove(fileId);
        saveIndex();
        qDebug() << "[FileCache] 已删除缓存:" << path;
    }
}

qint64 FileCache::totalCacheSize() const {
    qint64 total = 0;
    QDir dir(m_cacheDir);
    if (!dir.exists()) return 0;

    const QFileInfoList entries = dir.entryInfoList(QDir::Files | QDir::NoDotAndDotDot);
    for (const QFileInfo &fi : entries) {
        total += fi.size();
    }
    return total;
}

void FileCache::clearAllCache() {
    QMutexLocker locker(&m_mutex);

    // 删除缓存目录下的所有文件
    QDir dir(m_cacheDir);
    if (dir.exists()) {
        const QStringList files = dir.entryList(QDir::Files | QDir::NoDotAndDotDot);
        for (const QString &f : files) {
            dir.remove(f);
        }
    }

    m_cache.clear();
    saveIndex();
    qInfo() << "[FileCache] 已清除所有缓存";
}
