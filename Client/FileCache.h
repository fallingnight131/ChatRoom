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

    /// 用系统默认应用打开文件
    static bool openWithSystem(const QString &filePath);

private:
    explicit FileCache(QObject *parent = nullptr);
    static FileCache *s_instance;

    mutable QMutex m_mutex;
    QMap<int, QString> m_cache;  // fileId -> local path
    QString m_cacheDir;
};
