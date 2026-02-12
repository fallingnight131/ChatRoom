#pragma once

#include <QObject>
#include <QString>
#include <QDir>
#include <QMap>
#include <QMutex>

/// 文件缓存管理器 —— 管理接收文件的本地缓存
class FileCache : public QObject {
    Q_OBJECT
public:
    static FileCache *instance();

    /// 获取文件的本地缓存路径（如果已缓存）
    QString cachedFilePath(int fileId) const;

    /// 检查文件是否已缓存
    bool isCached(int fileId) const;

    /// 将文件数据保存到本地缓存，返回本地路径
    QString cacheFile(int fileId, const QString &fileName, const QByteArray &data);

    /// 从本地文件复制到缓存（用于发送者直接缓存，避免大文件全部读入内存）
    QString cacheFromLocal(int fileId, const QString &fileName, const QString &sourcePath);

    /// 获取缓存目录
    QString cacheDir() const;

    /// 设置缓存目录（用户名隔离子目录）
    void setCacheDir(const QString &baseDir, const QString &username);

    /// 初始化时设置用户名（创建用户子目录）
    void setUsername(const QString &username);

    /// 用系统默认应用打开文件
    static bool openWithSystem(const QString &filePath);

    /// 删除指定文件的本地缓存
    void removeFile(int fileId);

    /// 获取缓存目录总大小（字节）
    qint64 totalCacheSize() const;

    /// 清除所有缓存文件
    void clearAllCache();

    /// 获取所有已缓存的文件ID列表
    QMap<int, QString> allCachedFileIds() const;

private:
    explicit FileCache(QObject *parent = nullptr);
    static FileCache *s_instance;
    void loadIndex();
    void saveIndex();

    mutable QMutex m_mutex;
    QMap<int, QString> m_cache;  // fileId -> local path
    QString m_cacheDir;
    QString m_baseDir;     // 用户选择的基础缓存目录
    QString m_username;    // 当前用户名（用于隔离子目录）
};
