<template>
  <Teleport to="body">
    <div v-if="visible" class="preview-overlay" @click.self="close">
      <!-- 顶部栏 -->
      <div class="preview-topbar">
        <span class="preview-filename text-ellipsis">{{ fileName }}</span>
        <div class="preview-actions">
          <button class="preview-btn" @click="download" title="下载">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/>
              <polyline points="7 10 12 15 17 10"/>
              <line x1="12" y1="15" x2="12" y2="3"/>
            </svg>
          </button>
          <button class="preview-btn" @click="close" title="关闭">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- 加载动画 -->
      <div v-if="loading" class="preview-spinner">
        <div class="spinner"></div>
        <span>加载中... {{ loadPercent }}</span>
      </div>

      <!-- 图片预览 -->
      <div v-else-if="previewType === 'image'" class="preview-content preview-image-wrap"
           @click.self="close">
        <img :src="blobUrl" class="preview-image"
             :style="imageStyle"
             @wheel.prevent="onWheel"
             @mousedown="onDragStart"
             @dblclick="resetZoom" />
      </div>

      <!-- 视频播放 (DPlayer) -->
      <div v-else-if="previewType === 'video'" class="preview-content preview-video-wrap"
           @click.self="close">
        <div ref="dplayerRef" class="dplayer-container"></div>
      </div>

      <!-- 音频播放 -->
      <div v-else-if="previewType === 'audio'" class="preview-content preview-audio-wrap"
           @click.self="close">
        <div class="audio-card">
          <div class="audio-icon">🎵</div>
          <div class="audio-name">{{ fileName }}</div>
          <audio :src="blobUrl" controls autoplay class="audio-player"></audio>
        </div>
      </div>

      <!-- PDF 预览 -->
      <div v-else-if="previewType === 'pdf'" class="preview-content preview-pdf-wrap"
           @click.self="close">
        <iframe :src="blobUrl" class="preview-pdf"></iframe>
      </div>

      <!-- 文本预览 -->
      <div v-else-if="previewType === 'text'" class="preview-content preview-text-wrap"
           @click.self="close">
        <pre class="preview-text-content">{{ textContent }}</pre>
      </div>

      <!-- 不支持预览 -->
      <div v-else-if="previewType === 'unsupported'" class="preview-content preview-unsupported"
           @click.self="close">
        <div class="unsupported-card">
          <div class="unsupported-icon">📄</div>
          <div class="unsupported-name">{{ fileName }}</div>
          <div class="unsupported-size">{{ formatSize(fileSize) }}</div>
          <div class="unsupported-hint">该文件类型不支持在线预览</div>
          <button class="unsupported-download-btn" @click="download">⬇ 下载文件</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, watch, onUnmounted, nextTick, computed } from 'vue'
import { useChatStore } from '../stores/chat'
import { chatWs, MsgType, FILE_CHUNK_SIZE, MAX_SMALL_FILE } from '../services/websocket'

const props = defineProps({
  visible: Boolean,
  msg: Object,       // 消息对象: { fileId, fileName, fileSize, imageData, contentType }
})
const emit = defineEmits(['close'])

const chatStore = useChatStore()

const loading = ref(false)
const loadPercent = ref('')
const blobUrl = ref('')
const textContent = ref('')
const previewType = ref('')  // image | video | audio | pdf | text | unsupported
const fileName = ref('')
const fileSize = ref(0)
const dplayerRef = ref(null)
let dpInstance = null

// 图片缩放/拖动
const scale = ref(1)
const translateX = ref(0)
const translateY = ref(0)
let isDragging = false
let dragStartX = 0, dragStartY = 0
let lastTx = 0, lastTy = 0

const imageStyle = computed(() => ({
  transform: `translate(${translateX.value}px, ${translateY.value}px) scale(${scale.value})`,
  cursor: isDragging ? 'grabbing' : 'grab',
}))

// 文件类型检测
const VIDEO_EXTS = /\.(mp4|webm|ogg|mov|m4v|flv|avi|mkv|3gp)$/i
const IMAGE_EXTS = /\.(jpg|jpeg|png|gif|bmp|webp|svg|ico|tiff)$/i
const AUDIO_EXTS = /\.(mp3|wav|ogg|flac|aac|m4a|wma)$/i
const PDF_EXTS = /\.pdf$/i
const TEXT_EXTS = /\.(txt|md|json|xml|csv|log|ini|cfg|conf|yaml|yml|toml|sh|bat|ps1|py|js|ts|vue|jsx|tsx|html|css|scss|less|c|cpp|h|hpp|java|go|rs|rb|php|sql|r|m|swift|kt|dart|lua|pl|asm)$/i

const VIDEO_MIMES = ['video/mp4', 'video/webm', 'video/ogg']
const AUDIO_MIMES = ['audio/mpeg', 'audio/wav', 'audio/ogg', 'audio/flac', 'audio/aac']

function detectType(name) {
  if (!name) return 'unsupported'
  if (IMAGE_EXTS.test(name)) return 'image'
  if (VIDEO_EXTS.test(name)) return 'video'
  if (AUDIO_EXTS.test(name)) return 'audio'
  if (PDF_EXTS.test(name)) return 'pdf'
  if (TEXT_EXTS.test(name)) return 'text'
  return 'unsupported'
}

function getMimeType(name, type) {
  if (type === 'image') {
    const ext = name.split('.').pop().toLowerCase()
    const map = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', bmp: 'image/bmp', webp: 'image/webp', svg: 'image/svg+xml', ico: 'image/x-icon' }
    return map[ext] || 'image/png'
  }
  if (type === 'video') {
    const ext = name.split('.').pop().toLowerCase()
    const map = { mp4: 'video/mp4', webm: 'video/webm', ogg: 'video/ogg', mov: 'video/mp4', m4v: 'video/mp4', flv: 'video/x-flv', avi: 'video/x-msvideo', mkv: 'video/x-matroska' }
    return map[ext] || 'video/mp4'
  }
  if (type === 'audio') {
    const ext = name.split('.').pop().toLowerCase()
    const map = { mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', flac: 'audio/flac', aac: 'audio/aac', m4a: 'audio/mp4', wma: 'audio/x-ms-wma' }
    return map[ext] || 'audio/mpeg'
  }
  if (type === 'pdf') return 'application/pdf'
  if (type === 'text') return 'text/plain'
  return 'application/octet-stream'
}

function formatSize(size) {
  if (!size) return ''
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB'
  if (size < 1024 * 1024 * 1024) return (size / (1024*1024)).toFixed(1) + ' MB'
  return (size / (1024*1024*1024)).toFixed(2) + ' GB'
}

// base64 → Uint8Array
function b64ToBytes(b64) {
  const binary = atob(b64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

function resetZoom() {
  scale.value = 1
  translateX.value = 0
  translateY.value = 0
}

function onWheel(e) {
  const delta = e.deltaY > 0 ? -0.1 : 0.1
  scale.value = Math.max(0.1, Math.min(10, scale.value + delta))
}

function onDragStart(e) {
  isDragging = true
  dragStartX = e.clientX
  dragStartY = e.clientY
  lastTx = translateX.value
  lastTy = translateY.value
  const onMove = (ev) => {
    translateX.value = lastTx + (ev.clientX - dragStartX)
    translateY.value = lastTy + (ev.clientY - dragStartY)
  }
  const onUp = () => {
    isDragging = false
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

function close() {
  emit('close')
}

function download() {
  const msg = props.msg
  if (!msg) return
  if (blobUrl.value) {
    // 已经有 blob，直接下载
    const a = document.createElement('a')
    a.href = blobUrl.value
    a.download = fileName.value
    a.click()
  } else if (msg.fileId) {
    // 触发下载
    chatStore._triggerDownload(msg.fileId, msg.fileName, msg.fileSize)
  }
}

function cleanup() {
  if (dpInstance) {
    try { dpInstance.destroy() } catch (e) { /* ignore */ }
    dpInstance = null
  }
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value)
    blobUrl.value = ''
  }
  textContent.value = ''
  previewType.value = ''
  loading.value = false
  loadPercent.value = ''
  resetZoom()
}

async function initDPlayer(url) {
  // 动态导入 DPlayer
  const DPlayer = (await import('dplayer')).default
  await nextTick()
  if (!dplayerRef.value) return
  dpInstance = new DPlayer({
    container: dplayerRef.value,
    video: {
      url: url,
      type: 'auto',
    },
    autoplay: true,
    theme: '#409eff',
    volume: 0.7,
    screenshot: true,
    hotkey: true,
    preload: 'auto',
    lang: 'zh-cn',
  })
}

// 打开预览
async function openPreview(msg) {
  cleanup()
  if (!msg) return
  fileName.value = msg.fileName || '未知文件'
  fileSize.value = msg.fileSize || 0

  const type = detectType(fileName.value)
  // 如果已有 base64 image data
  if (msg.imageData && (type === 'image' || msg.contentType === 'image')) {
    previewType.value = 'image'
    blobUrl.value = 'data:image/png;base64,' + msg.imageData
    return
  }

  previewType.value = type
  if (type === 'unsupported') return

  // 需要下载文件
  if (!msg.fileId) return
  loading.value = true
  loadPercent.value = ''

  if (msg.fileSize && msg.fileSize > MAX_SMALL_FILE) {
    // 大文件分块下载
    await downloadLargeFile(msg, type)
  } else {
    // 小文件直下
    await downloadSmallFile(msg, type)
  }
}

function downloadSmallFile(msg, type) {
  return new Promise((resolve) => {
    const handler = (rsp) => {
      const d = rsp.data
      chatWs.off(MsgType.FILE_DOWNLOAD_RSP, handler)
      if (!d.success || !d.fileData) {
        loading.value = false
        previewType.value = 'unsupported'
        resolve()
        return
      }
      const bytes = b64ToBytes(d.fileData)
      handleFileData(bytes, type)
      resolve()
    }
    // 标记为预览模式，阻止 store 自动下载
    chatStore._previewFileIds.add(msg.fileId)
    chatWs.on(MsgType.FILE_DOWNLOAD_RSP, handler)
    chatWs.downloadFile(msg.fileId)
    // 超时
    setTimeout(() => {
      chatWs.off(MsgType.FILE_DOWNLOAD_RSP, handler)
      if (loading.value) {
        loading.value = false
        previewType.value = 'unsupported'
      }
      resolve()
    }, 30000)
  })
}

function downloadLargeFile(msg, type) {
  return new Promise((resolve) => {
    const chunks = []
    let received = 0
    const handler = (rsp) => {
      const d = rsp.data
      if (d.fileId !== msg.fileId) return
      if (!d.success || !d.chunkData) {
        chatWs.off(MsgType.FILE_DOWNLOAD_CHUNK_RSP, handler)
        loading.value = false
        previewType.value = 'unsupported'
        resolve()
        return
      }
      const bytes = b64ToBytes(d.chunkData)
      chunks.push(bytes)
      received = d.offset + d.chunkSize
      const percent = Math.floor((received / (d.fileSize || msg.fileSize)) * 100)
      loadPercent.value = percent + '%'

      if (received >= (d.fileSize || msg.fileSize)) {
        chatWs.off(MsgType.FILE_DOWNLOAD_CHUNK_RSP, handler)
        // 合并所有块
        const totalSize = chunks.reduce((s, c) => s + c.length, 0)
        const merged = new Uint8Array(totalSize)
        let offset = 0
        for (const c of chunks) {
          merged.set(c, offset)
          offset += c.length
        }
        handleFileData(merged, type)
        resolve()
      } else {
        chatWs.downloadChunk(msg.fileId, received, FILE_CHUNK_SIZE)
      }
    }
    // 标记为预览模式
    chatStore._previewFileIds.add(msg.fileId)
    chatWs.on(MsgType.FILE_DOWNLOAD_CHUNK_RSP, handler)
    chatWs.downloadChunk(msg.fileId, 0, FILE_CHUNK_SIZE)
  })
}

function handleFileData(bytes, type) {
  loading.value = false
  const mime = getMimeType(fileName.value, type)

  if (type === 'text') {
    // 文本直接解码
    const decoder = new TextDecoder('utf-8')
    textContent.value = decoder.decode(bytes)
    return
  }

  const blob = new Blob([bytes], { type: mime })
  blobUrl.value = URL.createObjectURL(blob)

  if (type === 'video') {
    nextTick(() => initDPlayer(blobUrl.value))
  }
}

// 监听 visible 变化
watch(() => props.visible, (v) => {
  if (v && props.msg) {
    openPreview(props.msg)
    // 阻止背景滚动
    document.body.style.overflow = 'hidden'
  } else {
    cleanup()
    document.body.style.overflow = ''
  }
})

// ESC 关闭
function onKeydown(e) {
  if (e.key === 'Escape' && props.visible) close()
}
if (typeof document !== 'undefined') {
  document.addEventListener('keydown', onKeydown)
}
onUnmounted(() => {
  cleanup()
  document.body.style.overflow = ''
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.9);
  z-index: 5000;
  display: flex;
  flex-direction: column;
}

/* 顶部栏 */
.preview-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  background: rgba(0, 0, 0, 0.5);
  color: #fff;
  flex-shrink: 0;
  z-index: 5001;
}
.preview-filename {
  font-size: 14px;
  max-width: 60%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-actions {
  display: flex;
  gap: 8px;
}
.preview-btn {
  background: rgba(255,255,255,0.12);
  border: none;
  color: #fff;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}
.preview-btn:hover {
  background: rgba(255,255,255,0.25);
}

/* 加载 */
.preview-spinner {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: #fff;
  font-size: 14px;
}
.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid rgba(255,255,255,0.2);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* 内容区 */
.preview-content {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  min-height: 0;
}

/* 图片 */
.preview-image-wrap {
  padding: 20px;
}
.preview-image {
  max-width: 95%;
  max-height: 95%;
  object-fit: contain;
  border-radius: 4px;
  transition: transform 0.1s ease;
  user-select: none;
  -webkit-user-drag: none;
}

/* 视频 - DPlayer */
.preview-video-wrap {
  padding: 20px;
  width: 100%;
}
.dplayer-container {
  width: 100%;
  max-width: 900px;
  margin: 0 auto;
  border-radius: 8px;
  overflow: hidden;
}

/* 音频 */
.preview-audio-wrap {
  padding: 20px;
}
.audio-card {
  background: rgba(255,255,255,0.08);
  border-radius: 16px;
  padding: 40px 48px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.audio-icon {
  font-size: 64px;
}
.audio-name {
  color: #fff;
  font-size: 16px;
  max-width: 400px;
  text-align: center;
  word-break: break-all;
}
.audio-player {
  width: 360px;
  max-width: 90vw;
  margin-top: 8px;
}
/* 替换默认audio样式 - Chrome/Edge */
audio::-webkit-media-controls-panel {
  background: rgba(255,255,255,0.1);
}

/* PDF */
.preview-pdf-wrap {
  padding: 20px;
  width: 100%;
  height: 100%;
}
.preview-pdf {
  width: 100%;
  height: 100%;
  max-width: 900px;
  margin: 0 auto;
  border: none;
  border-radius: 8px;
  background: #fff;
  display: block;
}

/* 文本 */
.preview-text-wrap {
  padding: 20px;
  width: 100%;
  height: 100%;
  overflow: auto;
}
.preview-text-content {
  background: rgba(255,255,255,0.06);
  color: #e0e0e0;
  padding: 20px 24px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-width: 900px;
  margin: 0 auto;
  overflow: auto;
  max-height: 100%;
  width: 100%;
  box-sizing: border-box;
}

/* 不支持预览 */
.preview-unsupported {
  padding: 20px;
}
.unsupported-card {
  background: rgba(255,255,255,0.08);
  border-radius: 16px;
  padding: 48px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  text-align: center;
}
.unsupported-icon {
  font-size: 72px;
}
.unsupported-name {
  color: #fff;
  font-size: 16px;
  word-break: break-all;
  max-width: 400px;
}
.unsupported-size {
  color: rgba(255,255,255,0.5);
  font-size: 13px;
}
.unsupported-hint {
  color: rgba(255,255,255,0.4);
  font-size: 13px;
  margin-top: 8px;
}
.unsupported-download-btn {
  margin-top: 16px;
  padding: 10px 28px;
  background: #409eff;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  transition: background 0.2s;
}
.unsupported-download-btn:hover {
  background: #66b1ff;
}

/* 移动端适配 */
@media (max-width: 768px) {
  .preview-topbar {
    padding: 8px 12px;
    padding-top: max(8px, env(safe-area-inset-top));
  }
  .preview-filename {
    font-size: 13px;
    max-width: 55%;
  }
  .preview-btn {
    width: 36px;
    height: 36px;
  }
  .preview-image-wrap,
  .preview-video-wrap,
  .preview-audio-wrap,
  .preview-pdf-wrap,
  .preview-text-wrap {
    padding: 10px;
  }
  .audio-card,
  .unsupported-card {
    padding: 28px 20px;
  }
  .audio-icon {
    font-size: 48px;
  }
  .unsupported-icon {
    font-size: 56px;
  }
  .dplayer-container {
    max-width: 100%;
  }
}
</style>
