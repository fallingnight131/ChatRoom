#include "MessageDelegate.h"
#include "MessageModel.h"
#include "Message.h"
#include "FileCache.h"
#include "ChatWindow.h"
#include "ThemeManager.h"

#include <QPainter>
#include <QPainterPath>
#include <QLinearGradient>
#include <QApplication>
#include <QDateTime>
#include <QTextDocument>
#include <QPixmapCache>
#include <QFileInfo>
#include <QFile>

MessageDelegate::MessageDelegate(QObject *parent)
    : QStyledItemDelegate(parent)
{
    // 根据当前主题初始化颜色
    updateThemeColors(ThemeManager::instance()->currentTheme() == ThemeManager::Dark);

    // 监听主题变更
    connect(ThemeManager::instance(), &ThemeManager::themeChanged, this, [this](ThemeManager::Theme t) {
        updateThemeColors(t == ThemeManager::Dark);
    });
}

void MessageDelegate::updateThemeColors(bool isDark) {
    // 气泡和气泡内文字保持原始配色，不随主题变化
    m_myBubbleColor    = QColor(149, 236, 105);     // 微信绿
    m_otherBubbleColor = QColor(255, 255, 255);     // 白色
    m_myTextColor      = Qt::black;
    m_otherTextColor   = Qt::black;

    if (isDark) {
        m_systemColor      = QColor(80, 80, 100);
        m_senderColor      = QColor(100, 100, 100);
        m_timeColor        = QColor(140, 140, 160);
        m_fileBgColor      = QColor(230, 240, 250);
    } else {
        m_systemColor      = QColor(200, 200, 200);
        m_senderColor      = QColor(100, 100, 100);
        m_timeColor        = QColor(150, 150, 150);
        m_fileBgColor      = QColor(230, 240, 250);
    }
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

    bool isDark = ThemeManager::instance()->currentTheme() == ThemeManager::Dark;

    // 绘制选中/悬停背景
    if (option.state & QStyle::State_Selected) {
        painter->fillRect(option.rect, isDark ? QColor(80, 90, 120, 80) : QColor(220, 235, 255, 80));
    } else if (option.state & QStyle::State_MouseOver) {
        painter->fillRect(option.rect, isDark ? QColor(60, 65, 85, 60) : QColor(240, 245, 250, 60));
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

    int senderH = sfm.height() + 2;
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

    // 绘制头像
    QRect avatarRect(avatarX, avatarY, m_avatarSize, m_avatarSize);
    QPixmap avatarPix = ChatWindow::avatarForUser(sender);
    if (!avatarPix.isNull()) {
        QPixmap scaled = avatarPix.scaled(m_avatarSize, m_avatarSize,
            Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        QPainterPath clipPath;
        clipPath.addEllipse(avatarRect);
        painter->setClipPath(clipPath);
        painter->drawPixmap(avatarRect, scaled);
        painter->setClipping(false);
    } else {
        painter->setPen(Qt::NoPen);
        quint32 hash = qHash(sender);
        QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
        painter->setBrush(avatarColor);
        painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
        painter->setPen(Qt::white);
        painter->setFont(option.font);
        painter->drawText(avatarRect, Qt::AlignCenter,
                          sender.isEmpty() ? "?" : sender.left(1).toUpper());
    }

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

    // 绘制发送者名字
    int textY = bubbleY + m_padding;
    painter->setPen(m_senderColor);
    painter->setFont(senderFont);
    painter->drawText(bubbleX + m_padding, textY + sfm.ascent(), sender);
    textY += senderH;

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
    QColor sysBg = m_systemColor;
    sysBg.setAlpha(100);
    painter->setBrush(sysBg);
    painter->drawRoundedRect(bgRect, 8, 8);

    painter->setPen(m_timeColor);
    painter->setFont(font);
    painter->drawText(bgRect, Qt::AlignCenter, content);
}

// ==================== 文件消息 ====================

bool MessageDelegate::isImageFile(const QString &fileName) {
    static const QStringList exts = {"png", "jpg", "jpeg", "gif", "bmp", "webp"};
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

QPixmap MessageDelegate::loadVideoThumbnail(int fileId) const {
    QString cacheKey = QString("vidthumb_%1").arg(fileId);
    QPixmap pix;
    if (QPixmapCache::find(cacheKey, &pix))
        return pix;

    // 从磁盘加载缩略图文件
    QString thumbPath = FileCache::instance()->cacheDir()
                        + QString("/thumb_%1.jpg").arg(fileId);
    if (!QFile::exists(thumbPath)) return QPixmap();

    if (!pix.load(thumbPath)) return QPixmap();

    // 缩放到合适大小
    if (pix.width() > m_maxImageWidth) {
        pix = pix.scaledToWidth(m_maxImageWidth, Qt::SmoothTransformation);
    }
    QPixmapCache::insert(cacheKey, pix);
    return pix;
}

// ==================== 饼状进度条 ====================

void MessageDelegate::drawPieProgress(QPainter *painter, const QRect &rect, double progress) const {
    // 半透明遮罩
    painter->setPen(Qt::NoPen);
    painter->setBrush(QColor(0, 0, 0, 120));
    painter->drawRoundedRect(rect, 6, 6);

    // 饼状进度
    int pieSize = qMin(rect.width(), rect.height()) / 2;
    pieSize = qMin(pieSize, 48);
    QRect pieRect(rect.center().x() - pieSize / 2,
                  rect.center().y() - pieSize / 2,
                  pieSize, pieSize);

    // 背景圆
    painter->setBrush(QColor(255, 255, 255, 60));
    painter->drawEllipse(pieRect);

    // 进度扇形
    painter->setBrush(QColor(76, 175, 80));  // 绿色
    int startAngle = 90 * 16;  // 从12点方向开始
    int spanAngle = -static_cast<int>(progress * 360 * 16);
    painter->drawPie(pieRect, startAngle, spanAngle);

    // 中心百分比文字
    painter->setPen(Qt::white);
    QFont pFont = painter->font();
    pFont.setPointSize(pieSize > 30 ? 9 : 7);
    pFont.setBold(true);
    painter->setFont(pFont);
    painter->drawText(pieRect, Qt::AlignCenter, QString("%1%").arg(static_cast<int>(progress * 100)));
}

void MessageDelegate::drawFileBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                      const QModelIndex &index, bool isMine) const {
    QString fileName = index.data(MessageModel::FileNameRole).toString();
    int fileId       = index.data(MessageModel::FileIdRole).toInt();
    int dlState      = index.data(MessageModel::DownloadStateRole).toInt();
    double dlProgress = index.data(MessageModel::DownloadProgressRole).toDouble();
    bool cached = FileCache::instance()->isCached(fileId);

    // 图片消息：内联预览（已缓存或正在下载中显示占位图）
    if (isImageFile(fileName)) {
        drawImageBubble(painter, option, index, isMine);
        return;
    }

    // 视频消息：特殊显示
    if (isVideoFile(fileName)) {
        drawVideoBubble(painter, option, index, isMine);
        return;
    }

    // ---------- 普通文件消息气泡 ----------

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
    QPixmap avatarPix = ChatWindow::avatarForUser(sender);
    if (!avatarPix.isNull()) {
        QPixmap scaled = avatarPix.scaled(m_avatarSize, m_avatarSize,
            Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        QPainterPath clipPath;
        clipPath.addEllipse(avatarRect);
        painter->setClipPath(clipPath);
        painter->drawPixmap(avatarRect, scaled);
        painter->setClipping(false);
    } else {
        painter->setPen(Qt::NoPen);
        quint32 hash = qHash(sender);
        QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
        painter->setBrush(avatarColor);
        painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
        painter->setPen(Qt::white);
        painter->setFont(option.font);
        painter->drawText(avatarRect, Qt::AlignCenter, sender.left(1).toUpper());
    }

    // 文件气泡
    QRect bubbleRect(bubbleX, bubbleY, bubbleW, bubbleH);
    painter->setPen(Qt::NoPen);
    painter->setBrush(m_fileBgColor);
    painter->drawRoundedRect(bubbleRect, m_bubbleRadius, m_bubbleRadius);

    // 文件图标区域
    QRect iconRect(bubbleX + 12, bubbleY + 15, 40, 40);

    if (dlState == Message::Downloading) {
        // 正在下载 → 图标区域显示饼状进度
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(66, 133, 244, 60));
        painter->drawRoundedRect(iconRect, 6, 6);
        drawPieProgress(painter, iconRect, dlProgress);
    } else if (!cached && dlState != Message::Downloaded) {
        // 未下载 → 显示下载箭头图标（蓝底白色↓）
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(66, 133, 244));
        painter->drawRoundedRect(iconRect, 6, 6);
        painter->setPen(Qt::white);
        QFont iconFont = option.font;
        iconFont.setPointSize(18);
        iconFont.setBold(true);
        painter->setFont(iconFont);
        painter->drawText(iconRect, Qt::AlignCenter, QStringLiteral("\u2913")); // ⤓ 下载箭头
    } else {
        // 已下载 → 正常文件图标
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(66, 133, 244));
        painter->drawRoundedRect(iconRect, 6, 6);
        painter->setPen(Qt::white);
        QFont iconFont = option.font;
        iconFont.setPointSize(16);
        painter->setFont(iconFont);
        painter->drawText(iconRect, Qt::AlignCenter, QStringLiteral("\U0001F4C4"));
    }

    // 文件名
    painter->setPen(isMine ? m_myTextColor : m_otherTextColor);
    painter->setFont(option.font);
    QFontMetrics fm(option.font);
    QString elidedName = fm.elidedText(fileName, Qt::ElideMiddle, bubbleW - 80);
    painter->drawText(bubbleX + 60, bubbleY + 28, elidedName);

    // 文件大小 + 状态文字
    QFont smallFont = option.font;
    smallFont.setPointSize(smallFont.pointSize() - 2);
    painter->setFont(smallFont);
    painter->setPen(m_timeColor);
    QString statusStr = sizeStr;
    if (dlState == Message::Downloading) {
        statusStr += QString("  下载中 %1%").arg(static_cast<int>(dlProgress * 100));
    } else if (!cached && dlState != Message::Downloaded) {
        statusStr += QStringLiteral("  点击下载");
        painter->setPen(QColor(66, 133, 244));  // 蓝色提示
    }
    painter->drawText(bubbleX + 60, bubbleY + 48, statusStr);

    // 时间
    painter->setPen(m_timeColor);
    QString timeStr = formatSmartTime(time);
    QFontMetrics tfm(smallFont);
    painter->drawText(bubbleX + bubbleW - m_padding - tfm.horizontalAdvance(timeStr),
                      bubbleY + bubbleH - 8, timeStr);
}

// ==================== 视频消息气泡 ====================

void MessageDelegate::drawVideoBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                       const QModelIndex &index, bool isMine) const {
    QString sender   = index.data(MessageModel::SenderRole).toString();
    QString fileName = index.data(MessageModel::FileNameRole).toString();
    int fileId       = index.data(MessageModel::FileIdRole).toInt();
    qint64 fileSize  = index.data(MessageModel::FileSizeRole).toLongLong();
    QDateTime time   = index.data(MessageModel::TimestampRole).toDateTime();
    int dlState      = index.data(MessageModel::DownloadStateRole).toInt();
    double dlProgress = index.data(MessageModel::DownloadProgressRole).toDouble();
    bool cached = FileCache::instance()->isCached(fileId);

    QRect rect = option.rect;

    // 视频缩略图尺寸（固定比例 16:9）
    int thumbW = m_maxImageWidth;
    int thumbH = static_cast<int>(thumbW * 9.0 / 16.0);

    QFont senderFont = option.font;
    senderFont.setPointSize(senderFont.pointSize() - 1);
    QFontMetrics sfm(senderFont);
    int senderH = sfm.height() + 4;

    QFont timeFont = option.font;
    timeFont.setPointSize(timeFont.pointSize() - 2);
    QFontMetrics tfm(timeFont);

    int bubbleW = thumbW + m_padding * 2;
    int bubbleH = senderH + thumbH + tfm.height() + m_padding * 2 + 6;

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
    QPixmap avatarPix = ChatWindow::avatarForUser(sender);
    if (!avatarPix.isNull()) {
        QPixmap scaled = avatarPix.scaled(m_avatarSize, m_avatarSize,
            Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        QPainterPath clipPath;
        clipPath.addEllipse(avatarRect);
        painter->setClipPath(clipPath);
        painter->drawPixmap(avatarRect, scaled);
        painter->setClipping(false);
    } else {
        painter->setPen(Qt::NoPen);
        quint32 hash = qHash(sender);
        QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
        painter->setBrush(avatarColor);
        painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
        painter->setPen(Qt::white);
        painter->setFont(option.font);
        painter->drawText(avatarRect, Qt::AlignCenter, sender.left(1).toUpper());
    }

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
    painter->setPen(m_senderColor);
    painter->setFont(senderFont);
    painter->drawText(bubbleX + m_padding, contentY + sfm.ascent(), sender);
    contentY += senderH;

    // 视频缩略图区域
    QRect thumbRect(bubbleX + m_padding, contentY, thumbW, thumbH);
    QPainterPath thumbClip;
    thumbClip.addRoundedRect(thumbRect, 6, 6);
    painter->setClipPath(thumbClip);

    // 尝试加载真实缩略图
    QPixmap thumbPix = loadVideoThumbnail(fileId);
    if (!thumbPix.isNull()) {
        // 有缩略图 → 绘制缩略图（居中裁剪到 16:9）
        QPixmap scaled = thumbPix.scaled(thumbW, thumbH,
            Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        int sx = (scaled.width() - thumbW) / 2;
        int sy = (scaled.height() - thumbH) / 2;
        painter->drawPixmap(thumbRect, scaled, QRect(sx, sy, thumbW, thumbH));
    } else {
        // 无缩略图 → 渐变占位 + 胶片装饰
        QLinearGradient grad(thumbRect.topLeft(), thumbRect.bottomRight());
        grad.setColorAt(0, QColor(45, 55, 72));
        grad.setColorAt(1, QColor(26, 32, 44));
        painter->setPen(Qt::NoPen);
        painter->setBrush(grad);
        painter->drawRect(thumbRect);

        // 胶片条纹
        painter->setBrush(QColor(0, 0, 0, 80));
        int stripH = 6;
        for (int sx = thumbRect.left(); sx < thumbRect.right(); sx += 16) {
            painter->drawRect(sx, thumbRect.top(), 10, stripH);
            painter->drawRect(sx, thumbRect.bottom() - stripH + 1, 10, stripH);
        }

        // 文件名标签
        QFont nameFont = option.font;
        nameFont.setPointSize(nameFont.pointSize() - 2);
        QFontMetrics nfm(nameFont);
        QString shortName = nfm.elidedText(fileName, Qt::ElideMiddle, thumbW - 20);
        int nameW = nfm.horizontalAdvance(shortName) + 12;
        int nameH = nfm.height() + 4;
        QRect nameRect(thumbRect.center().x() - nameW / 2,
                       thumbRect.bottom() - nameH - 10,
                       nameW, nameH);
        painter->setBrush(QColor(0, 0, 0, 140));
        painter->drawRoundedRect(nameRect, 3, 3);
        painter->setPen(QColor(200, 200, 200));
        painter->setFont(nameFont);
        painter->drawText(nameRect, Qt::AlignCenter, shortName);
    }

    painter->setClipping(false);

    if (dlState == Message::Downloading) {
        // 下载中 → 饼状进度
        drawPieProgress(painter, thumbRect, dlProgress);
    } else {
        // 播放按钮（白色半透明圆 + ▶）
        int playSize = 48;
        QRect playRect(thumbRect.center().x() - playSize / 2,
                       thumbRect.center().y() - playSize / 2,
                       playSize, playSize);
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(0, 0, 0, 140));
        painter->drawEllipse(playRect);
        painter->setPen(Qt::white);
        QFont playFont = option.font;
        playFont.setPointSize(20);
        painter->setFont(playFont);
        painter->drawText(playRect, Qt::AlignCenter, QStringLiteral("\u25B6")); // ▶

        // 文件大小标签
        QString sizeStr;
        if (fileSize < 1024 * 1024)
            sizeStr = QString("%1 KB").arg(fileSize / 1024.0, 0, 'f', 1);
        else
            sizeStr = QString("%1 MB").arg(fileSize / (1024.0 * 1024.0), 0, 'f', 1);

        QFont labelFont = option.font;
        labelFont.setPointSize(labelFont.pointSize() - 2);
        painter->setFont(labelFont);
        QFontMetrics lfm(labelFont);

        // 文件名 + 大小在缩略图底部
        QString label = (!cached && dlState != Message::Downloaded)
                        ? sizeStr + QStringLiteral("  点击下载")
                        : sizeStr;
        int labelW = lfm.horizontalAdvance(label) + 12;
        int labelH = lfm.height() + 4;
        QRect labelRect(thumbRect.left() + 4,
                        thumbRect.bottom() - labelH - 4,
                        labelW, labelH);
        painter->setPen(Qt::NoPen);
        painter->setBrush(QColor(0, 0, 0, 160));
        painter->drawRoundedRect(labelRect, 4, 4);
        painter->setPen(Qt::white);
        painter->drawText(labelRect, Qt::AlignCenter, label);
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

// ==================== 图片预览气泡 ====================

void MessageDelegate::drawImageBubble(QPainter *painter, const QStyleOptionViewItem &option,
                                       const QModelIndex &index, bool isMine) const {
    QString sender   = index.data(MessageModel::SenderRole).toString();
    QString fileName = index.data(MessageModel::FileNameRole).toString();
    int fileId       = index.data(MessageModel::FileIdRole).toInt();
    QDateTime time   = index.data(MessageModel::TimestampRole).toDateTime();
    int dlState      = index.data(MessageModel::DownloadStateRole).toInt();
    double dlProgress = index.data(MessageModel::DownloadProgressRole).toDouble();
    bool cached = FileCache::instance()->isCached(fileId);

    QRect rect = option.rect;
    QPixmap pix = cached ? loadCachedImage(fileId, fileName) : QPixmap();

    int imgW = pix.isNull() ? 120 : pix.width();
    int imgH = pix.isNull() ? 120 : pix.height();

    QFont senderFont = option.font;
    senderFont.setPointSize(senderFont.pointSize() - 1);
    QFontMetrics sfm(senderFont);
    int senderH = sfm.height() + 4;

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
    QPixmap avatarPix = ChatWindow::avatarForUser(sender);
    if (!avatarPix.isNull()) {
        QPixmap scaled = avatarPix.scaled(m_avatarSize, m_avatarSize,
            Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
        QPainterPath clipPath;
        clipPath.addEllipse(avatarRect);
        painter->setClipPath(clipPath);
        painter->drawPixmap(avatarRect, scaled);
        painter->setClipping(false);
    } else {
        painter->setPen(Qt::NoPen);
        quint32 hash = qHash(sender);
        QColor avatarColor = QColor::fromHsl(hash % 360, 150, 130);
        painter->setBrush(avatarColor);
        painter->drawRoundedRect(avatarRect, m_avatarSize / 2, m_avatarSize / 2);
        painter->setPen(Qt::white);
        painter->setFont(option.font);
        painter->drawText(avatarRect, Qt::AlignCenter, sender.left(1).toUpper());
    }

    // 气泡背景
    QRect bubbleRect(bubbleX, bubbleY, bubbleW, bubbleH);
    painter->setPen(Qt::NoPen);
    painter->setBrush(isMine ? m_myBubbleColor : m_otherBubbleColor);
    painter->drawRoundedRect(bubbleRect, m_bubbleRadius, m_bubbleRadius);

    // 小三角
    QPainterPath trianglePath;
    if (isMine) {
        trianglePath.moveTo(bubbleRect.right(), bubbleRect.top() + 14);
        trianglePath.lineTo(bubbleRect.right() + 8, bubbleRect.top() + 18);
        trianglePath.lineTo(bubbleRect.right(), bubbleRect.top() + 22);
    } else {
        trianglePath.moveTo(bubbleRect.left(), bubbleRect.top() + 14);
        trianglePath.lineTo(bubbleRect.left() - 8, bubbleRect.top() + 18);
        trianglePath.lineTo(bubbleRect.left(), bubbleRect.top() + 22);
    }
    painter->drawPath(trianglePath);

    int contentY = bubbleY + m_padding;

    // 发送者名字
    painter->setPen(m_senderColor);
    painter->setFont(senderFont);
    painter->drawText(bubbleX + m_padding, contentY + sfm.ascent(), sender);
    contentY += senderH;

    // 图片区域
    QRect imgRect(bubbleX + m_padding, contentY, imgW, imgH);

    if (!pix.isNull()) {
        // 已缓存图片 → 正常显示
        QPainterPath clipPath;
        clipPath.addRoundedRect(imgRect, 6, 6);
        painter->setClipPath(clipPath);
        painter->drawPixmap(imgRect, pix);
        painter->setClipping(false);
    } else {
        // 占位背景
        painter->setPen(Qt::NoPen);
        painter->setBrush(m_fileBgColor);
        painter->drawRoundedRect(imgRect, 6, 6);

        if (dlState == Message::Downloading) {
            // 图片下载中 → 显示饼状进度
            drawPieProgress(painter, imgRect, dlProgress);
        } else {
            // 等待下载
            painter->setPen(m_timeColor);
            painter->setFont(option.font);
            painter->drawText(imgRect, Qt::AlignCenter, QStringLiteral("加载中..."));
        }
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
    QColor recalledBg = m_systemColor;
    recalledBg.setAlpha(80);
    painter->setBrush(recalledBg);
    painter->drawRoundedRect(bgRect, 8, 8);

    painter->setPen(m_timeColor);
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

        // 图片文件：使用图片预览尺寸（无论是否已缓存，保持统一高度）
        if (isImageFile(fileName)) {
            if (FileCache::instance()->isCached(fileId)) {
                QPixmap pix = loadCachedImage(fileId, fileName);
                int imgH = pix.isNull() ? 120 : pix.height();

                QFont senderFont = option.font;
                senderFont.setPointSize(senderFont.pointSize() - 1);
                QFontMetrics sfm(senderFont);
                int senderH = sfm.height() + 4;

                QFont timeFont = option.font;
                timeFont.setPointSize(timeFont.pointSize() - 2);
                QFontMetrics tfmm(timeFont);

                int h = senderH + imgH + tfmm.height() + m_padding * 2 + 6 + m_margin * 2;
                return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
            }
            // 未缓存图片：占位高度
            int placeholderH = 120;
            QFont senderFont = option.font;
            senderFont.setPointSize(senderFont.pointSize() - 1);
            QFontMetrics sfm(senderFont);
            int senderH = sfm.height() + 4;
            QFont timeFont = option.font;
            timeFont.setPointSize(timeFont.pointSize() - 2);
            QFontMetrics tfmm(timeFont);
            int h = senderH + placeholderH + tfmm.height() + m_padding * 2 + 6 + m_margin * 2;
            return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
        }

        // 视频文件：16:9 缩略图尺寸
        if (isVideoFile(fileName)) {
            int thumbW = m_maxImageWidth;
            int thumbH = static_cast<int>(thumbW * 9.0 / 16.0);

            QFont senderFont = option.font;
            senderFont.setPointSize(senderFont.pointSize() - 1);
            QFontMetrics sfm(senderFont);
            int senderH = sfm.height() + 4;

            QFont timeFont = option.font;
            timeFont.setPointSize(timeFont.pointSize() - 2);
            QFontMetrics tfmm(timeFont);

            int h = senderH + thumbH + tfmm.height() + m_padding * 2 + 6 + m_margin * 2;
            return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
        }

        // 普通文件
        return QSize(option.rect.width(), 70 + m_margin * 2);
    }

    QString content = index.data(MessageModel::ContentRole).toString();

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
    int senderH = sfm.height() + 2;

    QFont timeFont = option.font;
    timeFont.setPointSize(timeFont.pointSize() - 2);
    QFontMetrics tfm(timeFont);

    int h = senderH + textRect.height() + tfm.height() + m_padding * 2 + 4 + m_margin * 2;
    return QSize(option.rect.width(), qMax(h, m_avatarSize + m_margin * 2));
}
