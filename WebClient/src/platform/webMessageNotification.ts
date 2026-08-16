export interface WebMessageNotificationCandidate {
  messageId: string;
  conversationId: string;
  senderAccountId: string;
  authenticatedAccountId: string;
  authenticatedAccountMentioned: boolean;
}

export interface WebMessageNotificationVisibility {
  applicationActive: boolean;
  visibleConversationId: string;
}

export type WebMessageNotificationKind = "message" | "mention";

export interface WebMessageNotificationDecision {
  show: boolean;
  kind?: WebMessageNotificationKind;
  conversationId?: string;
  messageId?: string;
}

export interface WebMessageNotificationCopy {
  messageTitle: string;
  mentionTitle: string;
  body: string;
}

export interface WebNotificationHandle {
  onclick: null | (() => void);
  close(): void;
}

export type WebNotificationPermission = "default" | "denied" | "granted";

const canonicalUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export class WebMessageNotificationPolicy {
  private readonly rememberedLimit: number;
  private readonly seenIds = new Set<string>();
  private readonly seenOrder: string[] = [];

  constructor(rememberedLimit = 256) {
    if (!Number.isInteger(rememberedLimit) || rememberedLimit < 1 || rememberedLimit > 4096) {
      throw new Error("invalid Web notification memory bound");
    }
    this.rememberedLimit = rememberedLimit;
  }

  evaluate(
    message: WebMessageNotificationCandidate,
    visibility: WebMessageNotificationVisibility,
  ): WebMessageNotificationDecision {
    if (!validCandidate(message) || message.senderAccountId === message.authenticatedAccountId) {
      return { show: false };
    }
    if (this.seenIds.has(message.messageId)) return { show: false };

    this.seenIds.add(message.messageId);
    this.seenOrder.push(message.messageId);
    while (this.seenOrder.length > this.rememberedLimit) {
      const removed = this.seenOrder.shift();
      if (removed) this.seenIds.delete(removed);
    }

    if (visibility.applicationActive
        && visibility.visibleConversationId === message.conversationId) {
      return { show: false };
    }
    return {
      show: true,
      kind: message.authenticatedAccountMentioned ? "mention" : "message",
      conversationId: message.conversationId,
      messageId: message.messageId,
    };
  }

  clear(): void {
    this.seenIds.clear();
    this.seenOrder.splice(0);
  }

  get rememberedMessageCount(): number {
    return this.seenIds.size;
  }
}

export interface WebMessageNotificationPresenterOptions {
  policy?: WebMessageNotificationPolicy;
  permission(): WebNotificationPermission;
  create(title: string, options: { body: string; tag: string }): WebNotificationHandle;
  activateConversation(conversationId: string): void;
}

export class WebMessageNotificationPresenter {
  private readonly policy: WebMessageNotificationPolicy;
  private readonly permission: WebMessageNotificationPresenterOptions["permission"];
  private readonly create: WebMessageNotificationPresenterOptions["create"];
  private readonly activateConversation: WebMessageNotificationPresenterOptions["activateConversation"];

  constructor(options: WebMessageNotificationPresenterOptions) {
    this.policy = options.policy ?? new WebMessageNotificationPolicy();
    this.permission = options.permission;
    this.create = options.create;
    this.activateConversation = options.activateConversation;
  }

  present(
    message: WebMessageNotificationCandidate,
    visibility: WebMessageNotificationVisibility,
    copy: WebMessageNotificationCopy,
  ): boolean {
    const decision = this.policy.evaluate(message, visibility);
    if (!decision.show || !decision.conversationId || !decision.messageId
        || this.permission() !== "granted") return false;
    try {
      const notification = this.create(
        decision.kind === "mention" ? copy.mentionTitle : copy.messageTitle,
        { body: copy.body, tag: `chat-v2-message-${decision.messageId}` },
      );
      let active = true;
      notification.onclick = () => {
        if (!active) return;
        active = false;
        notification.onclick = null;
        try { notification.close(); } catch { /* platform close is best effort */ }
        try { this.activateConversation(decision.conversationId!); } catch { /* delivery is independent */ }
      };
      return true;
    } catch {
      return false;
    }
  }

  clear(): void {
    this.policy.clear();
  }
}

function validCandidate(message: WebMessageNotificationCandidate): boolean {
  return canonicalUuid.test(message.messageId)
    && canonicalUuid.test(message.conversationId)
    && canonicalUuid.test(message.senderAccountId)
    && canonicalUuid.test(message.authenticatedAccountId);
}
