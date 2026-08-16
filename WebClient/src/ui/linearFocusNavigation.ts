export type LinearFocusKey = "ArrowDown" | "ArrowUp" | "Home" | "End";

export function nextWrappingFocusIndex(
  key: LinearFocusKey,
  currentIndex: number,
  itemCount: number,
): number | null {
  if (!Number.isSafeInteger(itemCount) || itemCount <= 0) return null;
  if (key === "Home") return 0;
  if (key === "End") return itemCount - 1;
  const current = Number.isSafeInteger(currentIndex)
    && currentIndex >= 0 && currentIndex < itemCount
    ? currentIndex
    : key === "ArrowUp" ? 0 : -1;
  return key === "ArrowDown"
    ? (current + 1) % itemCount
    : (current - 1 + itemCount) % itemCount;
}
