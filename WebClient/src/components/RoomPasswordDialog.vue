<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" style="min-width: 340px;">
      <div class="modal-title">需要密码</div>
      <p style="margin-bottom: 12px; color: var(--text-secondary); font-size: 13px;">
        此房间需要密码才能加入
      </p>
      <div class="input-group">
        <label>房间密码</label>
        <input class="input" v-model="password" type="password" placeholder="输入房间密码"
               @keyup.enter="submit" />
      </div>
      <div class="modal-actions">
        <button class="btn btn-secondary" @click="$emit('close')">取消</button>
        <button class="btn btn-primary" @click="submit">加入</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps({
  roomData: { type: Object, default: null }
})
const emit = defineEmits(['close', 'submit'])

const password = ref('')

function submit() {
  emit('submit', password.value)
}
</script>
