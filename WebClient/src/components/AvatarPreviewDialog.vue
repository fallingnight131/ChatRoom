<template>
  <div class="avatar-preview-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="avatar-preview-card" role="dialog" aria-modal="true"
         aria-labelledby="avatar-preview-title" tabindex="-1"
         @keydown.stop="onDialogKeydown">
      <h2 id="avatar-preview-title" class="visually-hidden">头像预览</h2>
      <img :src="src" class="avatar-preview-image" :alt="alt" />
      <div class="avatar-preview-actions">
        <button class="btn btn-secondary" type="button" @click="closeDialog">关闭预览</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'

defineProps({
  src: { type: String, required: true },
  alt: { type: String, default: '用户头像大图' }
})

const emit = defineEmits(['close'])
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => emit('close')
})
</script>

<style scoped>
.avatar-preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  z-index: 1000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}
.avatar-preview-card {
  max-width: 92vw;
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  outline: none;
}
.avatar-preview-image {
  max-width: 88vw;
  max-height: 78vh;
  border-radius: 14px;
  object-fit: contain;
  background: #111;
}
</style>
