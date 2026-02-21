<template>
  <div :class="['app-root', themeClass]">
    <router-view />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useUserStore } from './stores/user'

const userStore = useUserStore()
const themeClass = computed(() => userStore.darkMode ? 'dark-theme' : 'light-theme')
</script>

<style>
html, body {
  margin: 0;
  padding: 0;
  height: 100%;
  height: 100dvh; /* 移动端浏览器动态视口高度 */
  overflow: hidden;
  touch-action: manipulation; /* 消除300ms点击延迟 */
  -webkit-tap-highlight-color: transparent; /* 移除移动端点击高亮 */
}
#app, .app-root {
  height: 100%;
  height: 100dvh;
  width: 100%;
}
</style>
