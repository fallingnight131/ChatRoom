<template>
  <div class="modal-overlay" @click.self="closeDialog">
    <form ref="dialogRef" class="modal password-modal" role="dialog" aria-modal="true"
          aria-labelledby="room-password-title" aria-describedby="room-password-description"
          tabindex="-1" @keydown="onDialogKeydown" @submit.prevent="submit">
      <div id="room-password-title" class="modal-title">需要密码</div>
      <p id="room-password-description" class="password-description">
        此房间需要密码才能加入
      </p>
      <div class="input-group">
        <label for="room-password">房间密码</label>
        <input id="room-password" class="input" v-model="password" type="password"
               placeholder="输入房间密码" autocomplete="off" required />
      </div>
      <div class="modal-actions">
        <button class="btn btn-secondary" type="button" @click="closeDialog">取消</button>
        <button class="btn btn-primary" type="submit" :disabled="!password">加入</button>
      </div>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useModalKeyboardBoundary } from '../ui/useModalKeyboardBoundary'

const props = defineProps({
  roomData: { type: Object, default: null }
})
const emit = defineEmits(['close', 'submit'])

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
