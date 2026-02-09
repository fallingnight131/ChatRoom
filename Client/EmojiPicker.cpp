#include "EmojiPicker.h"

#include <QGridLayout>
#include <QPushButton>
#include <QScrollArea>

static const QStringList EMOJIS = {
    "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂",
    "🙂", "🙃", "😉", "😊", "😇", "🥰", "😍", "🤩",
    "😘", "😗", "😚", "😙", "🥲", "😋", "😛", "😜",
    "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "🫡",
    "🤐", "🤨", "😐", "😑", "😶", "🫥", "😏", "😒",
    "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤", "😴",
    "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "🥵", "🥶",
    "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐",
    "😕", "🫤", "😟", "🙁", "😮", "😯", "😲", "😳",
    "🥺", "🥹", "😦", "😧", "😨", "😰", "😥", "😢",
    "😭", "😱", "😖", "😣", "😞", "😓", "😩", "😫",
    "🥱", "😤", "😡", "😠", "🤬", "😈", "👿", "💀",
    "👍", "👎", "👏", "🤝", "👊", "✊", "🤞", "✌️",
    "🤟", "🤘", "👌", "🤌", "🤏", "👈", "👉", "👆",
    "👇", "☝️", "✋", "🤚", "🖐️", "🖖", "👋", "🤙",
    "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
    "💔", "💕", "💞", "💓", "💗", "💖", "💘", "💝",
    "⭐", "🌟", "✨", "💫", "🔥", "💯", "🎉", "🎊",
    "😺", "😸", "😹", "😻", "😼", "😽", "🙀", "😿",
};

EmojiPicker::EmojiPicker(QWidget *parent)
    : QWidget(parent, Qt::Popup)
{
    setupUi();
}

void EmojiPicker::setupUi() {
    setFixedSize(360, 280);
    setStyleSheet("background: white; border: 1px solid #ccc; border-radius: 6px;");

    auto *scrollArea = new QScrollArea(this);
    scrollArea->setWidgetResizable(true);
    scrollArea->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);

    auto *container = new QWidget;
    auto *grid = new QGridLayout(container);
    grid->setSpacing(2);
    grid->setContentsMargins(4, 4, 4, 4);

    int col = 0, row = 0;
    const int cols = 8;

    for (const QString &emoji : EMOJIS) {
        auto *btn = new QPushButton(emoji);
        btn->setFixedSize(38, 38);
        btn->setFont(QFont("Segoe UI Emoji", 16));
        btn->setStyleSheet("QPushButton { border: none; border-radius: 4px; }"
                           "QPushButton:hover { background: #e8e8e8; }");
        connect(btn, &QPushButton::clicked, [this, emoji] {
            emit emojiSelected(emoji);
        });
        grid->addWidget(btn, row, col);
        col++;
        if (col >= cols) { col = 0; row++; }
    }

    scrollArea->setWidget(container);

    auto *layout = new QGridLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->addWidget(scrollArea);
}
