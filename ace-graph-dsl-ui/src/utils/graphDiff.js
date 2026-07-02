function edgeKey(edge) {
  if (!edge) return ''
  if (edge.type === 'conditional') {
    const route = edge.dispatcher || edge.condition || ''
    return `${edge.from}|conditional|${route}`
  }
  return `${edge.from}|normal|${edge.to || ''}`
}

function stableStringify(value) {
  return JSON.stringify(value, Object.keys(value || {}).sort())
}

/**
 * 按 key 对比两个数组，返回增删改。
 */
export function diffByKey(baseList, targetList, keyFn) {
  const baseMap = new Map((baseList || []).map(item => [keyFn(item), item]))
  const targetMap = new Map((targetList || []).map(item => [keyFn(item), item]))
  const added = []
  const removed = []
  const modified = []

  for (const [key, item] of targetMap) {
    if (!baseMap.has(key)) {
      added.push({ key, item })
    } else if (stableStringify(baseMap.get(key)) !== stableStringify(item)) {
      modified.push({ key, before: baseMap.get(key), after: item })
    }
  }
  for (const [key, item] of baseMap) {
    if (!targetMap.has(key)) {
      removed.push({ key, item })
    }
  }
  return { added, removed, modified }
}

function shallowDiff(base, target) {
  const keys = new Set([...Object.keys(base || {}), ...Object.keys(target || {})])
  const changes = []
  for (const key of keys) {
    const b = base?.[key]
    const t = target?.[key]
    if (stableStringify(b) !== stableStringify(t)) {
      changes.push({ key, before: b, after: t })
    }
  }
  return changes
}

function diffMeta(base, target) {
  const fields = ['displayName', 'description', 'version']
  const changes = []
  for (const field of fields) {
    if ((base?.[field] ?? '') !== (target?.[field] ?? '')) {
      changes.push({ key: field, before: base?.[field], after: target?.[field] })
    }
  }
  const baseKs = base?.keyStrategies || {}
  const targetKs = target?.keyStrategies || {}
  if (stableStringify(baseKs) !== stableStringify(targetKs)) {
    changes.push({ key: 'keyStrategies', before: baseKs, after: targetKs })
  }
  return changes
}

/**
 * 对比两份 GraphDefinition 的结构差异。
 */
export function diffGraphStructure(base, target) {
  return {
    nodes: diffByKey(base?.nodes, target?.nodes, n => n.nodeId),
    edges: diffByKey(base?.edges, target?.edges, edgeKey),
    compile: shallowDiff(base?.compile, target?.compile),
    meta: diffMeta(base, target)
  }
}

export function hasStructuralDiff(diff) {
  if (!diff) return false
  const sections = [diff.nodes, diff.edges]
  for (const section of sections) {
    if (section.added.length || section.removed.length || section.modified.length) {
      return true
    }
  }
  if (diff.compile.length || diff.meta.length) return true
  return false
}

export function formatDefinitionJson(def) {
  return JSON.stringify(def, null, 2)
}
