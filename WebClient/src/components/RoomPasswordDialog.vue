<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <form ref="dialogRef" class="modal password-modal" role="dialog" aria-modal="true"
          aria-labelledby="room-password-title" aria-describedby="room-password-description"
          tabindex="-1" @keydown="onDialogKeydown" @submit.prevent="submit">
      <div id="room-password-title" class="modal-title">{{ messages.title }}</div>
      <p id="room-password-description" class="password-description">
        {{ messages.description }}
      </p>
      <div class="input-group">
        <label for="room-password">{{ messages.label }}</label>
        <input id="room-password" class="input" v-model="password" type="password"
               :placeholder="messages.placeholder" autocomplete="off" required />
      </div>
      <div class="modal-actions">
        <button class="btn btn-secondary" type="button" @click="closeDialog">{{ messages.cancel }}</button>
        <button class="btn btn-primary" type="submit" :disabled="!password">{{ messages.join }}</button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'
import { useUserStore } from '../stores/user'
import { roomPasswordMessages } from '../localization/webLocale'

const props = defineProps({
  roomData: { type: Object, default: null }
})
const emit = defineEmits(['close', 'submit'])

const userStore = useUserStore()
const messages = computed(() => roomPasswordMessages(userStore.locale))
const password = ref('')
const { dialogRef, closeDialog, onDialogKeydown } = useModalKeyboardBoundary({
  onClose: () => emit('close'),
  initialFocusSelector: '#room-password'
})

function submit() {
  if (!password.value) return
  const submittedPassword = password.value
  password.value = ''
  emit('submit', submittedPassword)
}
</script>

<style scoped>
.password-modal { width: min(380px, calc(100vw - 32px)); }
.password-description { margin-bottom: 12px; color: var(--text-secondary); font-size: 13px; }
</style>
