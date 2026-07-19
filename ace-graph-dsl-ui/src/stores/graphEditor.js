import { defineStore } from 'pinia'
import { ref, reactive, computed } from 'vue'
import { saveDraft, validateDefinition, previewPlantUml, publish, getLatestDefinition, listVersions, getEnabled } from '../api/graph'
import { canonicalContent, bumpPatchVersion, maxSemver, compareSemver } from '../utils/graphContent'
import { validateEdgeParamReachability } from '../utils/edgeParamValidation'
import { validateTopology } from '../utils/topologyValidation'

export const useGraphEditorStore = defineStore('aceGraphEditor', () => {
  const graphId = ref('')
  const version = ref('1.0.0')
  const displayName = ref('')
  const description = ref('')
  const keyStrategies = reactive({})
  const nodes = ref([])
  const edges = ref([])
  const interruptBefore = ref([])
  const saver = ref('memory')
  const validationErrors = ref([])
  const edgeParamIssues = ref([])
  const plantUmlContent = ref('')
  const versions = ref([])
  const enabledVersion = ref('')
  const baselineCanonical = ref('')
  const baselineVersion = ref('')
  const saving = ref(false)
  const publishing = ref(false)
  const selectedNode = ref(null)
  const selectedLfNodeId = ref(null)
  const selectedEdge = ref(null)
  const edgeEditCommand = ref(null)
  const edgeConvertCommand = ref(null)
  const canUndo = ref(false)
  const canRedo = ref(false)
  const conditionalDrawMode = ref(false)
  const topologyIssues = ref([])
  const minimapVisible = ref(true)
  const groups = ref([])

  // ── 子图下钻（scope stack）：每个条目是父级作用域的快照 ──
  const scopeStack = ref([])
  const rerenderToken = ref(0)
  const graphIds = ref([])

  const RESERVED_NODE_IDS = new Set(['__START__', '__END__', 'lf_start', 'lf_end'])

  function isStartNode(n) {
    return n.properties?.kind === 'START' || n.id === 'lf_start'
  }

  function isEndNode(n) {
    return n.properties?.kind === 'END' || n.id === 'lf_end'
  }

  function normalizeToken(id) {
    if (id === 'lf_start' || id === '__START__') return '__START__'
    if (id === 'lf_end' || id === '__END__') return '__END__'
    if (id === 'lf_error' || id === '__ERROR__') return '__ERROR__'
    return id
  }

  function isReservedNodeId(nodeId) {
    return RESERVED_NODE_IDS.has(nodeId)
  }

  /** 清洗 DSL：去掉误入的业务节点，统一 START/END 保留字，去重条件边 */
  function normalizeDefinition(def) {
    const normalizedNodes = (def.nodes || []).filter(n => !isReservedNodeId(n.nodeId))

    const normalEdges = []
    const conditionalByKey = new Map()

    for (const e of def.edges || []) {
      const from = normalizeToken(e.from)
      if (e.type === 'conditional') {
        const key = `${from}|${e.dispatcher || ''}|${e.condition || ''}`
        if (!conditionalByKey.has(key)) {
          const mapping = {}
          for (const [k, v] of Object.entries(e.mapping || {})) {
            mapping[k] = normalizeToken(v)
          }
          conditionalByKey.set(key, {
            from,
            to: '',
            type: 'conditional',
            dispatcher: e.dispatcher,
            condition: e.condition,
            conditionEngine: e.conditionEngine,
            mapping
          })
        } else {
          // 同一源节点同一路由来源的多条条件边：合并 mapping，避免丢分支
          const existing = conditionalByKey.get(key)
          for (const [k, v] of Object.entries(e.mapping || {})) {
            existing.mapping[k] = normalizeToken(v)
          }
        }
        continue
      }
      const to = normalizeToken(e.to)
      if (from === to) continue
      const type = e.type === 'error' ? 'error' : 'normal'
      normalEdges.push({ from, to, type })
    }

    return {
      ...def,
      nodes: normalizedNodes,
      edges: [...normalEdges, ...conditionalByKey.values()]
    }
  }

  function snapshotBaseline(def) {
    const snapshot = def || buildDefinition()
    baselineCanonical.value = canonicalContent(snapshot)
    if (snapshot?.version) {
      baselineVersion.value = snapshot.version
    }
  }

  function hasContentChanged() {
    return baselineCanonical.value && canonicalContent(buildDefinition()) !== baselineCanonical.value
  }

  function applyDefinition(def) {
    const normalized = normalizeDefinition(def)
    version.value = normalized.version || version.value
    displayName.value = normalized.displayName || ''
    description.value = normalized.description || ''
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
    Object.assign(keyStrategies, normalized.keyStrategies || {})
    nodes.value = normalized.nodes || []
    edges.value = normalized.edges || []
    interruptBefore.value = normalized.compile?.interruptBefore || []
    saver.value = normalized.compile?.saver || 'memory'
    snapshotBaseline(normalized)
    return normalized
  }

  /** 从 LogicFlow 画布数据同步到 store */
  function setFromLfData(lfData, nodeDescriptors) {
    const startNode = lfData.nodes.find(isStartNode)
    const endNode = lfData.nodes.find(isEndNode)

    const nodeIdMap = { lf_start: '__START__', lf_end: '__END__' }
    nodes.value = lfData.nodes
      .filter(n => !isStartNode(n) && !isEndNode(n))
      .filter(n => n.properties?.kind !== 'GROUP')
      .filter(n => n.id !== 'lf_error' && n.properties?.kind !== 'ERROR')
      .map(n => {
        const nodeId = n.properties?.nodeId || n.id
        nodeIdMap[n.id] = nodeId
        return {
          nodeId,
          category: n.properties?.category || 'NORMAL',
          displayName: n.properties?.displayName || '',
          subgraphRef: n.properties?.subgraphRef || '',
          subgraph: n.properties?.subgraph || null,
          config: n.properties?.config || {},
          x: n.x,
          y: n.y
        }
      })
      .filter(n => !isReservedNodeId(n.nodeId))
    if (startNode) nodeIdMap[startNode.id] = '__START__'
    if (endNode) nodeIdMap[endNode.id] = '__END__'
    nodeIdMap['lf_error'] = '__ERROR__'

    const normalEdges = []
    const conditionalByKey = new Map()

    for (const e of lfData.edges) {
      const from = normalizeToken(nodeIdMap[e.sourceNodeId] || e.sourceNodeId)
      if (e.properties?.type === 'conditional') {
        const key = `${from}|${e.properties.dispatcher || ''}|${e.properties.condition || ''}`
        if (!conditionalByKey.has(key)) {
          const mapping = {}
          for (const [k, v] of Object.entries(e.properties.mapping || {})) {
            mapping[k] = normalizeToken(v)
          }
          conditionalByKey.set(key, {
            from,
            to: '',
            type: 'conditional',
            dispatcher: e.properties.dispatcher,
            condition: e.properties.condition,
            conditionEngine: e.properties.conditionEngine,
            mapping
          })
        } else {
          // 同一源节点同一路由来源的多条条件边：合并 mapping，避免丢分支
          const existing = conditionalByKey.get(key)
          for (const [k, v] of Object.entries(e.properties.mapping || {})) {
            existing.mapping[k] = normalizeToken(v)
          }
        }
        continue
      }
      const to = normalizeToken(nodeIdMap[e.targetNodeId] || e.targetNodeId)
      if (from === to) continue
      const type = e.properties?.type === 'error' ? 'error' : 'normal'
      normalEdges.push({ from, to, type })
    }

    edges.value = normalizeDefinition({
      graphId: graphId.value,
      nodes: nodes.value,
      edges: [...normalEdges, ...conditionalByKey.values()]
    }).edges

    // 自动补全 keyStrategies（基于节点 outputKeys）
    const allOutputKeys = new Set()
    for (const n of nodes.value) {
      const desc = nodeDescriptors.find(d => d.nodeId === n.nodeId)
      if (desc) desc.outputKeys.forEach(k => allOutputKeys.add(k))
    }
    for (const k of allOutputKeys) {
      if (!keyStrategies[k]) keyStrategies[k] = 'REPLACE'
    }
    refreshEdgeParamValidation(nodeDescriptors)
  }

  function refreshEdgeParamValidation(nodeDescriptors) {
    if (!graphId.value) {
      edgeParamIssues.value = []
      return
    }
    edgeParamIssues.value = validateEdgeParamReachability(buildDefinition(), nodeDescriptors || [])
  }

  /** 构建完整的 GraphDefinition 对象 */
  function buildDefinition() {
    return normalizeDefinition({
      graphId: graphId.value,
      displayName: displayName.value,
      version: version.value,
      description: description.value,
      keyStrategies: { ...keyStrategies },
      nodes: nodes.value,
      edges: edges.value,
      compile: { interruptBefore: interruptBefore.value, saver: saver.value }
    })
  }

  async function save() {
    saving.value = true
    let collapsedToRoot = false
    try {
      // 内联子图作用域下保存：先把所有内联子图刷新回父节点，再回到根图保存（引用型子图则直接保存被引用图）
      if (scopeStack.value.length) {
        const top = scopeStack.value[scopeStack.value.length - 1]
        if (top.kind === 'inline') {
          flushAllSubgraphs()
          if (scopeStack.value.length) restoreFrame(scopeStack.value[0])
          scopeStack.value = []
          collapsedToRoot = true
          requestRerender()
        }
      }
      const def = buildDefinition()
      const result = await saveDraft(graphId.value, def, baselineVersion.value || def.version)
      if (!result.changed) {
        return { ok: true, unchanged: true, result, collapsedToRoot }
      }
      baselineVersion.value = def.version
      snapshotBaseline(result.definition || def)
      await fetchVersions()
      return { ok: true, unchanged: false, result, collapsedToRoot }
    } finally {
      saving.value = false
    }
  }

  function versionExists(target) {
    return (versions.value || []).some(v => v.version === target)
  }

  function maxKnownVersion() {
    const vers = (versions.value || []).map(v => v.version).filter(Boolean)
    return maxSemver(vers)
  }

  /** 保存前检查：内容变更时版本号须未占用且大于历史最大版本 */
  function needsVersionBump() {
    if (!hasContentChanged()) return false
    const current = version.value
    if (versionExists(current)) return true
    const max = maxKnownVersion()
    if (max && compareSemver(current, max) <= 0) return true
    return false
  }

  function suggestNextVersion() {
    return bumpPatchVersion(maxKnownVersion() || version.value || '1.0.0')
  }

  async function validate() {
    const def = buildDefinition()
    const result = await validateDefinition(graphId.value, def)
    validationErrors.value = result.errors || []
    return result
  }

  /** 整体拓扑校验（环 / END 可达 / 不可达 / 孤立节点），结果存入 topologyIssues */
  function validateTopologyNow() {
    const res = validateTopology(buildDefinition())
    topologyIssues.value = res.issues
    return res
  }

  async function loadPlantUml() {
    const def = buildDefinition()
    plantUmlContent.value = await previewPlantUml(graphId.value, def)
  }

  async function publishCurrent(operator = 'designer') {
    publishing.value = true
    try {
      const saveResult = await save()
      if (!saveResult.ok) return { success: false, message: '保存失败' }
      if (saveResult.unchanged && !hasContentChanged()) {
        // 无内容变更也可发布（重新启用当前版本）
      }
      const def = buildDefinition()
      const result = await publish(graphId.value, def.version, operator)
      if (result.success) {
        enabledVersion.value = def.version
      }
      return result
    } finally {
      publishing.value = false
    }
  }

  async function loadEnabledVersion() {
    if (!graphId.value) return null
    try {
      const def = await getEnabled(graphId.value)
      enabledVersion.value = def?.version || ''
      return def
    } catch {
      enabledVersion.value = ''
      return null
    }
  }

  function resetGraphScopeState() {
    versions.value = []
    enabledVersion.value = ''
    baselineVersion.value = ''
    baselineCanonical.value = ''
    validationErrors.value = []
    edgeParamIssues.value = []
    plantUmlContent.value = ''
    selectedNode.value = null
    selectedLfNodeId.value = null
    canUndo.value = false
    canRedo.value = false
    topologyIssues.value = []
    groups.value = []
    scopeStack.value = []
  }

  async function loadLatest() {
    if (!graphId.value) return null
    let def
    try {
      def = await getLatestDefinition(graphId.value)
    } catch (e) {
      console.warn('[graphEditor] getLatestDefinition failed:', graphId.value, e)
      versions.value = []
      enabledVersion.value = ''
      return null
    }
    const normalized = applyDefinition(def)
    try {
      await fetchVersions()
    } catch (e) {
      console.warn('[graphEditor] fetchVersions failed:', graphId.value, e)
      versions.value = normalized?.version
        ? [{ version: normalized.version, graphId: graphId.value }]
        : []
    }
    return normalized
  }

  function selectGraph(id) {
    graphId.value = id
    resetGraphScopeState()
    scopeStack.value = []
  }

  function initNewGraph(id, meta = {}) {
    graphId.value = id
    resetGraphScopeState()
    version.value = meta.version || '1.0.0'
    baselineVersion.value = version.value
    displayName.value = meta.displayName || id
    description.value = meta.description || ''
    nodes.value = []
    edges.value = []
    interruptBefore.value = meta.interruptBefore || []
    saver.value = meta.saver || 'memory'
    scopeStack.value = []
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
    snapshotBaseline(buildDefinition())
  }

  function resetEditor() {
    version.value = '1.0.0'
    displayName.value = ''
    description.value = ''
    nodes.value = []
    edges.value = []
    interruptBefore.value = []
    saver.value = 'memory'
    validationErrors.value = []
    edgeParamIssues.value = []
    plantUmlContent.value = ''
    canUndo.value = false
    canRedo.value = false
    conditionalDrawMode.value = false
    topologyIssues.value = []
    minimapVisible.value = true
    groups.value = []
    scopeStack.value = []
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
  }

  async function fetchVersions() {
    versions.value = await listVersions(graphId.value)
  }

  function setSelectedNode(lfNodeId, nodeId, config, category) {
    selectedLfNodeId.value = lfNodeId
    selectedNode.value = { nodeId, config: config ? { ...config } : {}, category: category || null }
  }

  function clearSelectedNode() {
    selectedLfNodeId.value = null
    selectedNode.value = null
  }

  function setSelectedEdge(edge) {
    selectedEdge.value = edge
  }

  function clearSelectedEdge() {
    selectedEdge.value = null
  }

  /** 请求画布按补丁重建条件边分组（conditionEngine / condition / mapping） */
  function requestEdgeEdit(cmd) {
    edgeEditCommand.value = { ...cmd, _t: Date.now() }
  }

  /** 请求画布将普通边转换为条件边 */
  function requestEdgeConvert(lfEdgeId) {
    edgeConvertCommand.value = lfEdgeId
  }

  function clearEdgeCommands() {
    edgeEditCommand.value = null
    edgeConvertCommand.value = null
  }

  function updateSelectedNodeConfig(config) {
    if (selectedNode.value) {
      selectedNode.value.config = { ...config }
    }
  }

  function loadVersionAsBaseline(def) {
    const normalized = applyDefinition(def)
    baselineVersion.value = normalized.version
    snapshotBaseline(normalized)
    return normalized
  }

  // ── 子图下钻（drill-down） ──
  const rootGraphId = computed(() => scopeStack.value.length ? scopeStack.value[0].graphId : graphId.value)
  const rootDisplayName = computed(() => scopeStack.value.length ? scopeStack.value[0].displayName : displayName.value)
  const isDrilledIn = computed(() => scopeStack.value.length > 0)
  const currentScopeKind = computed(() => scopeStack.value.length ? scopeStack.value[scopeStack.value.length - 1].kind : 'root')
  const breadcrumb = computed(() => {
    const items = [{ kind: 'root', graphId: rootGraphId.value, label: rootDisplayName.value, level: 0 }]
    scopeStack.value.forEach((f, i) => {
      items.push({
        kind: f.kind,
        graphId: f.graphId,
        owningNodeId: f.owningNodeId,
        subgraphRef: f.subgraphRef,
        label: f.label,
        level: i + 1
      })
    })
    return items
  })

  function requestRerender() {
    rerenderToken.value++
  }

  function captureFrame(kind, owningNodeId, subgraphRef, label) {
    return {
      graphId: graphId.value,
      version: version.value,
      displayName: displayName.value,
      description: description.value,
      keyStrategies: { ...keyStrategies },
      nodes: nodes.value,
      edges: edges.value,
      interruptBefore: interruptBefore.value,
      saver: saver.value,
      baselineVersion: baselineVersion.value,
      baselineCanonical: baselineCanonical.value,
      kind, owningNodeId, subgraphRef, label
    }
  }

  function restoreFrame(f) {
    graphId.value = f.graphId
    version.value = f.version
    displayName.value = f.displayName
    description.value = f.description
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
    Object.assign(keyStrategies, f.keyStrategies || {})
    nodes.value = f.nodes
    edges.value = f.edges
    interruptBefore.value = f.interruptBefore
    saver.value = f.saver
    baselineVersion.value = f.baselineVersion
    baselineCanonical.value = f.baselineCanonical
  }

  function loadWorkingFromDefinition(def, fallbackId) {
    const normalized = normalizeDefinition(def)
    graphId.value = normalized.graphId || fallbackId || graphId.value
    version.value = normalized.version || '1.0.0'
    displayName.value = normalized.displayName || ''
    description.value = normalized.description || ''
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
    Object.assign(keyStrategies, normalized.keyStrategies || {})
    nodes.value = normalized.nodes || []
    edges.value = normalized.edges || []
    interruptBefore.value = normalized.compile?.interruptBefore || []
    saver.value = normalized.compile?.saver || 'memory'
    clearSelectedNode()
    clearSelectedEdge()
  }

  /** 进入子图节点：内联子图切到子作用域；引用型子图加载被引用图作为独立作用域 */
  async function enterSubgraph(nodeId) {
    const node = nodes.value.find(n => n.nodeId === nodeId)
    if (!node || node.category !== 'SUBGRAPH') return
    const kind = node.subgraphRef ? 'reference' : 'inline'
    scopeStack.value.push(captureFrame(kind, nodeId, node.subgraphRef || '', node.displayName || node.nodeId))
    let childDef
    if (node.subgraph) {
      childDef = node.subgraph
    } else if (node.subgraphRef) {
      try {
        childDef = await getLatestDefinition(node.subgraphRef)
      } catch (e) {
        console.warn('[graphEditor] load subgraph ref failed:', node.subgraphRef, e)
        childDef = { graphId: node.subgraphRef, displayName: node.subgraphRef, version: '1.0.0', nodes: [], edges: [] }
      }
    } else {
      childDef = { graphId: nodeId, displayName: node.displayName || node.nodeId, version: '1.0.0', nodes: [], edges: [] }
    }
    loadWorkingFromDefinition(childDef, nodeId)
    requestRerender()
  }

  /** 将当前作用域（内联子图）的最新编辑写回父节点的 subgraph 字段 */
  function flushCurrentIntoParent() {
    const top = scopeStack.value[scopeStack.value.length - 1]
    if (!top || top.kind !== 'inline') return
    const def = buildDefinition()
    const parentNodes = top.nodes
    const node = parentNodes.find(n => n.nodeId === top.owningNodeId)
    if (node) {
      node.subgraph = def
      node.subgraphRef = ''
    }
  }

  /** 退出一级子图：刷新内联子图、恢复父作用域 */
  function exitSubgraph() {
    if (!scopeStack.value.length) return
    flushCurrentIntoParent()
    const popped = scopeStack.value.pop()
    if (scopeStack.value.length) {
      restoreFrame(scopeStack.value[scopeStack.value.length - 1])
    } else {
      restoreFrame(popped)
    }
    requestRerender()
  }

  /** 跳转到面包屑指定层级（level 0 = 根图） */
  function goToBreadcrumb(level) {
    while (scopeStack.value.length > level) exitSubgraph()
  }

  /** 保存前将所有内联子图逐级刷新回根图节点 */
  function flushAllSubgraphs() {
    let curDef = buildDefinition()
    let curOwningNodeId = scopeStack.value.length ? scopeStack.value[scopeStack.value.length - 1].owningNodeId : null
    for (let k = scopeStack.value.length - 1; k >= 0; k--) {
      const frame = scopeStack.value[k]
      if (frame.kind === 'inline' && curOwningNodeId) {
        const node = frame.nodes.find(n => n.nodeId === curOwningNodeId)
        if (node) { node.subgraph = curDef; node.subgraphRef = '' }
      }
      curDef = normalizeDefinition({
        graphId: frame.graphId,
        displayName: frame.displayName,
        version: frame.version,
        description: frame.description,
        keyStrategies: frame.keyStrategies || {},
        nodes: frame.nodes,
        edges: frame.edges,
        compile: { interruptBefore: frame.interruptBefore, saver: frame.saver }
      })
      curOwningNodeId = frame.owningNodeId
    }
  }

  /** 修改子图节点的元信息（名称 / 引用 / 内联模式） */
  function updateSubgraphNodeMeta({ nodeId, displayName, subgraphRef, mode }) {
    const node = nodes.value.find(n => n.nodeId === nodeId)
    if (!node) return
    if (displayName !== undefined) node.displayName = displayName
    if (mode === 'reference') {
      node.subgraphRef = subgraphRef || ''
      node.subgraph = null
    } else if (mode === 'inline') {
      node.subgraphRef = ''
      if (!node.subgraph) {
        node.subgraph = {
          graphId: nodeId,
          displayName: node.displayName || nodeId,
          version: '1.0.0',
          nodes: [],
          edges: []
        }
      }
    }
    requestRerender()
  }

  /** 重命名当前选中的结构型节点（子图 / Agent），同步更新相关边 */
  function renameSelectedNode(newId) {
    if (!selectedNode.value) return
    const oldId = selectedNode.value.nodeId
    if (!newId || newId === oldId) return
    const node = nodes.value.find(n => n.nodeId === oldId)
    if (node) node.nodeId = newId
    edges.value.forEach(e => {
      if (e.from === oldId) e.from = newId
      if (e.to === oldId) e.to = newId
      if (e.type === 'conditional' && e.mapping) {
        for (const k of Object.keys(e.mapping)) {
          if (e.mapping[k] === oldId) e.mapping[k] = newId
        }
      }
    })
    selectedNode.value = { ...selectedNode.value, nodeId: newId }
    requestRerender()
  }

  async function loadGraphIds() {
    try {
      graphIds.value = await listGraphIds()
    } catch (e) {
      graphIds.value = []
    }
  }

  return {
    graphId, version, displayName, description, keyStrategies,
    nodes, edges, interruptBefore, saver,
    validationErrors, edgeParamIssues, plantUmlContent, versions, enabledVersion, baselineVersion,
    saving, publishing,     selectedNode, selectedLfNodeId, selectedEdge, edgeEditCommand, edgeConvertCommand,
    canUndo, canRedo, conditionalDrawMode, topologyIssues, minimapVisible, groups,
    scopeStack, rerenderToken, graphIds,
    isDrilledIn, currentScopeKind, breadcrumb, rootGraphId, rootDisplayName,
    setFromLfData, applyDefinition, normalizeDefinition, buildDefinition, save, validate, loadPlantUml,
    refreshEdgeParamValidation,
    hasContentChanged, needsVersionBump, suggestNextVersion, snapshotBaseline, loadVersionAsBaseline, validateTopologyNow,
    maxKnownVersion, versionExists,
    publishCurrent, loadLatest, loadEnabledVersion, fetchVersions, selectGraph, initNewGraph, resetEditor,
    setSelectedNode, clearSelectedNode, updateSelectedNodeConfig,
    setSelectedEdge, clearSelectedEdge, requestEdgeEdit, requestEdgeConvert, clearEdgeCommands,
    enterSubgraph, exitSubgraph, goToBreadcrumb, updateSubgraphNodeMeta, renameSelectedNode, requestRerender, loadGraphIds
  }
})
