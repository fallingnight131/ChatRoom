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

    /// 获取缓存目录
    QString cacheDir() const;

    /// 设置缓存目录（用户名隔离子目录）
    void setCacheDir(const QString &baseDir, const QString &username);

    /// 初始化时设置用户名（创建用户子目录）
    void setUsername(const QString &username);

    /// 用系统默认应用打开文件
    static bool openWithSystem(const QString &filePath);

private:
    explicit FileCache(QObject *parent = nullptr);
    static FileCache *s_instance;
    void scanCacheDir();

    mutable QMutex m_mutex;
    QMap<int, QString> m_cache;  // fileId -> local path
    QString m_cacheDir;
    QString m_baseDir;     // 用户选择的基础缓存目录
    QString m_username;    // 当前用户名（用于隔离子目录）
};
