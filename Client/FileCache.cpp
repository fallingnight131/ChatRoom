#include "FileCache.h"

#include <QStandardPaths>
#include <QFile>
#include <QFileInfo>
#include <QDesktopServices>
#include <QUrl>
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
    // 缓存目录: AppData/Local/QtChatRoom/cache/
    m_cacheDir = QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation)
                 + "/cache";
    QDir dir(m_cacheDir);
    if (!dir.exists())
        dir.mkpath(".");

    // 扫描已有缓存
    QDir cacheDir(m_cacheDir);
    for (const QFileInfo &fi : cacheDir.entryInfoList(QDir::Files)) {
        // 文件名格式: fileId_originalName
        QString name = fi.fileName();
        int sep = name.indexOf('_');
        if (sep > 0) {
            bool ok;
            int fileId = name.left(sep).toInt(&ok);
            if (ok) {
                QMutexLocker locker(&m_mutex);
                m_cache[fileId] = fi.absoluteFilePath();
            }
        }
    }
    qDebug() << "[FileCache] 缓存目录:" << m_cacheDir << "已缓存文件:" << m_cache.size();
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
