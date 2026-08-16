import { nextTick, onMounted, onUnmounted, ref } from "vue";

const FOCUSABLE_SELECTOR = [
  "button:not([disabled])",
  "input:not([disabled]):not([tabindex='-1'])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "a[href]",
  "[tabindex]:not([tabindex='-1'])",
].join(",");

export type ModalLoopTarget = "first" | "last" | null;

export function modalLoopTarget(
  focusable: readonly unknown[],
  activeElement: unknown,
  shiftKey: boolean,
): ModalLoopTarget {
  if (focusable.length === 0) return null;
  const activeIndex = focusable.indexOf(activeElement);
  if (shiftKey && activeIndex <= 0) return "last";
  if (!shiftKey && (activeIndex < 0 || activeIndex === focusable.length - 1)) return "first";
  return null;
}

export function useModalKeyboardBoundary({
  onClose,
  canClose = () => true,
  initialFocusSelector = "",
}: {
  onClose: () => void;
  canClose?: () => boolean;
  initialFocusSelector?: string;
}) {
  const dialogRef = ref<HTMLElement | null>(null);
  let previousFocus: { focus: () => void } | null = null;

  function closeDialog(): boolean {
    if (!canClose()) return false;
    onClose();
    return true;
  }

  function onDialogKeydown(event: KeyboardEvent): void {
    if (event.key === "Escape") {
      if (closeDialog()) event.preventDefault();
      return;
    }
    if (event.key !== "Tab") return;
    const focusable = Array.from(
      dialogRef.value?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR) ?? [],
    );
    if (focusable.length === 0) {
      event.preventDefault();
      return;
    }
    const target = modalLoopTarget(focusable, document.activeElement, event.shiftKey);
    if (target === null) return;
    event.preventDefault();
    (target === "first" ? focusable[0] : focusable[focusable.length - 1])?.focus();
  }

  onMounted(() => {
    const active = document.activeElement as { focus?: () => void } | null;
    previousFocus = typeof active?.focus === "function" ? { focus: () => active.focus?.() } : null;
    nextTick(() => {
      const initial = initialFocusSelector
        ? dialogRef.value?.querySelector<HTMLElement>(initialFocusSelector)
        : null;
      (initial ?? dialogRef.value)?.focus();
    });
  });
  onUnmounted(() => previousFocus?.focus());

  return { dialogRef, closeDialog, onDialogKeydown };
}
