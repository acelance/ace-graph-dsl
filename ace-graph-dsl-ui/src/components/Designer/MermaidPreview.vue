<script setup>
import { ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  /** Mermaid 源码（可能含 ``` 围栏，组件会自动剥离） */
  source: { type: String, default: '' }
})

const container = ref(null)
const renderFailed = ref(false)
const rendered = ref(false)
const copied = ref(false)

let mermaidPromise = null
function loadMermaid() {
  if (typeof window !== 'undefined' && window.mermaid) return Promise.resolve(window.mermaid)
  if (mermaidPromise) return mermaidPromise
  mermaidPromise = new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js'
    s.async = true
    s.onload = () => {
      try {
        window.mermaid.initialize({ startOnLoad: false, securityLevel: 'loose', theme: 'default' })
        resolve(window.mermaid)
      } catch (e) {
        reject(e)
      }
    }
    s.onerror = () => reject(new Error('mermaid-load-failed'))
    document.head.appendChild(s)
  })
  return mermaidPromise
}

function cleanSource(src) {
  if (!src) return ''
  let s = String(src).trim()
  if (s.startsWith('```')) {
    s = s.replace(/^```[a-zA-Z]*\n?/, '').replace(/```\s*$/, '')
  }
  return s.trim()
}

async function render() {
  renderFailed.value = false
  rendered.value = false
  const src = cleanSource(props.source)
  if (!src) return
  try {
    const m = await loadMermaid()
    const id = 'mmd-' + Date.now() + '-' + Math.floor(Math.random() * 1e6)
    const { svg } = await m.render(id, src)
    if (container.value) {
      container.value.innerHTML = svg
      rendered.value = true
    }
  } catch (e) {
    renderFailed.value = true
    console.warn('[MermaidPreview] render failed, fallback to source:', e)
  }
}

watch(() => props.source, () => render())
onMounted(() => render())

async function copySource() {
  try {
    await navigator.clipboard.writeText(props.source || '')
    copied.value = true
    ElMessage.success('已复制 Mermaid 源码')
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    ElMessage.warning('复制失败，请手动选择文本')
  }
}
</script>

<template>
  <div class="mermaid-preview">
    <div v-if="!props.source" class="mp-empty">（无内容）</div>
    <template v-else>
      <div v-if="renderFailed" class="mp-fallback">
        <el-alert type="warning" :closable="false" title="Mermaid 渲染库未加载（可能处于离线环境），已改为显示源码" />
        <pre class="mp-source">{{ props.source }}</pre>
      </div>
      <div v-show="!renderFailed" ref="container" class="mp-canvas"></div>
      <div class="mp-actions">
        <el-button size="small" @click="copySource">{{ copied ? '已复制' : '复制 Mermaid 源码' }}</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mermaid-preview { width: 100%; }
.mp-canvas {
  width: 100%;
  overflow: auto;
  max-height: 58vh;
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
}
.mp-source {
  white-space: pre-wrap;
  word-break: break-all;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 8px;
  max-height: 46vh;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
}
.mp-actions { margin-top: 8px; text-align: right; }
.mp-empty { color: #909399; padding: 20px; text-align: center; }
.mp-fallback { margin-bottom: 8px; }
</style>
