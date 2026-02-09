#pragma once

#include <QWidget>

class QGridLayout;

/// 表情选择器 —— 弹出式表情面板
class EmojiPicker : public QWidget {
    Q_OBJECT
public:
    explicit EmojiPicker(QWidget *parent = nullptr);

signals:
    void emojiSelected(const QString &emoji);

private:
    void setupUi();
};
