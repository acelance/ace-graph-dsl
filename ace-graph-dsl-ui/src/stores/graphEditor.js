import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'
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
        }
        continue
      }
      const to = normalizeToken(e.to)
      if (from === to) continue
      normalEdges.push({ from, to, type: 'normal' })
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
      .map(n => {
        const nodeId = n.properties?.nodeId || n.id
        nodeIdMap[n.id] = nodeId
        return { nodeId, config: n.properties?.config || {} }
      })
      .filter(n => !isReservedNodeId(n.nodeId))
    if (startNode) nodeIdMap[startNode.id] = '__START__'
    if (endNode) nodeIdMap[endNode.id] = '__END__'

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
        }
        continue
      }
      const to = normalizeToken(nodeIdMap[e.targetNodeId] || e.targetNodeId)
      if (from === to) continue
      normalEdges.push({ from, to, type: 'normal' })
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
    try {
      const def = buildDefinition()
      const result = await saveDraft(graphId.value, def, baselineVersion.value || def.version)
      if (!result.changed) {
        return { ok: true, unchanged: true, result }
      }
      baselineVersion.value = def.version
      snapshotBaseline(result.definition || def)
      await fetchVersions()
      return { ok: true, unchanged: false, result }
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
    Object.keys(keyStrategies).forEach(k => delete keyStrategies[k])
  }

  async function fetchVersions() {
    versions.value = await listVersions(graphId.value)
  }

  function setSelectedNode(lfNodeId, nodeId, config) {
    selectedLfNodeId.value = lfNodeId
    selectedNode.value = { nodeId, config: config ? { ...config } : {} }
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

  return {
    graphId, version, displayName, description, keyStrategies,
    nodes, edges, interruptBefore, saver,
    validationErrors, edgeParamIssues, plantUmlContent, versions, enabledVersion, baselineVersion,
    saving, publishing,     selectedNode, selectedLfNodeId, selectedEdge, edgeEditCommand, edgeConvertCommand,
    canUndo, canRedo, conditionalDrawMode, topologyIssues, minimapVisible, groups,
    setFromLfData, applyDefinition, normalizeDefinition, buildDefinition, save, validate, loadPlantUml,
    refreshEdgeParamValidation,
    hasContentChanged, needsVersionBump, suggestNextVersion, snapshotBaseline, loadVersionAsBaseline, validateTopologyNow,
    maxKnownVersion, versionExists,
    publishCurrent, loadLatest, loadEnabledVersion, fetchVersions, selectGraph, initNewGraph, resetEditor,
    setSelectedNode, clearSelectedNode, updateSelectedNodeConfig,
    setSelectedEdge, clearSelectedEdge, requestEdgeEdit, requestEdgeConvert, clearEdgeCommands
  }
})
