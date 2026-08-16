<template>
  <div class="avatar-preview-overlay" @click.self="closeDialog">
    <div ref="dialogRef" class="avatar-preview-card" role="dialog" aria-modal="true"
         aria-labelledby="avatar-preview-title" tabindex="-1"
         @keydown.stop="onDialogKeydown">
      <h2 id="avatar-preview-title" class="visually-hidden">{{ messages.avatarPreview }}</h2>
      <img :src="src" class="avatar-preview-image" :alt="imageAlt" />
      <div class="avatar-preview-actions">
        <button class="btn btn-secondary" type="button" @click="closeDialog">{{ messages.closePreview }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { useUserStore } from '../stores/user'
import { userInfoMessages } from '../localization/webLocale'

const props = defineProps({
  src: { type: String, required: true },
  alt: { type: String, default: '' }
})

const emit = defineEmits(['close'])
const userStore = useUserStore()
const messages = computed(() => userInfoMessages(userStore.locale))
const imageAlt = computed(() => props.alt || messages.value.largeAvatar)
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
