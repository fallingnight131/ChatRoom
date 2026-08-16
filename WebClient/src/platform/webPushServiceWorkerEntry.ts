import { installWebPushServiceWorker, type WebPushServiceWorkerScope } from "./webPushServiceWorker";
import { loadWebPushGenericCopy } from "./webPushLocale";

const workerScope = self as unknown as WebPushServiceWorkerScope & { location: { origin: string } };

installWebPushServiceWorker(workerScope,
  () => loadWebPushGenericCopy(workerScope.location.origin),
  workerScope.location.origin);
