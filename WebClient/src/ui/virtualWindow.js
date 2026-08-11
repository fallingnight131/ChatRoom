export const DEFAULT_VIRTUALIZATION_THRESHOLD = 80

export function calculateVirtualWindow({
  heights,
  scrollTop,
  viewportHeight,
  overscan = 600,
  threshold = DEFAULT_VIRTUALIZATION_THRESHOLD
}) {
  const normalized = Array.isArray(heights)
    ? heights.map(height => Math.max(1, Number(height) || 1))
    : []
  const count = normalized.length
  const totalHeight = normalized.reduce((sum, height) => sum + height, 0)
  if (count <= threshold) {
    return { start: 0, end: count, top: 0, bottom: 0, totalHeight }
  }

  const normalizedViewport = Math.max(0, Number(viewportHeight) || 0)
  const effectiveScrollTop = Math.min(
    Math.max(0, Number(scrollTop) || 0),
    Math.max(0, totalHeight - normalizedViewport))
  const visibleStart = Math.max(0, effectiveScrollTop - overscan)
  const visibleEnd = Math.max(visibleStart,
    effectiveScrollTop + normalizedViewport + overscan)
  let start = 0
  let top = 0
  while (start < count && top + normalized[start] < visibleStart) {
    top += normalized[start]
    start += 1
  }

  let end = start
  let renderedHeight = 0
  while (end < count && top + renderedHeight < visibleEnd) {
    renderedHeight += normalized[end]
    end += 1
  }
  if (end === start && end < count) {
    renderedHeight += normalized[end]
    end += 1
  }
  return {
    start,
    end,
    top,
    bottom: Math.max(0, totalHeight - top - renderedHeight),
    totalHeight
  }
}
