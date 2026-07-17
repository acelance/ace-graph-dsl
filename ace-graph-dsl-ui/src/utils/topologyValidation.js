const START = '__START__'
const END = '__END__'

/**
 * 整体拓扑校验：环检测、END 可达性、不可达节点、孤立节点。
 * 纯结构校验，不依赖节点注册表，可在设计器内即时运行。
 *
 * @param {object} def GraphDefinition（含 nodes / edges）
 * @returns {{ ok: boolean, issues: Array<{ level: 'error'|'warning', type: string, message: string, nodes: string[] }> }}
 */
export function validateTopology(def) {
  const issues = []
  if (!def) {
    return { ok: false, issues: [{ level: 'error', type: 'empty', message: '图定义为空', nodes: [] }] }
  }

  const reserved = new Set([START, END])
  const nodeIds = new Set((def.nodes || []).map(n => n.nodeId).filter(id => id && !reserved.has(id)))
  nodeIds.add(START)
  nodeIds.add(END)

  const adj = new Map()
  const rev = new Map()
  const link = (from, to) => {
    if (!nodeIds.has(from) || !nodeIds.has(to) || from === to) return
    if (!adj.has(from)) adj.set(from, new Set())
    adj.get(from).add(to)
    if (!rev.has(to)) rev.set(to, new Set())
    rev.get(to).add(from)
  }

  for (const e of def.edges || []) {
    if (e.type === 'conditional') {
      const mapping = e.mapping || {}
      for (const target of Object.values(mapping)) link(e.from, target)
    } else {
      link(e.from, e.to)
    }
  }

  // 1. 环检测（END 视为终止节点，不再向外扩展）
  const cycle = findCycle(adj)
  if (cycle.length) {
    issues.push({
      level: 'error',
      type: 'cycle',
      message: `检测到环: ${[...cycle, cycle[0]].join(' → ')}`,
      nodes: cycle
    })
  }

  // 2. END 可达性（从 START BFS）
  const reachable = bfs(START, adj)
  if (!reachable.has(END)) {
    issues.push({
      level: 'error',
      type: 'noEnd',
      message: '无法从 START 到达 END（流程断头，END 不可达）',
      nodes: [START, END]
    })
  }

  // 3. 不可达节点 / 4. 孤立节点
  const unreachable = []
  const isolated = []
  for (const id of nodeIds) {
    if (id === START || id === END) continue
    if (!reachable.has(id)) unreachable.push(id)
    const hasIn = (rev.get(id) && rev.get(id).size > 0)
    const hasOut = (adj.get(id) && adj.get(id).size > 0)
    if (!hasIn && !hasOut) isolated.push(id)
  }
  if (unreachable.length) {
    issues.push({
      level: 'warning',
      type: 'unreachable',
      message: `不可达节点（从 START 无法到达）: ${unreachable.join(', ')}`,
      nodes: unreachable
    })
  }
  if (isolated.length) {
    issues.push({
      level: 'warning',
      type: 'isolated',
      message: `孤立节点（无任何连线）: ${isolated.join(', ')}`,
      nodes: isolated
    })
  }

  return { ok: issues.every(i => i.level !== 'error'), issues }
}

function bfs(start, adj) {
  const visited = new Set([start])
  const queue = [start]
  while (queue.length) {
    const cur = queue.shift()
    if (cur === END) continue
    for (const n of (adj.get(cur) || [])) {
      if (!visited.has(n)) {
        visited.add(n)
        queue.push(n)
      }
    }
  }
  return visited
}

function findCycle(adj) {
  const visited = new Set()
  const recStack = new Set()
  const path = []
  let cycle = []
  function dfs(node) {
    if (recStack.has(node)) {
      const idx = path.indexOf(node)
      cycle = path.slice(idx).concat(node)
      return true
    }
    if (visited.has(node)) return false
    visited.add(node)
    recStack.add(node)
    path.push(node)
    const nexts = node === END ? [] : (adj.get(node) || [])
    for (const n of nexts) {
      if (dfs(n)) return true
    }
    recStack.delete(node)
    path.pop()
    return false
  }
  for (const node of adj.keys()) {
    if (node === END) continue
    if (dfs(node)) break
  }
  return cycle
}
