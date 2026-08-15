/**
 * 将 GraphDefinition 转换为 Mermaid flowchart 源码（结构视图）。
 *
 * 用途：子图嵌套预览。纯函数、零依赖、对任意 DSL 拓扑均不抛异常，
 * 作为「服务端编译视图」不可用时（部分子图 / 离线 / 无权限）的兜底。
 *
 * 说明：Mermaid 节点 id 必须是合法标识符，故对原始 nodeId 做 sanitize；
 * 标签统一用双引号包裹以容纳中文 / emoji / 特殊符号。
 */

const RESERVED = new Set(['__START__', '__END__', '__ERROR__', 'lf_start', 'lf_end', 'lf_error'])

function sanitizeId(id) {
  const s = String(id == null ? '' : id).replace(/[^0-9A-Za-z_]/g, '_')
  return s || 'n'
}

function mermaidLabel(text) {
  if (text === undefined || text === null) return ''
  return String(text).replace(/"/g, '&quot;')
}

function isReserved(id) {
  return RESERVED.has(id)
}

/**
 * @param {object} def GraphDefinition（含 nodes / edges）
 * @returns {string} Mermaid 源码
 */
export function graphDefinitionToMermaid(def) {
  if (!def) return ''
  const nodes = def.nodes || []
  const edges = def.edges || []

  const idMap = new Map()
  const usedIds = new Set()

  function mermaidId(rawId) {
    if (idMap.has(rawId)) return idMap.get(rawId)
    let base = sanitizeId(rawId)
    let cand = base
    let i = 1
    while (usedIds.has(cand)) cand = `${base}_${i++}`
    usedIds.add(cand)
    idMap.set(rawId, cand)
    return cand
  }

  const nodeById = new Map(nodes.map((n) => [n.nodeId, n]))
  const allIds = new Set([
    ...nodes.map((n) => n.nodeId),
    ...edges.flatMap((e) => [e.from, e.to])
  ])

  function shape(rawId) {
    const mid = mermaidId(rawId)
    const node = nodeById.get(rawId) || { nodeId: rawId, category: 'NORMAL' }
    const label = mermaidLabel(node.displayName || rawId)
    if (rawId === '__START__' || rawId === 'lf_start') return `${mid}([⚡ ${label}])`
    if (rawId === '__END__' || rawId === 'lf_end') return `${mid}([⏹ ${label}])`
    if (rawId === '__ERROR__' || rawId === 'lf_error') return `${mid}{{⚠ ${label}}}`
    switch (node.category) {
      case 'SUBGRAPH':
        return `${mid}[["📁 ${label}"]]`
      case 'AGENT':
        return `${mid}("🤖 ${label}")`
      case 'HITL':
        return `${mid}{"⏸ ${label}"}`
      case 'ROUTER':
        return `${mid}{"⇄ ${label}"}`
      case 'MERGE':
        return `${mid}["⨝ ${label}"]`
      default:
        return `${mid}["${label}"]`
    }
  }

  const lines = ['graph TD']
  const classLines = []

  for (const id of allIds) {
    lines.push('  ' + shape(id))
    const node = nodeById.get(id)
    const cat = node?.category
    const mid = mermaidId(id)
    if (isReserved(id)) classLines.push(`  class ${mid} reserved`)
    else if (cat === 'SUBGRAPH') classLines.push(`  class ${mid} subgraph`)
    else if (cat === 'AGENT') classLines.push(`  class ${mid} agent`)
    else if (cat === 'HITL') classLines.push(`  class ${mid} hitl`)
  }

  for (const e of edges) {
    const from = mermaidId(e.from)
    const to = mermaidId(e.to)
    if (e.type === 'conditional') {
      const label = e.condition
        ? mermaidLabel(e.condition)
        : e.dispatcher
          ? `disp:${mermaidLabel(e.dispatcher)}`
          : 'cond'
      lines.push(`  ${from} -.->|${label}| ${to}`)
    } else if (e.type === 'error') {
      lines.push(`  ${from} -.->|error| ${to}`)
    } else {
      const parallel = e.parallel === true
      let label = ''
      if (parallel) {
        const agg = e.aggregation === 'ALL_OF' ? 'ALL_OF' : e.aggregation === 'ANY_OF' ? 'ANY_OF' : ''
        label = agg ? `并行·${agg}` : '并行'
      }
      lines.push(label ? `  ${from} -->|${label}| ${to}` : `  ${from} --> ${to}`)
    }
  }

  lines.push(
    '  classDef reserved fill:#ecfdf5,stroke:#10b981,color:#065f46;',
    '  classDef subgraph fill:#eef2ff,stroke:#6366f1,color:#3730a3;',
    '  classDef agent fill:#fef3c7,stroke:#f59e0b,color:#92400e;',
    '  classDef hitl fill:#fee2e2,stroke:#ef4444,color:#991b1b;'
  )
  if (classLines.length) lines.push(...classLines)

  return lines.join('\n')
}

export default graphDefinitionToMermaid
