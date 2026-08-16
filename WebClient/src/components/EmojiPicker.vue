<template>
  <div id="emoji-picker" class="emoji-picker" role="dialog"
       aria-label="选择要发送的表情" @click.stop @keydown.esc.stop.prevent="emit('close')">
    <div class="emoji-grid" role="grid" aria-label="表情">
      <div v-for="(row, rowIndex) in emojiRows" :key="rowIndex"
           class="emoji-row" role="row">
        <button v-for="(emoji, columnIndex) in row" :key="columnIndex"
                :ref="element => setEmojiButton(element, emojiIndex(rowIndex, columnIndex))"
                type="button" role="gridcell" class="emoji-item"
                :tabindex="emojiIndex(rowIndex, columnIndex) === activeIndex ? 0 : -1"
                :aria-label="`发送表情 ${emoji}`"
                @focus="activeIndex = emojiIndex(rowIndex, columnIndex)"
                @keydown="onEmojiKeydown($event, emojiIndex(rowIndex, columnIndex))"
                @click="emit('select', emoji)">
          {{ emoji }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'

const emit = defineEmits(['select', 'close'])
const activeIndex = ref(0)
const emojiButtons = []
const COLUMN_COUNT = 8

// 96个表情 (8×12), 与Qt客户端完全一致
const emojis = [
  // 行1：微笑系列
  '😄', '😃', '😀', '😂', '😅', '😊', '😉', '😍',
  // 行2：搞怪系列
  '😜', '😝', '😋', '😎', '🤓', '🤩', '🥰', '😘',
  // 行3：思考/无语
  '🤔', '😶', '😑', '😐', '🙄', '😏', '😒', '😤',
  // 行4：伤心/惊讶
  '😢', '😭', '😥', '😰', '😨', '😱', '😲', '😳',
  // 行5：特殊表情
  '🤭', '🤫', '🥱', '😴', '😷', '🤒', '🤕', '🤢',
  // 行6：动物
  '🐶', '🐺', '🐱', '🐭', '🐰', '🐻', '🐷', '🐵',
  // 行7：手势
  '👍', '👎', '👏', '🙏', '👊', '✌️', '👌', '👋',
  // 行8：爱心
  '❤️', '🧡', '💛', '💚', '💙', '💜', '💔', '💕',
  // 行9：物品/符号
  '🔥', '💯', '🎉', '🎁', '🎵', '✨', '💋', '💩',
  // 行10：更多表情
  '👻', '💀', '👾', '🤖', '🤡', '👼', '😈', '💤',
  // 行11：食物
  '🍉', '🍓', '🍊', '🍎', '🍻', '🍵', '🍔', '🍰',
  // 行12：天气/自然
  '🌞', '🌝', '🌚', '⭐', '🌈', '💧', '❄️', '🍂',
]
const emojiRows = Array.from(
  { length: Math.ceil(emojis.length / COLUMN_COUNT) },
  (_, rowIndex) => emojis.slice(rowIndex * COLUMN_COUNT, (rowIndex + 1) * COLUMN_COUNT),
)

function emojiIndex(rowIndex, columnIndex) {
  return rowIndex * COLUMN_COUNT + columnIndex
}

function setEmojiButton(element, index) {
  if (element) emojiButtons[index] = element
}

function focusEmoji(index) {
  const boundedIndex = Math.max(0, Math.min(index, emojis.length - 1))
  activeIndex.value = boundedIndex
  nextTick(() => emojiButtons[boundedIndex]?.focus())
}

function onEmojiKeydown(event, index) {
  const rowStart = Math.floor(index / COLUMN_COUNT) * COLUMN_COUNT
  const rowEnd = Math.min(rowStart + COLUMN_COUNT - 1, emojis.length - 1)
  const destinations = {
    ArrowRight: index === rowEnd ? rowStart : index + 1,
    ArrowLeft: index === rowStart ? rowEnd : index - 1,
    ArrowDown: Math.min(index + COLUMN_COUNT, emojis.length - 1),
    ArrowUp: Math.max(index - COLUMN_COUNT, 0),
    Home: event.ctrlKey ? 0 : rowStart,
    End: event.ctrlKey ? emojis.length - 1 : rowEnd,
  }
  if (!(event.key in destinations)) return
  event.preventDefault()
  focusEmoji(destinations[event.key])
}

onMounted(() => focusEmoji(0))
</script>

<style scoped>
.emoji-picker {
  position: absolute;
  bottom: 100%;
  left: 0;
  background: var(--bg-secondary);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  padding: 12px;
  z-index: 100;
  margin-bottom: 4px;
}
.emoji-grid {
  display: grid;
  gap: 2px;
}
.emoji-row {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 2px;
}
.emoji-item {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  cursor: pointer;
  border: 0;
  background: transparent;
  border-radius: 6px;
  transition: background 0.15s;
}
.emoji-item:hover,
.emoji-item:focus-visible {
  background: var(--bg-hover);
  transform: scale(1.2);
}

/* 移动端：底部弹出全宽 */
@media (max-width: 768px) {
  .emoji-picker {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    top: auto;
    max-height: 50vh;
    overflow-y: auto;
    border-radius: 16px 16px 0 0;
    padding: 16px;
    padding-bottom: max(16px, env(safe-area-inset-bottom));
    margin-bottom: 0;
    animation: slideUp 0.25s;
    z-index: 200;
  }
  .emoji-grid {
    gap: 4px;
  }
  .emoji-row {
    gap: 4px;
  }
  .emoji-item {
    width: auto;
    height: 40px;
    font-size: 24px;
  }
  .emoji-item:hover {
    transform: none;
  }
  .emoji-item:active {
    background: var(--bg-hover);
    transform: scale(1.15);
  }
}

</style>
