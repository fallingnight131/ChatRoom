#pragma once

#include <QStyledItemDelegate>
#include <QPixmap>

/// 消息气泡委托绘制 —— 自定义 QStyledItemDelegate
/// 支持：文本气泡、系统消息、文件消息、图片消息、已撤回消息
class MessageDelegate : public QStyledItemDelegate {
    Q_OBJECT
public:
    explicit MessageDelegate(QObject *parent = nullptr);

    void paint(QPainter *painter, const QStyleOptionViewItem &option,
               const QModelIndex &index) const override;
    QSize sizeHint(const QStyleOptionViewItem &option,
                   const QModelIndex &index) const override;

public slots:
    void updateThemeColors(bool isDark);

private:
    void drawTextBubble(QPainter *painter, const QStyleOptionViewItem &option,
                        const QModelIndex &index, bool isMine) const;
    void drawSystemMessage(QPainter *painter, const QStyleOptionViewItem &option,
                           const QModelIndex &index) const;
    void drawFileBubble(QPainter *painter, const QStyleOptionViewItem &option,
                        const QModelIndex &index, bool isMine) const;
    void drawImageBubble(QPainter *painter, const QStyleOptionViewItem &option,
                         const QModelIndex &index, bool isMine) const;
    void drawRecalledMessage(QPainter *painter, const QStyleOptionViewItem &option,
                              const QModelIndex &index) const;

    QSize textBubbleSize(const QStyleOptionViewItem &option,
                         const QModelIndex &index) const;

    /// 判断文件名是否为图片
    static bool isImageFile(const QString &fileName);
    /// 判断文件名是否为视频
    static bool isVideoFile(const QString &fileName);
    /// 加载缓存图片（带 QPixmapCache）
    QPixmap loadCachedImage(int fileId, const QString &fileName) const;

    // 颜色常量
    QColor m_myBubbleColor;
    QColor m_otherBubbleColor;
    QColor m_systemColor;
    QColor m_myTextColor;
    QColor m_otherTextColor;
    QColor m_senderColor;
    QColor m_timeColor;
    QColor m_fileBgColor;

    int m_bubbleRadius   = 12;
    int m_avatarSize     = 36;
    int m_maxBubbleWidth = 400;
    int m_maxImageWidth  = 240;
    int m_maxImageHeight = 240;
    int m_padding        = 10;
    int m_margin         = 6;
};
