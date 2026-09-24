import { listAgentResources } from '../api/graph'

/**
 * 拉一类设计期资源。失败与空列表分开，不把异常收成 items: []。
 * @param {'prompts'|'models'|'tools'|'mcp'|'skills'} kind
 * @param {{ agentCode?: string, graphId?: string, agentDefId?: string, otherBizParams?: string }} params
 * @returns {Promise<{ items: object[], error: Error|null, empty: boolean }>}
 */
export async function loadAgentResource(kind, params = {}) {
  try {
    const data = await listAgentResources(kind, params)
    const raw = Array.isArray(data?.items) ? data.items : []
    const items = raw.map(normalizeResourceItem).filter(Boolean)
    return { items, error: null, empty: items.length === 0 }
  } catch (error) {
    return { items: [], error, empty: false }
  }
}

function normalizeResourceItem(it) {
  if (!it || typeof it !== 'object') return null
  const key = String(it.key || it.name || '').trim()
  if (!key) return null
  const children = Array.isArray(it.children)
    ? it.children.map(normalizeResourceItem).filter(Boolean)
    : []
  return {
    key,
    label: it.label || it.displayName || key,
    description: it.description || null,
    children
  }
}

/**
 * 资源编码(显示名称)。没有单独的显示名称时只显示编码，不写 code(code)。
 * 勾选写回仍用 key，不把显示名称写入 mcpKeys / skillKeys。
 */
export function resourceCodeLabel(key, label) {
  const code = String(key || '').trim()
  const name = String(label || '').trim()
  if (!code) return name
  if (!name || name === code) return code
  return `${code}(${name})`
}

/** MCP 树 server 节点用的展示名，规则同 {@link resourceCodeLabel}。 */
export function mcpServerLabel(key, label) {
  return resourceCodeLabel(key, label)
}

/**
 * Catalog MCP → el-tree 数据（server → 工具）。
 * 无 children 时仅 server 节点，勾选即全选该 server。
 * 工具节点只显示资源编码。
 */
export function buildMcpTreeData(mcpItems) {
  return (mcpItems || []).map(s => {
    const children = (s.children || []).map(t => ({
      id: `t:${s.key}:${t.key}`,
      key: t.key,
      serverKey: s.key,
      label: t.key,
      nodeType: 'tool'
    }))
    return {
      id: `s:${s.key}`,
      key: s.key,
      label: mcpServerLabel(s.key, s.label),
      nodeType: 'server',
      children
    }
  })
}

/**
 * Catalog Skill → 扁平勾选树（与 MCP 相同展示形态，无子节点）。
 */
export function buildSkillTreeData(skillItems) {
  return (skillItems || [])
    .map(s => {
      const key = String(s?.key || s?.name || '').trim()
      if (!key) return null
      return {
        id: `sk:${key}`,
        key,
        label: resourceCodeLabel(key, s?.label || s?.displayName),
        nodeType: 'skill'
      }
    })
    .filter(Boolean)
}

/** 已保存 skillKeys → 树勾选 id */
export function skillCheckedIdsFromSpec(skillKeys, treeData) {
  const keys = Array.isArray(skillKeys) ? skillKeys : []
  const ids = []
  for (const raw of keys) {
    const key = String(raw || '').trim()
    if (!key) continue
    const node = (treeData || []).find(n => n.key === key)
    ids.push(node ? node.id : `sk:${key}`)
  }
  return ids
}

/** 树勾选 → skillKeys（资源编码） */
export function skillKeysFromChecked(treeRef) {
  const checked = treeRef?.getCheckedNodes?.(false) || []
  const keys = []
  for (const n of checked) {
    if (n?.nodeType === 'skill' && n.key && !keys.includes(n.key)) {
      keys.push(n.key)
    }
  }
  return keys
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
