import type { V2ConversationMention } from "./v2WebChatApplication";

const MAX_MENTIONS = 20;
const MAX_TARGETS = 10;
const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const encoder = new TextEncoder();
const decoder = new TextDecoder("utf-8", { fatal: true });

export interface MentionAnchor {
  targetAccountId: string;
  startUtf16: number;
  endUtf16: number;
}

export interface MentionParticipant {
  accountId: string;
  displayName: string;
}

export type MentionSegment =
  | { kind: "text"; text: string }
  | { kind: "mention"; text: string; targetAccountId: string };

export function insertMention(
  text: string,
  anchors: readonly MentionAnchor[],
  selectionStart: number,
  selectionEnd: number,
  participant: MentionParticipant,
): { text: string; anchors: MentionAnchor[]; caretUtf16: number } {
  if (!canonicalUuid.test(participant.accountId)
      || !participant.displayName.trim()
      || [...participant.displayName].length > 100
      || encoder.encode(participant.displayName).byteLength > 400) {
    throw new Error("invalid mention participant");
  }
  requireSelection(text, selectionStart, selectionEnd);
  const token = `@${participant.displayName}`;
  const insertion = `${token} `;
  const shifted = replaceRange(anchors, selectionStart, selectionEnd, insertion.length);
  shifted.push({
    targetAccountId: participant.accountId,
    startUtf16: selectionStart,
    endUtf16: selectionStart + token.length,
  });
  shifted.sort((left, right) => left.startUtf16 - right.startUtf16);
  requireAnchorBounds(shifted);
  return {
    text: text.slice(0, selectionStart) + insertion + text.slice(selectionEnd),
    anchors: shifted,
    caretUtf16: selectionStart + insertion.length,
  };
}

export function reconcileMentionEdit(
  previousText: string,
  nextText: string,
  anchors: readonly MentionAnchor[],
): MentionAnchor[] {
  let prefix = 0;
  const maximumPrefix = Math.min(previousText.length, nextText.length);
  while (prefix < maximumPrefix && previousText[prefix] === nextText[prefix]) prefix += 1;
  let suffix = 0;
  while (suffix < previousText.length - prefix && suffix < nextText.length - prefix
      && previousText[previousText.length - 1 - suffix]
        === nextText[nextText.length - 1 - suffix]) suffix += 1;
  const oldEnd = previousText.length - suffix;
  const replacementLength = nextText.length - prefix - suffix;
  return replaceRange(anchors, prefix, oldEnd, replacementLength).filter((anchor) =>
    nextText.slice(anchor.startUtf16, anchor.endUtf16).startsWith("@"));
}

export function serializeMentionAnchors(
  text: string,
  anchors: readonly MentionAnchor[],
): V2ConversationMention[] {
  requireAnchorBounds(anchors);
  return anchors.map((anchor) => {
    const token = text.slice(anchor.startUtf16, anchor.endUtf16);
    if (!token.startsWith("@") || token.length < 2) throw new Error("stale mention anchor");
    return {
      targetAccountId: anchor.targetAccountId,
      startUtf8Byte: encoder.encode(text.slice(0, anchor.startUtf16)).byteLength,
      lengthUtf8Bytes: encoder.encode(token).byteLength,
    };
  });
}

export function anchorsFromMentionSpans(
  text: string,
  mentions: readonly V2ConversationMention[],
): MentionAnchor[] {
  const offsets = utf8BoundaryMap(text);
  return mentions.map((mention) => {
    const startUtf16 = offsets.get(mention.startUtf8Byte);
    const endUtf16 = offsets.get(mention.startUtf8Byte + mention.lengthUtf8Bytes);
    if (startUtf16 === undefined || endUtf16 === undefined) {
      throw new Error("mention span is not on a UTF-8 boundary");
    }
    return { targetAccountId: mention.targetAccountId, startUtf16, endUtf16 };
  });
}

export function segmentMentionText(
  text: string,
  mentions: readonly V2ConversationMention[],
): MentionSegment[] {
  const bytes = encoder.encode(text);
  const segments: MentionSegment[] = [];
  let cursor = 0;
  for (const mention of mentions) {
    const end = mention.startUtf8Byte + mention.lengthUtf8Bytes;
    if (mention.startUtf8Byte < cursor || end > bytes.length) return [{ kind: "text", text }];
    if (mention.startUtf8Byte > cursor) {
      segments.push({ kind: "text", text: decoder.decode(bytes.slice(cursor, mention.startUtf8Byte)) });
    }
    segments.push({
      kind: "mention",
      text: decoder.decode(bytes.slice(mention.startUtf8Byte, end)),
      targetAccountId: mention.targetAccountId,
    });
    cursor = end;
  }
  if (cursor < bytes.length || segments.length === 0) {
    segments.push({ kind: "text", text: decoder.decode(bytes.slice(cursor)) });
  }
  return segments;
}

function replaceRange(
  anchors: readonly MentionAnchor[],
  start: number,
  end: number,
  replacementLength: number,
): MentionAnchor[] {
  const delta = replacementLength - (end - start);
  return anchors.flatMap((anchor) => {
    if (anchor.endUtf16 <= start) return [{ ...anchor }];
    if (anchor.startUtf16 >= end) return [{
      ...anchor,
      startUtf16: anchor.startUtf16 + delta,
      endUtf16: anchor.endUtf16 + delta,
    }];
    return [];
  });
}

function requireSelection(text: string, start: number, end: number): void {
  if (!Number.isInteger(start) || !Number.isInteger(end)
      || start < 0 || end < start || end > text.length) {
    throw new Error("invalid mention insertion selection");
  }
}

function requireAnchorBounds(anchors: readonly MentionAnchor[]): void {
  if (anchors.length > MAX_MENTIONS) throw new Error("too many mention spans");
  const targets = new Set<string>();
  let previousEnd = 0;
  for (const anchor of anchors) {
    if (!canonicalUuid.test(anchor.targetAccountId)
        || !Number.isInteger(anchor.startUtf16) || !Number.isInteger(anchor.endUtf16)
        || anchor.startUtf16 < previousEnd || anchor.endUtf16 <= anchor.startUtf16) {
      throw new Error("invalid mention anchor");
    }
    targets.add(anchor.targetAccountId);
    if (targets.size > MAX_TARGETS) throw new Error("too many mention targets");
    previousEnd = anchor.endUtf16;
  }
}

function utf8BoundaryMap(text: string): Map<number, number> {
  const result = new Map<number, number>([[0, 0]]);
  let utf8Offset = 0;
  let utf16Offset = 0;
  for (const character of text) {
    utf8Offset += encoder.encode(character).byteLength;
    utf16Offset += character.length;
    result.set(utf8Offset, utf16Offset);
  }
  return result;
}
