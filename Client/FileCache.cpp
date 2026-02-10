#include "FileCache.h"

#include <QStandardPaths>
#include <QFile>
#include <QFileInfo>
#include <QDesktopServices>
#include <QUrl>
#include <QSettings>
#include <QDebug>

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

    scanCacheDir();
    qDebug() << "[FileCache] 缓存目录:" << m_cacheDir << "已缓存文件:" << m_cache.size();
}

void FileCache::scanCacheDir() {
    QMutexLocker locker(&m_mutex);
    m_cache.clear();
    QDir cacheDir(m_cacheDir);
    for (const QFileInfo &fi : cacheDir.entryInfoList(QDir::Files)) {
        QString name = fi.fileName();
        int sep = name.indexOf('_');
        if (sep > 0) {
            bool ok;
            int fileId = name.left(sep).toInt(&ok);
            if (ok) {
                m_cache[fileId] = fi.absoluteFilePath();
            }
        }
    }
}

void FileCache::setUsername(const QString &username) {
    m_username = username;
    // 在 baseDir 下创建用户专属子目录
    m_cacheDir = m_baseDir + "/" + username;
    QDir dir(m_cacheDir);
    if (!dir.exists())
        dir.mkpath(".");
    scanCacheDir();
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

    scanCacheDir();
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
