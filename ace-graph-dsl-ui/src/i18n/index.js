import { ref } from 'vue'
import zhCN from './messages/zh-CN.js'
import enUS from './messages/en-US.js'

const builtins = { 'zh-CN': zhCN, 'en-US': enUS }
const locale = ref('zh-CN')
const customMessages = ref({})

function deepMerge(target, source) {
  const out = { ...target }
  for (const [key, value] of Object.entries(source || {})) {
    if (value && typeof value === 'object' && !Array.isArray(value)) {
      out[key] = deepMerge(out[key] || {}, value)
    } else {
      out[key] = value
    }
  }
  return out
}

function resolveMessages(loc) {
  const base = builtins[loc] || builtins['zh-CN']
  const extra = customMessages.value[loc] || {}
  return deepMerge(base, extra)
}

/**
 * 配置 Graph DSL UI 国际化。
 * @param {{ locale?: string, messages?: Record<string, object> }} options
 */
export function configureGraphDslI18n(options = {}) {
  if (options.locale) {
    locale.value = options.locale
  }
  if (options.messages) {
    const merged = { ...customMessages.value }
    for (const [loc, msgs] of Object.entries(options.messages)) {
      merged[loc] = deepMerge(merged[loc] || {}, msgs)
    }
    customMessages.value = merged
  }
}

export function getGraphDslLocale() {
  return locale.value
}

/**
 * 翻译文案；支持 {param} 占位符。
 */
export function t(key, params) {
  const msgs = resolveMessages(locale.value)
  let text = key.split('.').reduce((obj, part) => obj?.[part], msgs)
  if (text == null) {
    text = key
  }
  if (params && typeof text === 'string') {
    for (const [name, value] of Object.entries(params)) {
      text = text.replace(new RegExp(`\\{${name}\\}`, 'g'), String(value))
    }
  }
  return text
}

/** Vue 组合式 API：locale 变化时模板自动更新 */
export function useI18n() {
  return {
    locale,
    t: (key, params) => {
      void locale.value
      return t(key, params)
    }
  }
}
