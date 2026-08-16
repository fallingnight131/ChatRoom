import type { V2WebSocketTransport } from "../protocol/v2/webSocketTransport";
import type { WebPushHttpCredentialLease } from "./webPushSubscriptionHttpApi";

/** Keeps bearer/CSRF ownership inside the V2 transport and one HTTP callback. */
export function createV2WebPushHttpCredentialLease(
  transport: Pick<V2WebSocketTransport, "withWebPushHttpCredential">,
): WebPushHttpCredentialLease {
  return {
    withCredentials: action => transport.withWebPushHttpCredential(action),
  };
}
