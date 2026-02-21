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
#include <QDateTime>
#include <QDirIterator>

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

// ==================== 文件类型分类 ====================

QString FileCache::fileTypeSubDir(const QString &fileName) {
    static const QStringList imgExts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
    static const QStringList vidExts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    QString suffix = QFileInfo(fileName).suffix().toLower();
    if (imgExts.contains(suffix)) return QStringLiteral("Image");
    if (vidExts.contains(suffix)) return QStringLiteral("Video");
    return QStringLiteral("File");
}

QString FileCache::subDirForFile(const QString &fileName) const {
    // 返回如 "Image/2025-06", "Video/2025-06", "File/2025-06"
    QString typeDir = fileTypeSubDir(fileName);
    QString yearMonth = QDate::currentDate().toString("yyyy-MM");
    return typeDir + "/" + yearMonth;
}

// ==================== 索引管理 ====================

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
        QString relPath = entry["file"].toString();
        qint64 expectedSize = static_cast<qint64>(entry["size"].toDouble());
        QString path = m_cacheDir + "/" + relPath;
        // 验证文件存在且大小匹配
        QFileInfo fi(path);
        if (fi.exists() && fi.size() == expectedSize) {
            m_cache[fileId] = path;
        }
    }
}

void FileCache::saveIndex() {
    QJsonObject obj;
    QDir base(m_cacheDir);
    for (auto it = m_cache.constBegin(); it != m_cache.constEnd(); ++it) {
        QFileInfo fi(it.value());
        QJsonObject entry;
        // 存储相对于 cacheDir 的路径（支持多级目录）
        entry["file"] = base.relativeFilePath(it.value());
        entry["size"] = fi.size();
        obj[QString::number(it.key())] = entry;
    }
    QString indexPath = m_cacheDir + "/cache_index.json";
    QFile f(indexPath);
    if (f.open(QIODevice::WriteOnly)) {
        f.write(QJsonDocument(obj).toJson(QJsonDocument::Compact));
    }
}

// ==================== 用户/目录设置 ====================

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

// ==================== 缓存操作 ====================

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
    // 计算子目录: Image/2025-06, Video/2025-06, File/2025-06
    QString sub = subDirForFile(fileName);
    QString targetDir = m_cacheDir + "/" + sub;

    // 确保目录存在
    QDir dir(targetDir);
    if (!dir.exists())
        dir.mkpath(".");

    // 文件名去重：如果已存在同名文件，追加（1）、（2）...
    QString baseName = QFileInfo(fileName).completeBaseName();
    QString suffix = QFileInfo(fileName).suffix();
    QString filePath = targetDir + "/" + fileName;
    int counter = 1;
    while (QFile::exists(filePath)) {
        QString newName = suffix.isEmpty()
            ? QString("%1（%2）").arg(baseName).arg(counter)
            : QString("%1（%2）.%3").arg(baseName).arg(counter).arg(suffix);
        filePath = targetDir + "/" + newName;
        counter++;
    }

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

QString FileCache::thumbDir() const {
    QString dir = m_cacheDir + "/Thumb";
    QDir d(dir);
    if (!d.exists()) d.mkpath(".");
    return dir;
}

QString FileCache::cacheFromLocal(int fileId, const QString &fileName, const QString &sourcePath) {
    // 计算子目录
    QString sub = subDirForFile(fileName);
    QString targetDir = m_cacheDir + "/" + sub;

    // 确保目录存在
    QDir dir(targetDir);
    if (!dir.exists())
        dir.mkpath(".");

    // 文件名去重：如果已存在同名文件，追加（1）、（2）...
    QString baseName = QFileInfo(fileName).completeBaseName();
    QString suffix = QFileInfo(fileName).suffix();
    QString destPath = targetDir + "/" + fileName;
    int counter = 1;
    while (QFile::exists(destPath)) {
        // 如果源文件和目标是同一个文件，直接使用
        if (QFileInfo(sourcePath).absoluteFilePath() == QFileInfo(destPath).absoluteFilePath()) {
            QMutexLocker locker(&m_mutex);
            m_cache[fileId] = destPath;
            saveIndex();
            return destPath;
        }
        QString newName = suffix.isEmpty()
            ? QString("%1（%2）").arg(baseName).arg(counter)
            : QString("%1（%2）.%3").arg(baseName).arg(counter).arg(suffix);
        destPath = targetDir + "/" + newName;
        counter++;
    }

    // 如果源文件和目标相同，直接记录
    if (QFileInfo(sourcePath).absoluteFilePath() == QFileInfo(destPath).absoluteFilePath()) {
        QMutexLocker locker(&m_mutex);
        m_cache[fileId] = destPath;
        saveIndex();
        return destPath;
    }

    if (QFile::copy(sourcePath, destPath)) {
        QMutexLocker locker(&m_mutex);
        m_cache[fileId] = destPath;
        saveIndex();
        qDebug() << "[FileCache] 已从本地复制到缓存:" << destPath;
        return destPath;
    }

    qWarning() << "[FileCache] 本地复制到缓存失败:" << sourcePath << "->" << destPath;
    return {};
}

bool FileCache::openWithSystem(const QString &filePath) {
    return QDesktopServices::openUrl(QUrl::fromLocalFile(filePath));
}

void FileCache::removeFile(int fileId) {
    QMutexLocker locker(&m_mutex);
    if (m_cache.contains(fileId)) {
        QString path = m_cache[fileId];
        QFile file(path);
        file.setPermissions(QFile::ReadUser | QFile::WriteUser);
        if (!file.remove()) {
            qWarning() << "[FileCache] 无法删除缓存文件:" << path << file.errorString();
        }
        m_cache.remove(fileId);
        saveIndex();
        qDebug() << "[FileCache] 已删除缓存:" << path;
    }
}

qint64 FileCache::totalCacheSize() const {
    qint64 total = 0;
    QDir dir(m_cacheDir);
    if (!dir.exists()) return 0;

    // 递归遍历所有子目录
    QDirIterator it(m_cacheDir, QDir::Files | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
    while (it.hasNext()) {
        it.next();
        total += it.fileInfo().size();
    }
    return total;
}

void FileCache::clearAllCache() {
    QMutexLocker locker(&m_mutex);

    // 递归删除缓存目录下的所有文件（保留 cache_index.json 和目录结构）
    QDir dir(m_cacheDir);
    if (dir.exists()) {
        QDirIterator it(m_cacheDir, QDir::Files | QDir::NoDotAndDotDot, QDirIterator::Subdirectories);
        while (it.hasNext()) {
            it.next();
            if (it.fileName() == "cache_index.json") continue;
            QString fullPath = it.filePath();
            QFile file(fullPath);
            file.setPermissions(QFile::ReadUser | QFile::WriteUser);
            if (!file.remove()) {
                qWarning() << "[FileCache] 无法删除文件:" << fullPath << file.errorString();
            }
        }
        // 清理空的子目录（从深层往浅层删除）
        QStringList subDirs = {"Image", "Video", "File", "Thumb"};
        for (const QString &sub : subDirs) {
            QDir subDir(m_cacheDir + "/" + sub);
            if (subDir.exists()) {
                // 删除年月子目录
                const QStringList entries = subDir.entryList(QDir::Dirs | QDir::NoDotAndDotDot);
                for (const QString &e : entries) {
                    QDir(subDir.filePath(e)).rmdir(".");
                }
            }
        }
    }

    m_cache.clear();
    saveIndex();
    qInfo() << "[FileCache] 已清除所有缓存";
}

QMap<int, QString> FileCache::allCachedFileIds() const {
    QMutexLocker locker(&m_mutex);
    return m_cache;
}
