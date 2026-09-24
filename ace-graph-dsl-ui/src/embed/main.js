/**
 * B2/B3 同源 iframe 入口：解析 query → GraphDslManager :embed
 * 部署路径约定：/ace-graph-designer/embed.html
 */
import { createApp, ref, computed } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'
import { GraphDslManager, parseEmbedFromSearch, mergeEmbed, configureGraphApi } from '../index.js'

const embedState = ref(parseEmbedFromSearch(window.location.search))

const shell = (() => {
  const params = new URLSearchParams(window.location.search)
  return {
    apiBaseUrl: params.get('apiBaseUrl') || '/',
    title: params.get('title') || '',
    locale: params.get('locale') || 'zh-CN'
  }
})()

const app = createApp({
  setup() {
    const embed = computed(() => embedState.value)
    return { embed, shell }
  },
  components: { GraphDslManager },
  template: `
    <GraphDslManager
      :api-base-url="shell.apiBaseUrl"
      :title="shell.title"
      :locale="shell.locale"
      :embed="embed"
    />
  `
})

app.use(createPinia()).use(ElementPlus)
app.mount('#app')

function postToParent(payload) {
  try {
    if (window.parent && window.parent !== window) {
      window.parent.postMessage(payload, window.location.origin)
    }
  } catch {
    /* ignore */
  }
}

window.addEventListener('message', (event) => {
  if (event.origin !== window.location.origin) return
  const data = event.data
  if (!data || typeof data !== 'object') return
  if (data.type === 'ace-graph/set-context' && data.embed && typeof data.embed === 'object') {
    embedState.value = mergeEmbed(data.embed, embedState.value)
    return
  }
  if (data.type === 'ace-graph/auth' && typeof data.token === 'string' && data.token) {
    // 可选：Bearer；P1 默认 Cookie，此处仅写入内存默认头
    configureGraphApi({
      headers: { Authorization: `Bearer ${data.token}` }
    })
  }
})

postToParent({ type: 'ace-graph/ready' })
