#include "V2WindowsMentionComposer.h"

#include <QCoreApplication>
#include <QDebug>

namespace {
int failures = 0;
void check(bool value, const QString &message) {
    if (!value) { ++failures; qCritical().noquote() << message; }
}
template <typename Action>
void checkThrows(Action action, const QString &message) {
    try { action(); check(false, message); } catch (...) {}
}
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    const QString alice = QStringLiteral("10000000-0000-4000-8000-000000000001");
    const QString bob = QStringLiteral("10000000-0000-4000-8000-000000000002");
    const QString initial = QString::fromUtf8("你好 😀 world");
    const int insertionPoint = QString::fromUtf8("你好 😀 ").size();
    const auto inserted = V2WindowsMentionComposer::insert(initial, {},
        insertionPoint, insertionPoint, {alice, QStringLiteral("张三😀")});
    check(inserted.text == QString::fromUtf8("你好 😀 @张三😀 world")
              && inserted.anchors.size() == 1
              && inserted.caretUtf16 == inserted.anchors.first().endUtf16 + 1,
          QStringLiteral("Unicode insertion did not preserve caret and anchor positions"));

    const auto spans = V2WindowsMentionComposer::serialize(
        inserted.text, inserted.anchors);
    const QByteArray prefix = QString::fromUtf8("你好 😀 ").toUtf8();
    const QByteArray token = QString::fromUtf8("@张三😀").toUtf8();
    check(spans.size() == 1 && spans.first().targetAccountId == alice
              && spans.first().startUtf8Byte == prefix.size()
              && spans.first().lengthUtf8Bytes == token.size(),
          QStringLiteral("UTF-16 anchors did not serialize to exact UTF-8 spans"));
    const auto restored = V2WindowsMentionComposer::restore(inserted.text, spans);
    check(restored.size() == 1
              && restored.first().startUtf16 == inserted.anchors.first().startUtf16
              && restored.first().endUtf16 == inserted.anchors.first().endUtf16,
          QStringLiteral("stored UTF-8 spans did not restore editor anchors"));

    const auto prefixed = V2WindowsMentionComposer::reconcile(
        inserted.text, QStringLiteral("X") + inserted.text, inserted.anchors);
    check(prefixed.size() == 1
              && prefixed.first().startUtf16 == inserted.anchors.first().startUtf16 + 1,
          QStringLiteral("non-overlapping edit did not shift mention anchor"));
    QString changed = inserted.text;
    changed.replace(inserted.anchors.first().startUtf16 + 1, 1, QStringLiteral("李"));
    check(V2WindowsMentionComposer::reconcile(
              inserted.text, changed, inserted.anchors).isEmpty(),
          QStringLiteral("editing visible mention text did not invalidate identity"));

    const auto second = V2WindowsMentionComposer::insert(inserted.text, inserted.anchors,
        inserted.text.size(), inserted.text.size(), {bob, QStringLiteral("李四")});
    const auto segments = V2WindowsMentionComposer::segments(
        second.text, V2WindowsMentionComposer::serialize(second.text, second.anchors));
    int mentionSegments = 0;
    for (const auto &segment : segments) if (segment.mention) ++mentionSegments;
    check(mentionSegments == 2 && segments.first().text.startsWith(QStringLiteral("你好")),
          QStringLiteral("identity-preserving rendering did not segment two mentions"));

    checkThrows([&] {
        V2WindowsMentionComposer::restore(QString::fromUtf8("@张三"), {{alice, 0, 2}});
    }, QStringLiteral("UTF-8 span splitting a Chinese scalar was accepted"));
    checkThrows([&] {
        V2WindowsMentionComposer::insert(QString::fromUtf8("😀"), {}, 1, 1,
                                         {alice, QStringLiteral("张三")});
    }, QStringLiteral("selection splitting a surrogate pair was accepted"));

    if (failures) return 1;
    qInfo() << "[V2WindowsMentionComposerTest] PASS";
    return 0;
}
