#pragma once

#include "V2LocalMessageRepository.h"

#include <QList>
#include <QString>

class V2WindowsMentionComposer final {
public:
    struct Anchor {
        QString targetAccountId;
        int startUtf16 = 0;
        int endUtf16 = 0;
    };
    struct Participant { QString accountId; QString displayName; };
    struct EditResult { QString text; QList<Anchor> anchors; int caretUtf16 = 0; };
    struct Segment { QString text; QString targetAccountId; bool mention = false; };

    static EditResult insert(const QString &text, const QList<Anchor> &anchors,
                             int selectionStart, int selectionEnd,
                             const Participant &participant);
    static QList<Anchor> reconcile(const QString &previousText,
                                   const QString &nextText,
                                   const QList<Anchor> &anchors);
    static QList<V2LocalMessageRepository::Mention> serialize(
        const QString &text, const QList<Anchor> &anchors);
    static QList<Anchor> restore(
        const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions);
    static QList<Segment> segments(
        const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions);

private:
    static QList<Anchor> replaceRange(const QList<Anchor> &anchors,
                                      int start, int end, int replacementLength);
    static void requireAnchors(const QString &text, const QList<Anchor> &anchors);
    static bool canonicalUuid(const QString &value);
    static bool utf16Boundary(const QString &text, int index);
};
