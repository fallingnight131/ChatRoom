#pragma once

#include <QDialog>
#include <QPixmap>
#include <QPoint>

class QLabel;

/// 头像裁剪对话框 —— 加载图片后可拖动/缩放圆形裁剪区域
class AvatarCropDialog : public QDialog {
    Q_OBJECT
public:
    /// @param image 原始图片
    explicit AvatarCropDialog(const QPixmap &image, QWidget *parent = nullptr);

    /// 获取裁剪后的圆形头像 (128x128 PNG)
    QPixmap croppedAvatar() const;

protected:
    void paintEvent(QPaintEvent *event) override;
    void mousePressEvent(QMouseEvent *event) override;
    void mouseMoveEvent(QMouseEvent *event) override;
    void mouseReleaseEvent(QMouseEvent *event) override;
    void wheelEvent(QWheelEvent *event) override;

private:
    void updateCrop();

    QPixmap m_original;      // 原图
    QPixmap m_scaled;        // 缩放到画布上的图片
    QRect   m_canvasRect;    // 图片绘制区域（在 widget 坐标）
    QRect   m_cropRect;      // 裁剪圈（正方形，在 widget 坐标）
    QPixmap m_result;        // 裁剪结果

    QPoint  m_dragStart;
    QPoint  m_cropStartPos;
    bool    m_dragging = false;

    int     m_cropSize;      // 裁剪圈直径（widget 坐标）
    double  m_scaleFactor;   // widget 到原图的缩放比

    QLabel *m_previewLabel = nullptr;

    static constexpr int CANVAS_SIZE = 460;
    static constexpr int AVATAR_OUTPUT_SIZE = 128;
};
