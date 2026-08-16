export const LOW_BANDWIDTH_STORAGE_KEY = "lowBandwidthMode";

export type BandwidthPreferenceSource = "user" | "browser" | "default" | "session";

export interface BandwidthPreference {
  enabled: boolean;
  source: BandwidthPreferenceSource;
}

interface PreferenceStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
}

interface NetworkInformationLike {
  saveData?: boolean;
}

export function resolveBandwidthPreference(
  storage: PreferenceStorage | null,
  connection: NetworkInformationLike | null,
): BandwidthPreference {
  let stored: string | null = null;
  try { stored = storage?.getItem(LOW_BANDWIDTH_STORAGE_KEY) ?? null; } catch { /* session fallback */ }
  if (stored === "true" || stored === "false") {
    return { enabled: stored === "true", source: "user" };
  }
  if (connection?.saveData === true) return { enabled: true, source: "browser" };
  return { enabled: false, source: "default" };
}

export function persistBandwidthPreference(
  storage: PreferenceStorage | null,
  enabled: boolean,
): boolean {
  try {
    if (!storage) return false;
    storage.setItem(LOW_BANDWIDTH_STORAGE_KEY, String(enabled));
    return true;
  } catch {
    return false;
  }
}

export function shouldAutoRequestAvatar(
  username: string,
  lowBandwidthMode: boolean,
  hasCachedEntry: boolean,
): boolean {
  return username.length > 0 && !lowBandwidthMode && !hasCachedEntry;
}
