#include "AvatarCropDialog.h"

#include <QPainter>
#include <QPainterPath>
#include <QMouseEvent>
#include <QWheelEvent>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QLabel>

AvatarCropDialog::AvatarCropDialog(const QPixmap &image, QWidget *parent)
    : QDialog(parent)
    , m_original(image)
{
    setWindowTitle("裁剪头像");
    setFixedSize(CANVAS_SIZE + 40, CANVAS_SIZE + 120);
    setMouseTracking(true);

    // 将原图等比缩放到画布
    m_scaled = m_original.scaled(CANVAS_SIZE, CANVAS_SIZE,
                                  Qt::KeepAspectRatio, Qt::SmoothTransformation);
    m_scaleFactor = static_cast<double>(m_original.width()) / m_scaled.width();

    // 画布居中
    int cx = (width() - m_scaled.width()) / 2;
    int cy = 10;
    m_canvasRect = QRect(cx, cy, m_scaled.width(), m_scaled.height());

    // 初始裁剪区域（居中，取较短边的 80%）
    m_cropSize = qMin(m_scaled.width(), m_scaled.height()) * 80 / 100;
    m_cropRect = QRect(
        m_canvasRect.center().x() - m_cropSize / 2,
        m_canvasRect.center().y() - m_cropSize / 2,
        m_cropSize, m_cropSize);

    // 预览标签
    m_previewLabel = new QLabel(this);
    m_previewLabel->setFixedSize(64, 64);
    m_previewLabel->setStyleSheet("border: 1px solid #ccc; border-radius: 32px;");
    m_previewLabel->setScaledContents(true);

    // 按钮
    auto *okBtn = new QPushButton("确定", this);
    auto *cancelBtn = new QPushButton("取消", this);
    okBtn->setFixedWidth(80);
    cancelBtn->setFixedWidth(80);

    connect(okBtn, &QPushButton::clicked, this, &QDialog::accept);
    connect(cancelBtn, &QPushButton::clicked, this, &QDialog::reject);

    // 底部布局
    auto *bottomLayout = new QHBoxLayout;
    bottomLayout->addStretch();
    bottomLayout->addWidget(new QLabel("预览:", this));
    bottomLayout->addWidget(m_previewLabel);
    bottomLayout->addStretch();
    bottomLayout->addWidget(okBtn);
    bottomLayout->addWidget(cancelBtn);

    // 使用绝对定位底部布局
    auto *bottomWidget = new QWidget(this);
    bottomWidget->setLayout(bottomLayout);
    bottomWidget->setGeometry(0, CANVAS_SIZE + 20, width(), 80);

    updateCrop();
}

void AvatarCropDialog::updateCrop() {
    // 约束裁剪区域在画布内
    if (m_cropRect.left() < m_canvasRect.left())
        m_cropRect.moveLeft(m_canvasRect.left());
    if (m_cropRect.top() < m_canvasRect.top())
        m_cropRect.moveTop(m_canvasRect.top());
    if (m_cropRect.right() > m_canvasRect.right())
        m_cropRect.moveRight(m_canvasRect.right());
    if (m_cropRect.bottom() > m_canvasRect.bottom())
        m_cropRect.moveBottom(m_canvasRect.bottom());

    // 在原图坐标系中裁剪
    int srcX = static_cast<int>((m_cropRect.x() - m_canvasRect.x()) * m_scaleFactor);
    int srcY = static_cast<int>((m_cropRect.y() - m_canvasRect.y()) * m_scaleFactor);
    int srcW = static_cast<int>(m_cropRect.width() * m_scaleFactor);
    int srcH = static_cast<int>(m_cropRect.height() * m_scaleFactor);

    // 确保不超出原图
    srcX = qBound(0, srcX, m_original.width() - 1);
    srcY = qBound(0, srcY, m_original.height() - 1);
    srcW = qMin(srcW, m_original.width() - srcX);
    srcH = qMin(srcH, m_original.height() - srcY);

    QPixmap cropped = m_original.copy(srcX, srcY, srcW, srcH);
    QPixmap scaled = cropped.scaled(AVATAR_OUTPUT_SIZE, AVATAR_OUTPUT_SIZE,
                                     Qt::KeepAspectRatioByExpanding,
                                     Qt::SmoothTransformation);

    // 居中裁剪为正方形
    int ox = (scaled.width() - AVATAR_OUTPUT_SIZE) / 2;
    int oy = (scaled.height() - AVATAR_OUTPUT_SIZE) / 2;
    scaled = scaled.copy(ox, oy, AVATAR_OUTPUT_SIZE, AVATAR_OUTPUT_SIZE);

    // 应用圆形蒙版
    m_result = QPixmap(AVATAR_OUTPUT_SIZE, AVATAR_OUTPUT_SIZE);
    m_result.fill(Qt::transparent);
    QPainter p(&m_result);
    p.setRenderHint(QPainter::Antialiasing);
    QPainterPath path;
    path.addEllipse(0, 0, AVATAR_OUTPUT_SIZE, AVATAR_OUTPUT_SIZE);
    p.setClipPath(path);
    p.drawPixmap(0, 0, scaled);
    p.end();

    // 更新预览
    if (m_previewLabel)
        m_previewLabel->setPixmap(m_result.scaled(64, 64, Qt::KeepAspectRatio, Qt::SmoothTransformation));
}

QPixmap AvatarCropDialog::croppedAvatar() const {
    return m_result;
}

void AvatarCropDialog::paintEvent(QPaintEvent *) {
    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);

    // 背景
    painter.fillRect(rect(), QColor(40, 40, 40));

    // 绘制图片
    painter.drawPixmap(m_canvasRect, m_scaled);

    // 绘制半透明遮罩（裁剪区域外）
    QPainterPath fullPath;
    fullPath.addRect(m_canvasRect);
    QPainterPath cropPath;
    cropPath.addEllipse(m_cropRect);
    QPainterPath maskPath = fullPath - cropPath;
    painter.fillPath(maskPath, QColor(0, 0, 0, 150));

    // 绘制裁剪圆形边框
    painter.setPen(QPen(Qt::white, 2, Qt::DashLine));
    painter.setBrush(Qt::NoBrush);
    painter.drawEllipse(m_cropRect);

    // 十字准星
    QPoint center = m_cropRect.center();
    painter.setPen(QPen(QColor(255, 255, 255, 100), 1));
    painter.drawLine(center.x() - 10, center.y(), center.x() + 10, center.y());
    painter.drawLine(center.x(), center.y() - 10, center.x(), center.y() + 10);

    // 提示文字
    painter.setPen(Qt::white);
    QFont font = painter.font();
    font.setPointSize(9);
    painter.setFont(font);
    painter.drawText(QRect(0, m_canvasRect.bottom() + 4, width(), 20),
                     Qt::AlignCenter, "拖动移动裁剪区域 | 滚轮调整大小");
}

void AvatarCropDialog::mousePressEvent(QMouseEvent *event) {
    if (event->button() == Qt::LeftButton && m_cropRect.contains(event->pos())) {
        m_dragging = true;
        m_dragStart = event->pos();
        m_cropStartPos = m_cropRect.topLeft();
        setCursor(Qt::ClosedHandCursor);
    }
}

void AvatarCropDialog::mouseMoveEvent(QMouseEvent *event) {
    if (m_dragging) {
        QPoint delta = event->pos() - m_dragStart;
        m_cropRect.moveTopLeft(m_cropStartPos + delta);
        updateCrop();
        update();
    } else {
        // 更新光标
        if (m_cropRect.contains(event->pos()))
            setCursor(Qt::OpenHandCursor);
        else
            setCursor(Qt::ArrowCursor);
    }
}

void AvatarCropDialog::mouseReleaseEvent(QMouseEvent *event) {
    if (event->button() == Qt::LeftButton) {
        m_dragging = false;
        if (m_cropRect.contains(event->pos()))
            setCursor(Qt::OpenHandCursor);
        else
            setCursor(Qt::ArrowCursor);
    }
}

void AvatarCropDialog::wheelEvent(QWheelEvent *event) {
    int delta = event->angleDelta().y() > 0 ? 20 : -20;
    int newSize = qBound(40, m_cropSize + delta,
                          qMin(m_canvasRect.width(), m_canvasRect.height()));

    QPoint center = m_cropRect.center();
    m_cropSize = newSize;
    m_cropRect = QRect(center.x() - m_cropSize / 2,
                       center.y() - m_cropSize / 2,
                       m_cropSize, m_cropSize);

    updateCrop();
    update();
    event->accept();
}
