#include "V2WindowsMentionComposer.h"

#include <QSet>
#include <QUuid>
#include <algorithm>
#include <stdexcept>

namespace {
constexpr qsizetype MaximumMentions = 20;
constexpr qsizetype MaximumTargets = 10;
}

V2WindowsMentionComposer::EditResult V2WindowsMentionComposer::insert(
        const QString &text, const QList<Anchor> &anchors,
        int selectionStart, int selectionEnd, const Participant &participant) {
    if (!canonicalUuid(participant.accountId) || participant.displayName.trimmed().isEmpty()
            || participant.displayName.toUcs4().size() > 100
            || participant.displayName.toUtf8().size() > 400
            || selectionStart < 0 || selectionEnd < selectionStart
            || selectionEnd > text.size() || !utf16Boundary(text, selectionStart)
            || !utf16Boundary(text, selectionEnd))
        throw std::invalid_argument("invalid mention insertion");
    requireAnchors(text, anchors);
    const QString token = QStringLiteral("@") + participant.displayName;
    const QString insertion = token + QStringLiteral(" ");
    auto shifted = replaceRange(
        anchors, selectionStart, selectionEnd, static_cast<int>(insertion.size()));
    shifted.append({participant.accountId, selectionStart,
                    selectionStart + static_cast<int>(token.size())});
    std::sort(shifted.begin(), shifted.end(), [](const Anchor &left, const Anchor &right) {
        return left.startUtf16 < right.startUtf16;
    });
    const QString result = text.left(selectionStart) + insertion + text.mid(selectionEnd);
    requireAnchors(result, shifted);
    return {result, shifted,
            selectionStart + static_cast<int>(insertion.size())};
}

QList<V2WindowsMentionComposer::Anchor> V2WindowsMentionComposer::reconcile(
        const QString &previousText, const QString &nextText,
        const QList<Anchor> &anchors) {
    requireAnchors(previousText, anchors);
    int prefix = 0;
    const int maximumPrefix = std::min(previousText.size(), nextText.size());
    while (prefix < maximumPrefix && previousText.at(prefix) == nextText.at(prefix)) ++prefix;
    while (prefix > 0 && (!utf16Boundary(previousText, prefix)
            || !utf16Boundary(nextText, prefix))) --prefix;
    int suffix = 0;
    while (suffix < previousText.size() - prefix && suffix < nextText.size() - prefix
            && previousText.at(previousText.size() - 1 - suffix)
                == nextText.at(nextText.size() - 1 - suffix)) ++suffix;
    while (suffix > 0 && (!utf16Boundary(previousText, previousText.size() - suffix)
            || !utf16Boundary(nextText, nextText.size() - suffix))) --suffix;
    const int oldEnd = previousText.size() - suffix;
    auto result = replaceRange(
        anchors, prefix, oldEnd, nextText.size() - prefix - suffix);
    for (auto position = result.begin(); position != result.end();) {
        if (nextText.mid(position->startUtf16,
                         position->endUtf16 - position->startUtf16)
                .startsWith(QLatin1Char('@'))) ++position;
        else position = result.erase(position);
    }
    requireAnchors(nextText, result);
    return result;
}

QList<V2LocalMessageRepository::Mention> V2WindowsMentionComposer::serialize(
        const QString &text, const QList<Anchor> &anchors) {
    requireAnchors(text, anchors);
    QList<V2LocalMessageRepository::Mention> result;
    for (const auto &anchor : anchors) {
        const QString token = text.mid(
            anchor.startUtf16, anchor.endUtf16 - anchor.startUtf16);
        if (token.size() < 2 || !token.startsWith(QLatin1Char('@')))
            throw std::invalid_argument("stale mention anchor");
        result.append({anchor.targetAccountId,
            static_cast<int>(text.left(anchor.startUtf16).toUtf8().size()),
            static_cast<int>(token.toUtf8().size())});
    }
    return result;
}

QList<V2WindowsMentionComposer::Anchor> V2WindowsMentionComposer::restore(
        const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    if (mentions.size() > MaximumMentions) throw std::invalid_argument("too many mentions");
    const QByteArray bytes = text.toUtf8();
    QList<Anchor> result;
    int previousEnd = 0;
    QSet<QString> targets;
    for (const auto &mention : mentions) {
        if (!canonicalUuid(mention.targetAccountId) || mention.lengthUtf8Bytes <= 0
                || mention.startUtf8Byte < previousEnd || mention.startUtf8Byte >= bytes.size()
                || mention.lengthUtf8Bytes > bytes.size() - mention.startUtf8Byte)
            throw std::invalid_argument("invalid mention span");
        const int end = mention.startUtf8Byte + mention.lengthUtf8Bytes;
        const auto boundary = [&](int index) {
            return index == 0 || index == bytes.size()
                || (static_cast<unsigned char>(bytes.at(index)) & 0xc0U) != 0x80U;
        };
        if (!boundary(mention.startUtf8Byte) || !boundary(end)
                || bytes.at(mention.startUtf8Byte) != '@')
            throw std::invalid_argument("invalid mention UTF-8 boundary");
        targets.insert(mention.targetAccountId);
        if (targets.size() > MaximumTargets)
            throw std::invalid_argument("too many mention targets");
        result.append({mention.targetAccountId,
            static_cast<int>(QString::fromUtf8(
                bytes.constData(), mention.startUtf8Byte).size()),
            static_cast<int>(QString::fromUtf8(bytes.constData(), end).size())});
        previousEnd = end;
    }
    requireAnchors(text, result);
    return result;
}

QList<V2WindowsMentionComposer::Segment> V2WindowsMentionComposer::segments(
        const QString &text,
        const QList<V2LocalMessageRepository::Mention> &mentions) {
    QList<Segment> result;
    try {
        const auto anchors = restore(text, mentions);
        int cursor = 0;
        for (const auto &anchor : anchors) {
            if (anchor.startUtf16 > cursor)
                result.append({text.mid(cursor, anchor.startUtf16 - cursor), {}, false});
            result.append({text.mid(anchor.startUtf16,
                anchor.endUtf16 - anchor.startUtf16), anchor.targetAccountId, true});
            cursor = anchor.endUtf16;
        }
        if (cursor < text.size() || result.isEmpty()) result.append({text.mid(cursor), {}, false});
    } catch (...) {
        result = {{text, {}, false}};
    }
    return result;
}

QList<V2WindowsMentionComposer::Anchor> V2WindowsMentionComposer::replaceRange(
        const QList<Anchor> &anchors, int start, int end, int replacementLength) {
    QList<Anchor> result;
    const int delta = replacementLength - (end - start);
    for (const auto &anchor : anchors) {
        if (anchor.endUtf16 <= start) result.append(anchor);
        else if (anchor.startUtf16 >= end)
            result.append({anchor.targetAccountId,
                anchor.startUtf16 + delta, anchor.endUtf16 + delta});
    }
    return result;
}

void V2WindowsMentionComposer::requireAnchors(
        const QString &text, const QList<Anchor> &anchors) {
    if (anchors.size() > MaximumMentions) throw std::invalid_argument("too many anchors");
    int previousEnd = 0;
    QSet<QString> targets;
    for (const auto &anchor : anchors) {
        if (!canonicalUuid(anchor.targetAccountId) || anchor.startUtf16 < previousEnd
                || anchor.endUtf16 <= anchor.startUtf16 || anchor.endUtf16 > text.size()
                || !utf16Boundary(text, anchor.startUtf16)
                || !utf16Boundary(text, anchor.endUtf16))
            throw std::invalid_argument("invalid mention anchor");
        targets.insert(anchor.targetAccountId);
        if (targets.size() > MaximumTargets)
            throw std::invalid_argument("too many mention targets");
        previousEnd = anchor.endUtf16;
    }
}

bool V2WindowsMentionComposer::canonicalUuid(const QString &value) {
    const QUuid uuid(value);
    return !uuid.isNull() && uuid.toString(QUuid::WithoutBraces) == value;
}

bool V2WindowsMentionComposer::utf16Boundary(const QString &text, int index) {
    return index >= 0 && index <= text.size()
        && (index == 0 || index == text.size()
            || !(text.at(index - 1).isHighSurrogate() && text.at(index).isLowSurrogate()));
}
