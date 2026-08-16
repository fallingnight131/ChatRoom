import { installWebPushServiceWorker, type WebPushServiceWorkerScope } from "./webPushServiceWorker";

const workerScope = self as unknown as WebPushServiceWorkerScope & { location: { origin: string } };

installWebPushServiceWorker(workerScope, {
  messageTitle: "New ChatRoom activity",
  mentionTitle: "New ChatRoom mention",
  body: "Open ChatRoom to view it.",
}, workerScope.location.origin);
