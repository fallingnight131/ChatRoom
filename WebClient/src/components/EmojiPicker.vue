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
</style>
