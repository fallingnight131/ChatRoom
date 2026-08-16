import { nextTick, reactive, ref } from "vue";

export function useKeyboardContextMenu<T>() {
  const menu = reactive<{ show: boolean; x: number; y: number; item: T | null }>({
    show: false,
    x: 0,
    y: 0,
    item: null,
  });
  const menuRef = ref<HTMLElement | null>(null);
  let invoker: HTMLElement | null = null;

  function open(item: T, source: HTMLElement, x: number, y: number): void {
    menu.show = true;
    menu.x = x;
    menu.y = y;
    menu.item = item;
    invoker = source;
    nextTick(() => menuRef.value?.querySelector<HTMLElement>('[role="menuitem"]')?.focus());
  }

  function openFromPointer(event: MouseEvent, item: T): void {
    const source = event.currentTarget;
    if (!(source instanceof HTMLElement)) return;
    open(item, source, event.clientX, event.clientY);
  }

  function openFromKeyboard(event: KeyboardEvent, item: T): boolean {
    if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return false;
    const source = event.currentTarget;
    if (!(source instanceof HTMLElement)) return false;
    event.preventDefault();
    const rect = source.getBoundingClientRect();
    open(item, source, rect.left + 12, rect.top + 12);
    return true;
  }

  function close(restoreFocus = false): void {
    menu.show = false;
    menu.item = null;
    const restoreTarget = invoker;
    invoker = null;
    if (restoreFocus) nextTick(() => restoreTarget?.focus());
  }

  function dismiss(): void {
    close(false);
  }

  function onKeydown(event: KeyboardEvent): void {
    const items = [...(menuRef.value?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? [])];
    if (event.key === "Escape") {
      event.preventDefault();
      close(true);
      return;
    }
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key) || items.length === 0) return;
    event.preventDefault();
    const current = items.indexOf(document.activeElement as HTMLElement);
    let next = 0;
    if (event.key === "End") next = items.length - 1;
    else if (event.key === "ArrowUp") next = current <= 0 ? items.length - 1 : current - 1;
    else if (event.key === "ArrowDown") next = current < 0 || current === items.length - 1 ? 0 : current + 1;
    items[next]?.focus();
  }

  return { menu, menuRef, openFromPointer, openFromKeyboard, close, dismiss, onKeydown };
}
