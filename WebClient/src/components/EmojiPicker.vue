<template>
  <div class="emoji-picker" @click.stop>
    <div class="emoji-grid">
      <span v-for="(emoji, idx) in emojis" :key="idx"
            class="emoji-item" @click="$emit('select', emoji)">
        {{ emoji }}
      </span>
    </div>
  </div>
</template>

<script setup>
defineEmits(['select', 'close'])

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
  border-radius: 6px;
  transition: background 0.15s;
}
.emoji-item:hover {
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
    grid-template-columns: repeat(8, 1fr);
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

@media (max-width: 480px) {
  .emoji-grid {
    grid-template-columns: repeat(7, 1fr);
  }
}
</style>
