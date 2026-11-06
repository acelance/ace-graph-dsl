/**
 * HITL 结构节点默认规格与规范化（与后端 HitlNodeSpec 对齐）。
 */

/** 拖入画布时的默认 hitlSpec */
export function defaultHitlSpec() {
  return {
    injectKeys: []
  }
}

/**
 * 规范化 injectKeys：去空白、去重、保序。
 * @param {unknown} raw
 * @returns {string[]}
 */
export function normalizeInjectKeys(raw) {
  if (!raw) return []
  const list = Array.isArray(raw)
    ? raw
    : String(raw).split(/[,\s]+/)
  const seen = new Set()
  const out = []
  for (const item of list) {
    const k = String(item || '').trim()
    if (!k || seen.has(k)) continue
    seen.add(k)
    out.push(k)
  }
  return out
}

/**
 * @param {object|null|undefined} spec
 * @returns {{ injectKeys: string[] }}
 */
export function normalizeHitlSpec(spec) {
  return {
    injectKeys: normalizeInjectKeys(spec?.injectKeys)
  }
}

/** @param {object|null|undefined} node */
export function isHitlNode(node) {
  return !!(node && (node.category === 'HITL' || node.hitlSpec))
}
