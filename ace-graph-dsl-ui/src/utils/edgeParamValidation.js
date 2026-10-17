const START = '__START__'
const END = '__END__'
const ERROR = '__ERROR__'

function descriptorMap(nodeDescriptors) {
  const map = new Map()
  for (const d of nodeDescriptors || []) {
    map.set(d.nodeId, d)
  }
  return map
}

function nodeRefMap(definition) {
  const map = new Map()
  for (const n of definition?.nodes || []) {
    if (n?.nodeId) map.set(n.nodeId, n)
  }
  return map
}

function splitKeys(raw) {
  if (!raw) return []
  if (Array.isArray(raw)) return raw.map(s => String(s).trim()).filter(Boolean)
  return String(raw).split(/[,\s]+/).map(s => s.trim()).filter(Boolean)
}

/** SAA 写回图 state：父 outputKey + 子 outputKey */
function saaOutputKeys(saaSpec) {
  const keys = []
  if (!saaSpec) return keys
  const parent = (saaSpec.outputKey || '').trim()
  if (parent) keys.push(parent)
  for (const sub of saaSpec.subAgents || []) {
    const k = (sub?.outputKey || '').trim()
    if (k) keys.push(k)
  }
  return keys
}

function inputKeysOf(nodeId, descMap, refMap) {
  if (nodeId === START || nodeId === END || nodeId === ERROR) return new Set()
  const desc = descMap.get(nodeId)
  if (desc) return new Set(desc.inputKeys || [])
  const ref = refMap.get(nodeId)
  if (ref?.saaSpec || ref?.category === 'SAA_WORKFLOW') {
    return new Set(splitKeys(ref.saaSpec?.inputKeys))
  }
  if (ref?.agentSpec) {
    return new Set(splitKeys(ref.agentSpec.inputKeys))
  }
  return new Set()
}

function outputKeysOf(nodeId, descMap, refMap) {
  if (nodeId === START || nodeId === END || nodeId === ERROR) return new Set()
  const keys = new Set()
  const desc = descMap.get(nodeId)
  for (const k of desc?.outputKeys || []) keys.add(k)
  const ref = refMap.get(nodeId)
  if (ref?.saaSpec || ref?.category === 'SAA_WORKFLOW') {
    for (const k of saaOutputKeys(ref.saaSpec)) keys.add(k)
  } else if (ref?.agentSpec) {
    const out = (ref.agentSpec.outputKey || '').trim()
    if (out) keys.add(out)
  }
  return keys
}

/** START / ERROR 出边、目标为 HITL 的入边不做可达性校验 */
function shouldSkipEdgeValidation(from, to, descMap) {
  if (from === START || from === ERROR) return true
  return descMap.get(to)?.category === 'HITL'
}

function collectNodes(definition) {
  const nodes = new Set([START, END])
  for (const n of definition.nodes || []) {
    nodes.add(n.nodeId)
  }
  for (const e of definition.edges || []) {
    if (e.from) nodes.add(e.from)
    if (e.type === 'conditional') {
      for (const to of Object.values(e.mapping || {})) {
        nodes.add(to)
      }
    } else if (e.to) {
      nodes.add(e.to)
    }
  }
  return nodes
}

function buildPredecessors(definition) {
  const preds = new Map()
  for (const e of definition.edges || []) {
    if (e.type === 'conditional') {
      for (const to of Object.values(e.mapping || {})) {
        if (!preds.has(to)) preds.set(to, [])
        preds.get(to).push(e.from)
      }
    } else if (e.to) {
      if (!preds.has(e.to)) preds.set(e.to, [])
      preds.get(e.to).push(e.from)
    }
  }
  return preds
}

function successorsOf(from, definition) {
  const succ = []
  for (const e of definition.edges || []) {
    if (e.from !== from) continue
    if (e.type === 'conditional') {
      succ.push(...Object.values(e.mapping || {}))
    } else if (e.to) {
      succ.push(e.to)
    }
  }
  return succ
}

function computeEntryKeys(definition, predecessors, descMap, refMap) {
  const entry = new Map()
  const exit = new Map()
  entry.set(START, new Set())
  exit.set(START, new Set())

  const allNodes = collectNodes(definition)
  const indegree = new Map()
  for (const node of allNodes) {
    indegree.set(node, (predecessors.get(node) || []).length)
  }

  const queue = [START]
  const processed = new Set([START])

  while (queue.length) {
    const node = queue.shift()
    const nodeEntry = entry.get(node) || new Set()
    const nodeExit = new Set(nodeEntry)
    for (const k of outputKeysOf(node, descMap, refMap)) nodeExit.add(k)
    exit.set(node, nodeExit)

    for (const succ of successorsOf(node, definition)) {
      if (!entry.has(succ)) entry.set(succ, new Set())
      for (const k of nodeExit) entry.get(succ).add(k)
      indegree.set(succ, (indegree.get(succ) || 0) - 1)
      if (indegree.get(succ) === 0 && !processed.has(succ)) {
        processed.add(succ)
        queue.push(succ)
      }
    }
  }
  return entry
}

/**
 * 校验连线参数可达性。
 * @returns {Array<{ edgeKey: string, from: string, to: string, targetNodeId: string, missingKeys: string[] }>}
 */
export function validateEdgeParamReachability(definition, nodeDescriptors) {
  if (!definition?.nodes?.length) return []

  const descMap = descriptorMap(nodeDescriptors)
  const refMap = nodeRefMap(definition)
  const predecessors = buildPredecessors(definition)
  const entryKeys = computeEntryKeys(definition, predecessors, descMap, refMap)
  const reported = new Set()
  const issues = []

  function reportEdge(from, to) {
    if (!to || to === END) return
    if (shouldSkipEdgeValidation(from, to, descMap)) return
    const required = inputKeysOf(to, descMap, refMap)
    if (!required.size) return
    const available = entryKeys.get(to) || new Set()
    const missingKeys = [...required].filter(k => !available.has(k))
    if (!missingKeys.length) return
    const dedupeKey = `${from}->${to}:${missingKeys.sort().join(',')}`
    if (reported.has(dedupeKey)) return
    reported.add(dedupeKey)
    issues.push({
      edgeKey: `${from}->${to}`,
      from,
      to,
      targetNodeId: to,
      missingKeys
    })
  }

  for (const e of definition.edges || []) {
    if (e.type === 'conditional') {
      for (const to of Object.values(e.mapping || {})) {
        reportEdge(e.from, to)
      }
    } else {
      reportEdge(e.from, e.to)
    }
  }
  return issues
}
