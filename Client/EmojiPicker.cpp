#include "EmojiPicker.h"

#include <QGridLayout>
#include <QVBoxLayout>
#include <QPushButton>
#include <QScrollArea>
#include <QLabel>

// 微信风格表情包列表（包含 doge/狗头等高频表情）
static const QStringList EMOJIS = {
    // 第一行：微笑/大笑系列
    "\U0001F604", // 😄 微笑
    "\U0001F603", // 😃 开心
    "\U0001F600", // 😀 呲牙
    "\U0001F602", // 😂 笑哭
    "\U0001F605", // 😅 苦笑
    "\U0001F60A", // 😊 害羞
    "\U0001F609", // 😉 眨眼
    "\U0001F60D", // 😍 花痴

    // 第二行：卖萌/搞怪
    "\U0001F61C", // 😜 调皮
    "\U0001F61D", // 😝 吐舌
    "\U0001F60B", // 😋 好吃
    "\U0001F60E", // 😎 墨镜
    "\U0001F913", // 🤓 书呆子
    "\U0001F929", // 🤩 好看
    "\U0001F970", // 🥰 喜欢
    "\U0001F618", // 😘 亲亲

    // 第三行：思考/无语
    "\U0001F914", // 🤔 思考
    "\U0001F636", // 😶 无语
    "\U0001F611", // 😑 面无表情
    "\U0001F610", // 😐 冷漠
    "\U0001F644", // 🙄 翻白眼
    "\U0001F60F", // 😏 得意
    "\U0001F612", // 😒 不高兴
    "\U0001F624", // 😤 生气

    // 第四行：伤心/惊讶
    "\U0001F622", // 😢 流泪
    "\U0001F62D", // 😭 大哭
    "\U0001F625", // 😥 心疼
    "\U0001F630", // 😰 冷汗
    "\U0001F628", // 😨 害怕
    "\U0001F631", // 😱 吓死了
    "\U0001F632", // 😲 惊讶
    "\U0001F633", // 😳 脸红

    // 第五行：特殊表情
    "\U0001F92D", // 🤭 偷笑
    "\U0001F92B", // 🤫 嘘
    "\U0001F971", // 🥱 打哈欠
    "\U0001F634", // 😴 睡觉
    "\U0001F637", // 😷 口罩
    "\U0001F912", // 🤒 生病
    "\U0001F915", // 🤕 受伤
    "\U0001F922", // 🤢 恶心

    // [doge] 🐶 狗头 — 第六行开头！
    "\U0001F436", // 🐶 doge/狗头
    "\U0001F43A", // 🐺 狼
    "\U0001F431", // 🐱 猫
    "\U0001F42D", // 🐭 老鼠
    "\U0001F430", // 🐰 兔子
    "\U0001F43B", // 🐻 熊
    "\U0001F437", // 🐷 猪
    "\U0001F435", // 🐵 猴

    // 第七行：手势
    "\U0001F44D", // 👍 点赞
    "\U0001F44E", // 👎 踩
    "\U0001F44F", // 👏 鼓掌
    "\U0001F64F", // 🙏 合十
    "\U0001F44A", // 👊 拳头
    "\u270C\uFE0F",// ✌️ 耶
    "\U0001F44C", // 👌 OK
    "\U0001F44B", // 👋 挥手

    // 第八行：爱心
    "\u2764\uFE0F",// ❤️ 红心
    "\U0001F9E1", // 🧡 橙心
    "\U0001F49B", // 💛 黄心
    "\U0001F49A", // 💚 绿心
    "\U0001F499", // 💙 蓝心
    "\U0001F49C", // 💜 紫心
    "\U0001F494", // 💔 心碎
    "\U0001F495", // 💕 双心

    // 第九行：物品/符号
    "\U0001F525", // 🔥 火
    "\U0001F4AF", // 💯 满分
    "\U0001F389", // 🎉 庆祝
    "\U0001F381", // 🎁 礼物
    "\U0001F3B5", // 🎵 音乐
    "\u2728",     // ✨ 闪亮
    "\U0001F48B", // 💋 嘴唇
    "\U0001F4A9", // 💩 便便

    // 第十行：更多表情
    "\U0001F47B", // 👻 幽灵
    "\U0001F480", // 💀 骷髅
    "\U0001F47E", // 👾 外星人
    "\U0001F916", // 🤖 机器人
    "\U0001F921", // 🤡 小丑
    "\U0001F47C", // 👼 天使
    "\U0001F608", // 😈 恶魔
    "\U0001F4A4", // 💤 睡眠

    // 第十一行：食物
    "\U0001F349", // 🍉 西瓜
    "\U0001F353", // 🍓 草莓
    "\U0001F34A", // 🍊 橘子
    "\U0001F34E", // 🍎 苹果
    "\U0001F37B", // 🍻 干杯
    "\U0001F375", // 🍵 茶
    "\U0001F354", // 🍔 汉堡
    "\U0001F370", // 🍰 蛋糕

    // 第十二行：天气/自然
    "\U0001F31E", // 🌞 太阳
    "\U0001F31D", // 🌝 满月脸
    "\U0001F31A", // 🌚 新月脸
    "\u2B50",     // ⭐ 星星
    "\U0001F308", // 🌈 彩虹
    "\U0001F4A7", // 💧 水滴
    "\u2744\uFE0F",// ❄️ 雪花
    "\U0001F342", // 🍂 落叶
};

EmojiPicker::EmojiPicker(QWidget *parent)
    : QWidget(parent, Qt::Popup)
{
    setupUi();
}

void EmojiPicker::setupUi() {
    setFixedSize(380, 340);

    auto *outerLayout = new QVBoxLayout(this);
    outerLayout->setContentsMargins(6, 6, 6, 6);
    outerLayout->setSpacing(0);

    // 标题
    auto *titleLabel = new QLabel("表情");
    titleLabel->setStyleSheet("color: #666; font-size: 12px; padding: 2px 4px;");
    outerLayout->addWidget(titleLabel);

    auto *scrollArea = new QScrollArea;
    scrollArea->setWidgetResizable(true);
    scrollArea->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    scrollArea->setVerticalScrollBarPolicy(Qt::ScrollBarAsNeeded);
    scrollArea->setStyleSheet(
        "QScrollArea { border: none; background: transparent; }"
        "QScrollBar:vertical { width: 6px; background: transparent; }"
        "QScrollBar::handle:vertical { background: #ccc; border-radius: 3px; min-height: 30px; }"
        "QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }"
    );

    auto *container = new QWidget;
    container->setStyleSheet("background: transparent;");
    auto *grid = new QGridLayout(container);
    grid->setSpacing(4);
    grid->setContentsMargins(2, 2, 2, 2);

    const int cols = 8;
    int col = 0, row = 0;

    for (const QString &emoji : EMOJIS) {
        auto *btn = new QPushButton(emoji);
        btn->setFixedSize(42, 42);
        btn->setCursor(Qt::PointingHandCursor);
        btn->setToolTip(emoji);
        // 使用平台原生 Emoji 字体
        QFont emojiFont;
#ifdef Q_OS_WIN
        emojiFont = QFont("Segoe UI Emoji", 20);
#elif defined(Q_OS_MAC)
        emojiFont = QFont("Apple Color Emoji", 20);
#else
        emojiFont = QFont("Noto Color Emoji", 20);
#endif
        btn->setFont(emojiFont);
        btn->setStyleSheet(
            "QPushButton {"
            "  border: 1px solid transparent;"
            "  border-radius: 6px;"
            "  background: transparent;"
            "  padding: 0px;"
            "}"
            "QPushButton:hover {"
            "  background: #e0e0e0;"
            "  border-color: #ccc;"
            "}"
            "QPushButton:pressed {"
            "  background: #d0d0d0;"
            "}"
        );
        connect(btn, &QPushButton::clicked, this, [this, emoji] {
            emit emojiSelected(emoji);
        });
        grid->addWidget(btn, row, col);
        col++;
        if (col >= cols) { col = 0; row++; }
    }

    scrollArea->setWidget(container);
    outerLayout->addWidget(scrollArea);

    // 整体样式
    setStyleSheet(
        "EmojiPicker {"
        "  background: white;"
        "  border: 1px solid #d0d0d0;"
        "  border-radius: 8px;"
        "}"
    );
}
