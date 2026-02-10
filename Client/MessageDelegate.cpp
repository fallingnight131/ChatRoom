#include "MessageDelegate.h"
#include "MessageModel.h"
#include "Message.h"
#include "FileCache.h"

#include <QPainter>
#include <QPainterPath>
#include <QApplication>
#include <QDateTime>
#include <QTextDocument>
#include <QPixmapCache>
#include <QFileInfo>

MessageDelegate::MessageDelegate(QObject *parent)
    : QStyledItemDelegate(parent)
    , m_myBubbleColor(QColor(149, 236, 105))     // 微信绿
    , m_otherBubbleColor(QColor(255, 255, 255))   // 白色
    , m_systemColor(QColor(200, 200, 200))
    , m_myTextColor(Qt::black)
    , m_otherTextColor(Qt::black)
    , m_senderColor(QColor(100, 100, 100))
    , m_timeColor(QColor(150, 150, 150))
    , m_fileBgColor(QColor(230, 240, 250))
{
}

/// 智能时间格式化：今天只显示时间，昨天显示"昨天 HH:mm"，其他显示完整日期
static QString formatSmartTime(const QDateTime &dt) {
    QDate today = QDate::currentDate();
    QDate msgDate = dt.date();
    if (msgDate == today)
        return dt.toString("HH:mm");
    else if (msgDate == today.addDays(-1))
        return QStringLiteral("昨天 ") + dt.toString("HH:mm");
    else if (msgDate.year() == today.year())
        return dt.toString("M月d日 HH:mm");
    else
        return dt.toString("yyyy/M/d HH:mm");
}

void MessageDelegate::paint(QPainter *painter, const QStyleOptionViewItem &option,
                             const QModelIndex &index) const {
    painter->save();
    painter->setRenderHint(QPainter::Antialiasing);

    // 绘制选中/悬停背景
    if (option.state & QStyle::State_Selected) {
        painter->fillRect(option.rect, QColor(220, 235, 255, 80));
    } else if (option.state & QStyle::State_MouseOver) {
        painter->fillRect(option.rect, QColor(240, 245, 250, 60));
    }

    bool recalled = index.data(MessageModel::RecalledRole).toBool();
    int contentType = index.data(MessageModel::ContentTypeRole).toInt();
    bool isMine = index.data(MessageModel::IsMineRole).toBool();

    if (recalled) {
        drawRecalledMessage(painter, option, index);
    } else if (contentType == static_cast<int>(Message::System)) {
        drawSystemMessage(painter, option, index);
    } else if (contentType == static_cast<int>(Message::File)) {
        drawFileBubble(painter, option, index, isMine);
    } else {
        drawTextBubble(painter, option, index, isMine);
    }

    painter->restore();
}

QSize MessageDelegate::sizeHint(const QStyleOptionViewItem &option,
                                 const QModelIndex &index) const {
    int contentType = index.data(MessageModel::ContentTypeRole).toInt();
    bool recalled   = index.data(MessageModel::RecalledRole).toBool();

    if (recalled || contentType == static_cast<int>(Message::System)) {
        return QSize(option.rect.width(), 36);
    }

    return textBubbleSize(option, index);
}

// ==================== 文本气泡 ====================

void MessageDelegate::drawTextBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                      const QModelIndex &index, bool isMine) const {
    QString sender  = index.data(MessageModel::SenderRole).toString();
    QString content = index.data(MessageModel::ContentRole).toString();
    QDateTime time  = index.data(MessageModel::TimestampRole).toDateTime();
    int contentType = index.data(MessageModel::ContentTypeRole).toInt();

    QRect rect = option.rect;
    int bubbleMaxW = qMin(m_maxBubbleWidth, rect.width() - m_avatarSize - m_margin * 4);

    // 计算文本尺寸
    QFont contentFont = option.font;
    if (contentType == static_cast<int>(Message::Emoji)) {
        contentFont.setPointSize(contentFont.pointSize() + 8); // 表情放大
    }
    QFontMetrics fm(contentFont);
    QRect textRect = fm.boundingRect(QRect(0, 0, bubbleMaxW - m_padding * 2, 9999),
                                     Qt::TextWordWrap, content);

    QFont senderFont = option.font;
    senderFont.setPointSize(senderFont.pointSize() - 1);
    QFontMetrics sfm(senderFont);

    QString timeStr = formatSmartTime(time);
    QFont timeFont = option.font;
    timeFont.setPointSize(timeFont.pointSize() - 2);
    QFontMetrics tfm(timeFont);

    int senderH = isMine ? 0 : sfm.height() + 2;
    int bubbleW = qMax(textRect.width() + m_padding * 2,
                       tfm.horizontalAdvance(timeStr) + m_padding * 2);
    bubbleW = qMax(bubbleW, sfm.horizontalAdvance(sender) + m_padding * 2);
    int bubbleH = senderH + textRect.height() + tfm.height() + m_padding * 2 + 4;

    // 头像位置
    int avatarX, bubbleX;
    if (isMine) {
        avatarX = rect.right() - m_margin - m_avatarSize;
        bubbleX = avatarX - m_margin - bubbleW;
    } else {
        avatarX = rect.left() + m_margin;
        bubbleX = avatarX + m_avatarSize + m_margin;
    }
    int avatarY = rect.top() + m_margin;
    int bubbleY = rect.top() + m_margin;

    // 绘制头像（文字头像）
    QRect avatarRect(avatarX, avatarY, m_avatarSize, m_avatarSize);
    painter->setPen(Qt::NoPen);
    quint32 hash = qHash(sender);
    QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
    painter->setBrush(avatarColor);
    painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
    painter->setPen(Qt::white);
    painter->setFont(option.font);
    painter->drawText(avatarRect, Qt::AlignCenter,
                      sender.isEmpty() ? "?" : sender.left(1).toUpper());

    // 绘制气泡
    QRect bubbleRect(bubbleX, bubbleY, bubbleW, bubbleH);
    painter->setPen(Qt::NoPen);
    painter->setBrush(isMine ? m_myBubbleColor : m_otherBubbleColor);
    painter->drawRoundedRect(bubbleRect, m_bubbleRadius, m_bubbleRadius);

    // 绘制小三角
    QPainterPath triangle;
    if (isMine) {
        triangle.moveTo(bubbleRect.right(), bubbleRect.top() + 14);
        triangle.lineTo(bubbleRect.right() + 8, bubbleRect.top() + 18);
        triangle.lineTo(bubbleRect.right(), bubbleRect.top() + 22);
    } else {
        triangle.moveTo(bubbleRect.left(), bubbleRect.top() + 14);
        triangle.lineTo(bubbleRect.left() - 8, bubbleRect.top() + 18);
        triangle.lineTo(bubbleRect.left(), bubbleRect.top() + 22);
    }
    painter->drawPath(triangle);

    // 绘制发送者名字（他人消息）
    int textY = bubbleY + m_padding;
    if (!isMine) {
        painter->setPen(m_senderColor);
        painter->setFont(senderFont);
        painter->drawText(bubbleX + m_padding, textY + sfm.ascent(), sender);
        textY += senderH;
    }

    // 绘制消息内容
    painter->setPen(isMine ? m_myTextColor : m_otherTextColor);
    painter->setFont(contentFont);
    painter->drawText(QRect(bubbleX + m_padding, textY,
                            bubbleW - m_padding * 2, textRect.height()),
                      Qt::TextWordWrap, content);

    // 绘制时间
    painter->setPen(m_timeColor);
    painter->setFont(timeFont);
    painter->drawText(QRect(bubbleX + m_padding,
                            bubbleY + bubbleH - m_padding - tfm.height(),
                            bubbleW - m_padding * 2, tfm.height()),
                      Qt::AlignRight, timeStr);
}

// ==================== 系统消息 ====================

void MessageDelegate::drawSystemMessage(QPainter *painter, const QStyleOptionViewItem &option,
                                          const QModelIndex &index) const {
    QString content = index.data(MessageModel::ContentRole).toString();
    QRect rect = option.rect;

    QFont font = option.font;
    font.setPointSize(font.pointSize() - 1);
    QFontMetrics fm(font);

    int textW = fm.horizontalAdvance(content) + 20;
    int textH = fm.height() + 8;

    QRect bgRect(rect.center().x() - textW / 2,
                 rect.center().y() - textH / 2,
                 textW, textH);

    painter->setPen(Qt::NoPen);
    painter->setBrush(QColor(200, 200, 200, 100));
    painter->drawRoundedRect(bgRect, 8, 8);

    painter->setPen(QColor(130, 130, 130));
    painter->setFont(font);
    painter->drawText(bgRect, Qt::AlignCenter, content);
}

// ==================== 文件消息 ====================

bool MessageDelegate::isImageFile(const QString &fileName) {
    static const QStringList exts = {"png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svg"};
    return exts.contains(QFileInfo(fileName).suffix().toLower());
}

bool MessageDelegate::isVideoFile(const QString &fileName) {
    static const QStringList exts = {"mp4", "avi", "mkv", "mov", "wmv", "flv", "webm"};
    return exts.contains(QFileInfo(fileName).suffix().toLower());
}

QPixmap MessageDelegate::loadCachedImage(int fileId, const QString &fileName) const {
    QString cacheKey = QString("msgimg_%1").arg(fileId);
    QPixmap pix;
    if (QPixmapCache::find(cacheKey, &pix))
        return pix;

    QString path = FileCache::instance()->cachedFilePath(fileId);
    if (path.isEmpty()) return QPixmap();

    if (!pix.load(path)) return QPixmap();

    // 缩放到合适大小
    if (pix.width() > m_maxImageWidth || pix.height() > m_maxImageHeight) {
        pix = pix.scaled(m_maxImageWidth, m_maxImageHeight,
                         Qt::KeepAspectRatio, Qt::SmoothTransformation);
    }
    QPixmapCache::insert(cacheKey, pix);
    return pix;
}

void MessageDelegate::drawFileBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                      const QModelIndex &index, bool isMine) const {
    QString fileName = index.data(MessageModel::FileNameRole).toString();
    int fileId       = index.data(MessageModel::FileIdRole).toInt();

    // 图片消息：内联预览
    if (isImageFile(fileName) && FileCache::instance()->isCached(fileId)) {
        drawImageBubble(painter, option, index, isMine);
        return;
    }

    // 视频消息：显示特殊图标
    bool isVideo = isVideoFile(fileName);

    QString sender   = index.data(MessageModel::SenderRole).toString();
    qint64 fileSize  = index.data(MessageModel::FileSizeRole).toLongLong();
    QDateTime time   = index.data(MessageModel::TimestampRole).toDateTime();

    QRect rect = option.rect;

    // 格式化文件大小
    QString sizeStr;
    if (fileSize < 1024)
        sizeStr = QString("%1 B").arg(fileSize);
    else if (fileSize < 1024 * 1024)
        sizeStr = QString("%1 KB").arg(fileSize / 1024.0, 0, 'f', 1);
    else
        sizeStr = QString("%1 MB").arg(fileSize / (1024.0 * 1024.0), 0, 'f', 1);

    int avatarX, bubbleX;
    int bubbleW = 240, bubbleH = 70;

    if (isMine) {
        avatarX = rect.right() - m_margin - m_avatarSize;
        bubbleX = avatarX - m_margin - bubbleW;
    } else {
        avatarX = rect.left() + m_margin;
        bubbleX = avatarX + m_avatarSize + m_margin;
    }
    int avatarY = rect.top() + m_margin;
    int bubbleY = rect.top() + m_margin;

    // 头像
    QRect avatarRect(avatarX, avatarY, m_avatarSize, m_avatarSize);
    painter->setPen(Qt::NoPen);
    quint32 hash = qHash(sender);
    QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
    painter->setBrush(avatarColor);
    painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
    painter->setPen(Qt::white);
    painter->setFont(option.font);
    painter->drawText(avatarRect, Qt::AlignCenter, sender.left(1).toUpper());

    // 文件气泡
    QRect bubbleRect(bubbleX, bubbleY, bubbleW, bubbleH);
    painter->setPen(Qt::NoPen);
    painter->setBrush(m_fileBgColor);
    painter->drawRoundedRect(bubbleRect, m_bubbleRadius, m_bubbleRadius);

    // 文件图标
    painter->setPen(Qt::NoPen);
    painter->setBrush(isVideo ? QColor(220, 80, 60) : QColor(66, 133, 244));
    QRect iconRect(bubbleX + 12, bubbleY + 15, 40, 40);
    painter->drawRoundedRect(iconRect, 6, 6);
    painter->setPen(Qt::white);
    QFont iconFont = option.font;
    iconFont.setPointSize(16);
    painter->setFont(iconFont);
    painter->drawText(iconRect, Qt::AlignCenter, isVideo ? QStringLiteral("\u25B6") : QStringLiteral("\U0001F4C4"));

    // 文件名
    painter->setPen(Qt::black);
    painter->setFont(option.font);
    QFontMetrics fm(option.font);
    QString elidedName = fm.elidedText(fileName, Qt::ElideMiddle, bubbleW - 80);
    painter->drawText(bubbleX + 60, bubbleY + 28, elidedName);

    // 文件大小
    QFont smallFont = option.font;
    smallFont.setPointSize(smallFont.pointSize() - 2);
    painter->setFont(smallFont);
    painter->setPen(m_timeColor);
    painter->drawText(bubbleX + 60, bubbleY + 48, sizeStr);

    // 时间
    QString timeStr = formatSmartTime(time);
    QFontMetrics tfm(smallFont);
    painter->drawText(bubbleX + bubbleW - m_padding - tfm.horizontalAdvance(timeStr),
                      bubbleY + bubbleH - 8, timeStr);
}

// ==================== 图片预览气泡 ====================

void MessageDelegate::drawImageBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                       const QModelIndex &index, bool isMine) const {
    QString sender   = index.data(MessageModel::SenderRole).toString();
    QString fileName = index.data(MessageModel::FileNameRole).toString();
    int fileId       = index.data(MessageModel::FileIdRole).toInt();
    QDateTime time   = index.data(MessageModel::TimestampRole).toDateTime();

    QRect rect = option.rect;
    QPixmap pix = loadCachedImage(fileId, fileName);

    int imgW = pix.isNull() ? 120 : pix.width();
    int imgH = pix.isNull() ? 120 : pix.height();

    QFont senderFont = option.font;
    senderFont.setPointSize(senderFont.pointSize() - 1);
    QFontMetrics sfm(senderFont);
    int senderH = isMine ? 0 : sfm.height() + 4;

    QFont timeFont = option.font;
    timeFont.setPointSize(timeFont.pointSize() - 2);
    QFontMetrics tfm(timeFont);

    int bubbleW = imgW + m_padding * 2;
    int bubbleH = senderH + imgH + tfm.height() + m_padding * 2 + 6;

    int avatarX, bubbleX;
    if (isMine) {
        avatarX = rect.right() - m_margin - m_avatarSize;
        bubbleX = avatarX - m_margin - bubbleW;
    } else {
        avatarX = rect.left() + m_margin;
        bubbleX = avatarX + m_avatarSize + m_margin;
    }
    int avatarY = rect.top() + m_margin;
    int bubbleY = rect.top() + m_margin;

    // 头像
    QRect avatarRect(avatarX, avatarY, m_avatarSize, m_avatarSize);
    painter->setPen(Qt::NoPen);
    quint32 hash = qHash(sender);
    QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
    painter->setBrush(avatarColor);
    painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
    painter->setPen(Qt::white);
    painter->setFont(option.font);
    painter->drawText(avatarRect, Qt::AlignCenter, sender.left(1).toUpper());

    // 气泡背景
    QRect bubbleRect(bubbleX, bubbleY, bubbleW, bubbleH);
    painter->setPen(Qt::NoPen);
    painter->setBrush(isMine ? m_myBubbleColor : m_otherBubbleColor);
    painter->drawRoundedRect(bubbleRect, m_bubbleRadius, m_bubbleRadius);

    // 小三角
    QPainterPath triangle;
    if (isMine) {
        triangle.moveTo(bubbleRect.right(), bubbleRect.top() + 14);
        triangle.lineTo(bubbleRect.right() + 8, bubbleRect.top() + 18);
        triangle.lineTo(bubbleRect.right(), bubbleRect.top() + 22);
    } else {
        triangle.moveTo(bubbleRect.left(), bubbleRect.top() + 14);
        triangle.lineTo(bubbleRect.left() - 8, bubbleRect.top() + 18);
        triangle.lineTo(bubbleRect.left(), bubbleRect.top() + 22);
    }
    painter->drawPath(triangle);

    int contentY = bubbleY + m_padding;

    // 发送者名字
    if (!isMine) {
        painter->setPen(m_senderColor);
        painter->setFont(senderFont);
        painter->drawText(bubbleX + m_padding, contentY + sfm.ascent(), sender);
        contentY += senderH;
    }

    // 图片
    if (!pix.isNull()) {
        QRect imgRect(bubbleX + m_padding, contentY, imgW, imgH);
        // 圆角裁剪
        QPainterPath clipPath;
        clipPath.addRoundedRect(imgRect, 6, 6);
        painter->setClipPath(clipPath);
        painter->drawPixmap(imgRect, pix);
        painter->setClipping(false);
    } else {
        // 加载中占位
        QRect placeholder(bubbleX + m_padding, contentY, imgW, imgH);
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(220, 220, 220));
        painter->drawRoundedRect(placeholder, 6, 6);
        painter->setPen(m_timeColor);
        painter->setFont(option.font);
        painter->drawText(placeholder, Qt::AlignCenter, "加载中...");
    }

    // 时间
    QString timeStr = formatSmartTime(time);
    painter->setPen(m_timeColor);
    painter->setFont(timeFont);
    painter->drawText(QRect(bubbleX + m_padding,
                            bubbleY + bubbleH - m_padding - tfm.height(),
                            bubbleW - m_padding * 2, tfm.height()),
                      Qt::AlignRight, timeStr);
}

// ==================== 已撤回消息 ====================

void MessageDelegate::drawRecalledMessage(QPainter *painter, const QStyleOptionViewItem &option,
                                            const QModelIndex &index) const {
    QString sender = index.data(MessageModel::SenderRole).toString();
    QRect rect = option.rect;

    QString text = QString("%1 撤回了一条消息").arg(sender);
    QFont font = option.font;
    font.setPointSize(font.pointSize() - 1);
    font.setItalic(true);
    QFontMetrics fm(font);

    int textW = fm.horizontalAdvance(text) + 20;
    int textH = fm.height() + 8;

    QRect bgRect(rect.center().x() - textW / 2,
                 rect.center().y() - textH / 2,
                 textW, textH);

    painter->setPen(Qt::NoPen);
    painter->setBrush(QColor(255, 235, 200, 120));
    painter->drawRoundedRect(bgRect, 8, 8);

    painter->setPen(QColor(180, 140, 80));
    painter->setFont(font);
    painter->drawText(bgRect, Qt::AlignCenter, text);
}

// ==================== 尺寸计算 ====================

QSize MessageDelegate::textBubbleSize(const QStyleOptionViewItem &option,
                                       const QModelIndex &index) const {
    int contentType = index.data(MessageModel::ContentTypeRole).toInt();

    if (contentType == static_cast<int>(Message::File)) {
        QString fileName = index.data(MessageModel::FileNameRole).toString();
        int fileId       = index.data(MessageModel::FileIdRole).toInt();

        // 图片文件：计算图片预览尺寸
        if (isImageFile(fileName) && FileCache::instance()->isCached(fileId)) {
            QPixmap pix = loadCachedImage(fileId, fileName);
            int imgW = pix.isNull() ? 120 : pix.width();
            int imgH = pix.isNull() ? 120 : pix.height();

            bool isMine = index.data(MessageModel::IsMineRole).toBool();
            QFont senderFont = option.font;
            senderFont.setPointSize(senderFont.pointSize() - 1);
            QFontMetrics sfm(senderFont);
            int senderH = isMine ? 0 : sfm.height() + 4;

            QFont timeFont = option.font;
            timeFont.setPointSize(timeFont.pointSize() - 2);
            QFontMetrics tfm(timeFont);

            int h = senderH + imgH + tfm.height() + m_padding * 2 + 6 + m_margin * 2;
            return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
        }

        return QSize(option.rect.width(), 70 + m_margin * 2);
    }

    QString content = index.data(MessageModel::ContentRole).toString();
    bool isMine = index.data(MessageModel::IsMineRole).toBool();

    int bubbleMaxW = qMin(m_maxBubbleWidth, option.rect.width() - m_avatarSize - m_margin * 4);

    QFont contentFont = option.font;
    if (contentType == static_cast<int>(Message::Emoji))
        contentFont.setPointSize(contentFont.pointSize() + 8);

    QFontMetrics fm(contentFont);
    QRect textRect = fm.boundingRect(QRect(0, 0, bubbleMaxW - m_padding * 2, 9999),
                                     Qt::TextWordWrap, content);

    QFont senderFont = option.font;
    senderFont.setPointSize(senderFont.pointSize() - 1);
    QFontMetrics sfm(senderFont);
    int senderH = isMine ? 0 : sfm.height() + 2;

    QFont timeFont = option.font;
    timeFont.setPointSize(timeFont.pointSize() - 2);
    QFontMetrics tfm(timeFont);

    int h = senderH + textRect.height() + tfm.height() + m_padding * 2 + 4 + m_margin * 2;
    return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
}
