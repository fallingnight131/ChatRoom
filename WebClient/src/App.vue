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
  overflow: hidden;
}
#app, .app-root {
  height: 100%;
  width: 100%;
}
</style>
