/**
 * 设计器嵌入契约 EmbedContext（仅三键下沉）。
 * @see docs/designer-embed-integration.md
 */

/** provide / inject 键 */
export const ACE_GRAPH_EMBED_KEY = Symbol('aceGraphEmbed')

/** otherBizParams UTF-8 字节上限 */
export const OTHER_BIZ_PARAMS_MAX_BYTES = 4096

const EMBED_KEYS = ['graphId', 'agentCode', 'otherBizParams']

/**
 * @typedef {object} EmbedContext
 * @property {string} [graphId]
 * @property {string} [agentCode]
 * @property {string} [otherBizParams]
 */

/**
 * @typedef {object} OtherBizParamsResult
 * @property {boolean} ok
 * @property {string} [value]
 * @property {'TOO_LONG'} [error]
 */

/** @param {string} str */
export function utf8ByteLength(str) {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(str).length
  }
  // 极旧环境回退
  let n = 0
  for (let i = 0; i < str.length; i++) {
    const c = str.charCodeAt(i)
    if (c < 0x80) n += 1
    else if (c < 0x800) n += 2
    else if (c >= 0xd800 && c <= 0xdbff) {
      n += 4
      i++
    } else n += 3
  }
  return n
}

/**
 * 校验 otherBizParams：空 → 省略；超限 → 拒绝（不截断）。
 * @param {unknown} value
 * @returns {OtherBizParamsResult}
 */
export function validateOtherBizParams(value) {
  if (value == null) return { ok: true, value: undefined }
  const s = String(value)
  if (!s) return { ok: true, value: undefined }
  if (utf8ByteLength(s) > OTHER_BIZ_PARAMS_MAX_BYTES) {
    return { ok: false, value: undefined, error: 'TOO_LONG' }
  }
  return { ok: true, value: s }
}

/**
 * @param {unknown} raw
 * @returns {EmbedContext}
 */
function normalize(raw) {
  if (!raw || typeof raw !== 'object') return {}
  /** @type {EmbedContext} */
  const out = {}
  for (const key of EMBED_KEYS) {
    if (!(key in raw)) continue
    const v = raw[key]
    if (v == null) continue
    const s = String(v).trim()
    if (!s) continue
    if (key === 'otherBizParams') {
      const checked = validateOtherBizParams(s)
      if (checked.ok && checked.value) out.otherBizParams = checked.value
      // 超限：丢弃该键（调用方可另弹提示）
      continue
    }
    out[key] = s
  }
  return out
}

/**
 * 合并嵌入上下文：primary（embed 对象）覆盖 aliases（扁平别名）。
 * @param {EmbedContext|null|undefined} primary
 * @param {Partial<EmbedContext>|null|undefined} aliases
 * @returns {EmbedContext}
 */
export function mergeEmbed(primary, aliases) {
  const a = normalize(aliases)
  const p = normalize(primary)
  return { ...a, ...p }
}

/**
 * 从组件 props 解析：`embed` 优先，扁平三键作缺省填充。
 * @param {{ embed?: EmbedContext|null, graphId?: string, agentCode?: string, otherBizParams?: string }} props
 * @returns {EmbedContext}
 */
export function parseEmbedFromProps(props) {
  const p = props || {}
  return mergeEmbed(p.embed, {
    graphId: p.graphId,
    agentCode: p.agentCode,
    otherBizParams: p.otherBizParams
  })
}

/**
 * 从 URL search 解析：`embed` JSON 优先，再合并扁平三键。
 * @param {string|URLSearchParams} search `?…` 或 `URLSearchParams`
 * @returns {EmbedContext}
 */
export function parseEmbedFromSearch(search) {
  const params = typeof search === 'string'
    ? new URLSearchParams(search.startsWith('?') ? search.slice(1) : search)
    : search

  let fromJson = null
  const embedRaw = params.get('embed')
  if (embedRaw) {
    try {
      const parsed = JSON.parse(embedRaw)
      if (parsed && typeof parsed === 'object') fromJson = parsed
    } catch {
      // 非法 JSON 视为未传
    }
  }

  return mergeEmbed(fromJson, {
    graphId: params.get('graphId') || undefined,
    agentCode: params.get('agentCode') || undefined,
    otherBizParams: params.get('otherBizParams') || undefined
  })
}

/**
 * 组装资源 Catalog query（空值省略；otherBizParams 超限则不带并返回 error）。
 * @param {{ agentCode?: string, graphId?: string, otherBizParams?: string, agentDefId?: string }} input
 * @returns {{ params: Record<string, string>, otherBizParamsError?: 'TOO_LONG' }}
 */
export function buildResourceCatalogParams(input = {}) {
  /** @type {Record<string, string>} */
  const params = {}
  const agentCode = (input.agentCode || '').trim()
  const graphId = (input.graphId || '').trim()
  const agentDefId = (input.agentDefId || '').trim()
  if (agentCode) params.agentCode = agentCode
  if (graphId) params.graphId = graphId
  if (agentDefId) params.agentDefId = agentDefId

  let otherBizParamsError
  if (input.otherBizParams != null && String(input.otherBizParams) !== '') {
    const checked = validateOtherBizParams(input.otherBizParams)
    if (!checked.ok) {
      otherBizParamsError = checked.error
    } else if (checked.value) {
      params.otherBizParams = checked.value
    }
  }
  return { params, otherBizParamsError }
}
