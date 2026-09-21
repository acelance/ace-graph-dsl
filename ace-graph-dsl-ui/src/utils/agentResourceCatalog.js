import { listAgentResources } from '../api/graph'

/**
 * 拉一类设计期资源。失败与空列表分开，不把异常收成 items: []。
 * @param {'prompts'|'models'|'tools'|'mcp'|'skills'} kind
 * @param {{ agentCode?: string, graphId?: string, agentDefId?: string }} params
 * @returns {Promise<{ items: object[], error: Error|null, empty: boolean }>}
 */
export async function loadAgentResource(kind, params = {}) {
  try {
    const data = await listAgentResources(kind, params)
    const items = Array.isArray(data?.items) ? data.items : []
    return { items, error: null, empty: items.length === 0 }
  } catch (error) {
    return { items: [], error, empty: false }
  }
}

/**
 * Catalog MCP → el-tree 数据（server → 工具）。
 * 无 children 时仅 server 节点，勾选即全选该 server。
 */
export function buildMcpTreeData(mcpItems) {
  return (mcpItems || []).map(s => {
    const children = (s.children || []).map(t => ({
      id: `t:${s.key}:${t.key}`,
      key: t.key,
      serverKey: s.key,
      label: t.label || t.key,
      nodeType: 'tool'
    }))
    return {
      id: `s:${s.key}`,
      key: s.key,
      label: s.label || s.key,
      nodeType: 'server',
      children
    }
  })
}

/** 根据已保存的 mcpKeys + whitelist 还原树勾选 id */
export function mcpCheckedIdsFromSpec(spec, treeData) {
  if (!spec) return []
  const keys = Array.isArray(spec.mcpKeys) ? spec.mcpKeys : []
  const wl = spec.mcpToolWhitelist && typeof spec.mcpToolWhitelist === 'object'
    ? spec.mcpToolWhitelist
    : {}
  const ids = []
  for (const server of keys) {
    const node = (treeData || []).find(n => n.key === server)
    if (!node) {
      ids.push(`s:${server}`)
      continue
    }
    const allow = wl[server]
    if (!allow || !allow.length) {
      ids.push(node.id)
      ;(node.children || []).forEach(c => ids.push(c.id))
    } else {
      ids.push(node.id)
      ;(node.children || []).forEach(c => {
        if (allow.includes(c.key)) ids.push(c.id)
      })
    }
  }
  return ids
}

/** 树勾选 → mcpKeys + mcpToolWhitelist（未勾任何工具 ⇒ 该 server 全选，白名单不写） */
export function mcpBindingFromChecked(treeRef, treeData) {
  const checked = treeRef?.getCheckedNodes?.(false) || []
  const half = treeRef?.getHalfCheckedNodes?.() || []
  const servers = new Set()
  const toolByServer = {}

  ;[...checked, ...half].forEach(n => {
    if (n.nodeType === 'server') servers.add(n.key)
    if (n.nodeType === 'tool' && n.serverKey) {
      servers.add(n.serverKey)
      if (!toolByServer[n.serverKey]) toolByServer[n.serverKey] = []
      if (!toolByServer[n.serverKey].includes(n.key)) {
        toolByServer[n.serverKey].push(n.key)
      }
    }
  })

  const mcpKeys = [...servers]
  const mcpToolWhitelist = {}
  for (const sk of mcpKeys) {
    const serverNode = (treeData || []).find(n => n.key === sk)
    const catalogTools = serverNode?.children || []
    const selected = toolByServer[sk] || []
    if (!catalogTools.length || selected.length === 0 || selected.length === catalogTools.length) {
      continue
    }
    mcpToolWhitelist[sk] = selected
  }
  return { mcpKeys, mcpToolWhitelist }
}
