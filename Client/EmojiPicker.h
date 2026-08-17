#pragma once

#include "WindowsLocaleCatalog.h"

#include <QList>
#include <QWidget>

class QGridLayout;
class QLabel;
class QPushButton;
class WindowsLocaleViewModel;

/// 表情选择器 —— 弹出式表情面板
class EmojiPicker : public QWidget {
    Q_OBJECT
public:
    explicit EmojiPicker(QWidget *parent = nullptr,
                         WindowsLocaleViewModel *localeViewModel = nullptr);
    QLabel *titleForTest() const { return m_title; }
    QList<QPushButton *> buttonsForTest() const { return m_buttons; }

signals:
    void emojiSelected(const QString &emoji);

private:
    void setupUi();
    void applyLocale();

    WindowsLocaleViewModel *m_localeViewModel = nullptr;
    WindowsLocale m_locale = WindowsLocale::ZhCn;
    QLabel *m_title = nullptr;
    QList<QPushButton *> m_buttons;
};
