<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { Delete, Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import LogicFlow from '@logicflow/core'
import { MiniMap, Snapshot, SelectionSelect } from '@logicflow/extension'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'
import { DspRectNode, DspDiamondNode, DspCircleNode, DspGroupNode, DspSubgraphNode, DspAgentNode, resolveNodeType } from './DspNode.js'
import { DspBezierEdge } from './DspEdge.js'

const nodeStore = useNodeRegistryStore()
const editor = useGraphEditorStore()
const { t } = useI18n()

const containerRef = ref(null)
const canDeleteSelection = ref(false)
/** 当前作用域是否存在流式/异步节点，决定是否显示画布图例 */
const hasStreamingNode = computed(() => (editor.nodes || []).some(n => n.config && n.config.streaming))
/** 框选模式（SelectionSelect 独占）是否开启 */
const selectionSelectActive = ref(false)
let lf = null
let suppressSync = false
let groupLastPos = {}
let lfReadyResolve
const lfReady = new Promise(resolve => { lfReadyResolve = resolve })

/** Win/Linux 用 Ctrl，macOS 用 Cmd，以便点选多选 */
function detectMultipleSelectKey() {
  const platform = navigator.platform || ''
  const ua = navigator.userAgent || ''
  if (/Mac|iPhone|iPad|iPod/i.test(platform) || /Mac OS/i.test(ua)) return 'meta'
  return 'ctrl'
}

onMounted(() => { initLf() })
onBeforeUnmount(() => {
  if (lf) {
    lf.keyboard?.off?.(['delete'])
    lf.clearData()
  }
})

watch(() => editor.selectedNode?.config, (config) => {
  if (!lf || !editor.selectedLfNodeId || !config) return
  const model = lf.getNodeModelById(editor.selectedLfNodeId)
  if (!model) return
  suppressSync = true
  try {
    lf.setProperties(editor.selectedLfNodeId, { ...model.properties, config: { ...config } })
  } finally {
    suppressSync = false
  }
}, { deep: true })

watch(() => editor.edgeParamIssues, () => {
  applyEdgeValidationStyles()
}, { deep: true })

watch(() => editor.edgeEditCommand, (cmd) => {
  if (cmd) applyConditionalEdgeEdit(cmd)
})

watch(() => editor.edgeConvertCommand, (lfEdgeId) => {
  if (lfEdgeId) convertEdgeToConditional(lfEdgeId)
})

// 子图下钻 / 结构型节点元信息变更后，由 store 触发整图重渲染
watch(() => editor.rerenderToken, () => {
  if (lf) renderFromDefinition(editor.buildDefinition())
})

function detectCanvasTheme() {
  const root = containerRef.value?.closest('.ace-graph-dsl-designer, .ace-graph-dsl-manager')
  if (root?.classList.contains('dark') || document.documentElement.classList.contains('dark')) {
    return 'dark'
  }
  return 'light'
}

function baseProperties(extra = {}) {
  return { theme: detectCanvasTheme(), ...extra }
}

function isReservedLfNodeId(id) {
  return id === 'lf_start' || id === 'lf_end' || id === 'lf_error'
}

function isReservedNodeData(data) {
  return isReservedLfNodeId(data?.id)
    || data?.properties?.kind === 'START'
    || data?.properties?.kind === 'END'
    || data?.properties?.kind === 'ERROR'
}

function refreshSelectionState() {
  if (!lf) {
    canDeleteSelection.value = false
    return
  }
  const { nodes, edges } = lf.getSelectElements(true)
  const deletableNodes = nodes.filter(n => !isReservedNodeData(n))
  canDeleteSelection.value = deletableNodes.length > 0 || edges.length > 0
}

function deleteSelectedElements() {
  if (!lf) return false
  const elements = lf.getSelectElements(true)
  const nodes = elements.nodes.filter(n => !isReservedNodeData(n))
  const edges = elements.edges
  if (!nodes.length && !edges.length) return false

  // 分组清理：删除分组容器时解组成员；删除成员时从分组移除
  const deletedGroupIds = new Set()
  nodes.forEach(n => {
    if (n.properties?.kind === 'GROUP') deletedGroupIds.add(n.id)
  })
  if (deletedGroupIds.size) {
    deletedGroupIds.forEach(gid => {
      const g = editor.groups.find(x => x.id === gid)
      if (g) g.memberIds.forEach(mid => {
        const m = lf.getNodeModelById(mid)
        if (m) { m.visible = true; const p = { ...m.properties }; delete p.groupId; lf.setProperties(mid, p) }
      })
    })
    editor.groups = editor.groups.filter(x => !deletedGroupIds.has(x.id))
  }
  nodes.forEach(n => {
    const gid = n.properties?.groupId
    if (gid && !deletedGroupIds.has(gid)) {
      const g = editor.groups.find(x => x.id === gid)
      if (g) g.memberIds = g.memberIds.filter(id => id !== n.id)
    }
  })

  lf.clearSelectElements()
  edges.forEach(edge => edge.id && lf.deleteEdge(edge.id))
  nodes.forEach(node => node.id && lf.deleteNode(node.id))
  editor.clearSelectedNode()
  editor.clearSelectedEdge()
  refreshSelectionState()
  syncToStore()
  return true
}

function registerDeleteShortcut() {
  lf.keyboard.on(['delete'], () => {
    if (!lf.options.keyboard?.enabled) return true
    if (lf.graphModel.textEditElement) return true
    return deleteSelectedElements() ? false : true
  })
}

function refreshHistoryState() {
  const h = lf.history
  if (h) {
    editor.canUndo = !!h.undoAble
    editor.canRedo = !!h.redoAble
  }
}

function undo() {
  if (!lf) return
  lf.undo()
  refreshHistoryState()
}

function redo() {
  if (!lf) return
  lf.redo()
  refreshHistoryState()
}

/** 复制选中业务节点（排除 START/END）到剪贴板 */
let clipboard = null
function copySelection() {
  if (!lf) return
  const { nodes } = lf.getSelectElements(true)
  const biz = nodes.filter(n => !isReservedNodeData(n))
  if (!biz.length) return
  clipboard = biz.map(n => ({
    nodeId: n.properties?.nodeId || n.id,
    displayName: n.properties?.displayName,
    category: n.properties?.category || 'NORMAL',
    inputKeys: n.properties?.inputKeys,
    outputKeys: n.properties?.outputKeys,
    config: n.properties?.config || {},
    x: n.x,
    y: n.y
  }))
  ElMessage.success(t('canvas.copied', { n: clipboard.length }))
}

/** 粘贴剪贴板节点，偏移避免重叠并选中 */
function pasteSelection() {
  if (!lf || !clipboard || !clipboard.length) return
  const offset = 50
  const ids = []
  clipboard.forEach((c, i) => {
    const id = `${c.nodeId}_${Date.now()}_${i}`
    lf.addNode({
      id,
      type: resolveNodeType(c.category),
      x: c.x + offset,
      y: c.y + offset,
      text: c.displayName,
      properties: baseProperties({
        nodeId: c.nodeId,
        displayName: c.displayName,
        category: c.category,
        inputKeys: c.inputKeys,
        outputKeys: c.outputKeys,
        config: c.config
      })
    })
    ids.push(id)
  })
  lf.clearSelectElements()
  ids.forEach(id => {
    const m = lf.getNodeModelById(id)
    if (m) lf.selectElement(m, true)
  })
  syncToStore()
}

function registerCopyPasteShortcut() {
  lf.keyboard.on(['ctrl+c', 'meta+c'], () => {
    copySelection()
    return false
  })
  lf.keyboard.on(['ctrl+v', 'meta+v'], () => {
    pasteSelection()
    return false
  })
}

function applyLfTheme() {
  const isDark = detectCanvasTheme() === 'dark'
  const text = isDark ? '#e5eaf3' : '#303133'
  const edgeStroke = isDark ? '#6b7d93' : '#8b9cb3'

  if (containerRef.value) {
    containerRef.value.style.backgroundColor = isDark ? '#141414' : '#fafafa'
  }

  lf.setTheme({
    baseNode: { fill: '#FFFFFF', stroke: '#dcdfe6', strokeWidth: 1 },
    circle: { stroke: '#06b6d4', strokeWidth: 2 },
    rect: { fill: '#FFFFFF', stroke: '#dcdfe6', strokeWidth: 1 },
    polygon: { strokeWidth: 2 },
    polyline: {
      stroke: edgeStroke,
      hoverStroke: '#409eff',
      selectedStroke: '#409eff',
      strokeWidth: 1.5,
    },
    bezier: {
      stroke: edgeStroke,
      hoverStroke: '#409eff',
      selectedStroke: '#409eff',
      strokeWidth: 1.5,
    },
    nodeText: {
      color: text,
      overflowMode: 'ellipsis',
      fontSize: 13,
      background: { fill: 'transparent' },
    },
    edgeText: {
      color: text,
      fontSize: 12,
      background: { fill: isDark ? '#1d1e1f' : '#ffffff', stroke: '#e4e7ed', radius: 4 },
    },
  })
}

function registerCustomElements() {
  lf.register(DspRectNode)
  lf.register(DspDiamondNode)
  lf.register(DspCircleNode)
  lf.register(DspGroupNode)
  lf.register(DspSubgraphNode)
  lf.register(DspAgentNode)
  lf.register(DspBezierEdge)
  lf.setDefaultEdgeType('dsp-bezier')
  applyLfTheme()
}

function initLf() {
  lf = new LogicFlow({
    container: containerRef.value,
    grid: { size: 10, visible: true, type: 'dot' },
    plugins: [MiniMap, Snapshot, SelectionSelect],
    keyboard: { enabled: true },
    edgeTextDraggable: false,
    adjustEdge: true,
    hoverOutline: true,
    edgeSelectedOutline: true,
    // 默认 '' 时无法多选，成组按钮会始终提示「请先选中至少 2 个业务节点」
    multipleSelectKey: detectMultipleSelectKey(),
    guards: {
      beforeDelete: (data) => !isReservedNodeData(data)
    }
  })
  registerCustomElements()
  lf.render({ nodes: [], edges: [] })
  ensureStartEndNodes()
  registerDeleteShortcut()
  registerCopyPasteShortcut()

  lf.on('history:change', (payload) => {
    const d = (payload && payload.data) || payload || {}
    editor.canUndo = !!d.undoAble
    editor.canRedo = !!d.redoAble
  })
  lf.on('node:add,node:delete,node:dnd-add,edge:add,edge:delete,node:properties-update', syncToStore)
  // 条件边绘制模式：用户新拉的普通边自动转为条件边（加载/渲染时不触发，避免误转已有边）
  lf.on('edge:add', ({ data }) => {
    if (editor.conditionalDrawMode && !suppressSync && data && data.properties?.type !== 'conditional') {
      convertEdgeToConditional(data.id)
    }
  })
  lf.on('node:click', ({ data }) => {
    refreshSelectionState()
    editor.clearSelectedEdge()
    if (data.properties?.kind === 'START' || data.properties?.kind === 'END' || data.properties?.kind === 'ERROR') {
      editor.clearSelectedNode()
      return
    }
    editor.setSelectedNode(data.id, data.properties?.nodeId || data.id, data.properties?.config || {}, data.properties?.category)
  })
  lf.on('node:dblclick', ({ data }) => {
    // 双击子图节点进入下钻（单点击仅选中，便于配置引用/内联）
    if (data.properties?.category === 'SUBGRAPH') {
      editor.enterSubgraph(data.properties?.nodeId || data.id)
    }
  })
  lf.on('edge:click', ({ data }) => {
    editor.clearSelectedNode()
    selectEdgeModel(data)
    refreshSelectionState()
  })
  lf.on('blank:click', () => {
    editor.clearSelectedNode()
    editor.clearSelectedEdge()
    refreshSelectionState()
  })
  lf.on('element:click', () => refreshSelectionState())
  lf.on('selection:selected', () => {
    refreshSelectionState()
    // 框选完成后自动退出独占模式，便于立刻点「子流程」成组
    if (selectionSelectActive.value) {
      closeSelectionSelectMode()
    }
  })
  // 子流程分组：拖拽容器时同步移动成员
  lf.on('node:dragstart', ({ data }) => {
    if (data?.properties?.kind === 'GROUP') groupLastPos[data.id] = { x: data.x, y: data.y }
  })
  lf.on('node:drag', ({ data }) => {
    if (data?.properties?.kind === 'GROUP') moveGroupMembers(data.id, data.x, data.y)
  })
  lf.on('node:dragend', ({ data }) => {
    if (data?.properties?.kind === 'GROUP') delete groupLastPos[data.id]
  })
  // 缩略图默认显示
  try { lf.extension?.miniMap?.show?.() } catch (e) { /* noop */ }
  lfReadyResolve?.()
}

function closeSelectionSelectMode() {
  if (!lf) return
  try { lf.extension?.selectionSelect?.closeSelectionSelect?.() } catch (e) { /* noop */ }
  selectionSelectActive.value = false
}

/** 切换框选：开启后在画布空白处拖拽框住多个节点，再点「子流程」成组 */
function toggleSelectionSelect() {
  if (!lf) return
  const ext = lf.extension?.selectionSelect
  if (!ext) {
    ElMessage.warning(t('toolbar.boxSelectUnavailable'))
    return
  }
  if (selectionSelectActive.value) {
    closeSelectionSelectMode()
    return
  }
  try {
    ext.setExclusiveMode?.(true)
    ext.openSelectionSelect()
    selectionSelectActive.value = true
    ElMessage.info(t('toolbar.boxSelectHint'))
  } catch (e) {
    selectionSelectActive.value = false
    ElMessage.warning(t('toolbar.boxSelectUnavailable'))
  }
}

function resolveLfTarget(token, idMap) {
  if (!token) return ''
  if (token === '__START__' || token === 'lf_start') return 'lf_start'
  if (token === '__END__' || token === 'lf_end') return 'lf_end'
  if (token === '__ERROR__' || token === 'lf_error') return 'lf_error'
  return idMap[token] || token
}

function renderLfGraph(lfNodes, lfEdges) {
  applyLfTheme()
  try {
    lf.render({ nodes: lfNodes, edges: lfEdges })
  } catch (err) {
    console.error('[Canvas] dsp-bezier render failed, fallback to polyline:', err)
    const fallbackEdges = lfEdges.map(edge => ({ ...edge, type: 'polyline' }))
    lf.render({ nodes: lfNodes, edges: fallbackEdges })
  }
}

function syncToStore() {
  if (suppressSync) return
  const data = lf.getGraphData()
  editor.setFromLfData(data, nodeStore.nodes)
  if (editor.selectedLfNodeId) {
    const node = data.nodes.find(n => n.id === editor.selectedLfNodeId)
    if (node) {
      editor.setSelectedNode(node.id, node.properties?.nodeId, node.properties?.config || {})
    } else {
      editor.clearSelectedNode()
    }
  }
  refreshSelectionState()
  applyEdgeValidationStyles()
}

function lfNodeToDslId(node) {
  if (!node) return ''
  if (node.id === 'lf_start' || node.properties?.kind === 'START') return '__START__'
  if (node.id === 'lf_end' || node.properties?.kind === 'END') return '__END__'
  if (node.id === 'lf_error' || node.properties?.kind === 'ERROR') return '__ERROR__'
  return node.properties?.nodeId || node.id.replace(/^lf_/, '')
}

/** 仅对状态变化的边 setProperties，避免全量重绘 */
function applyEdgeValidationStyles() {
  if (!lf) return
  const invalidKeys = new Set((editor.edgeParamIssues || []).map(i => i.edgeKey))
  const { nodes, edges } = lf.getGraphData()
  const nodeById = new Map(nodes.map(n => [n.id, n]))

  for (const edge of edges) {
    const fromDsl = lfNodeToDslId(nodeById.get(edge.sourceNodeId))
    const toDsl = lfNodeToDslId(nodeById.get(edge.targetNodeId))
    const edgeKey = `${fromDsl}->${toDsl}`
    const shouldInvalid = invalidKeys.has(edgeKey)
    const currentInvalid = edge.properties?.paramInvalid === true
    if (shouldInvalid === currentInvalid) continue
    lf.setProperties(edge.id, { ...edge.properties, paramInvalid: shouldInvalid })
  }
}

function isStartNode(n) {
  return n.properties?.kind === 'START' || n.id === 'lf_start'
}

function isEndNode(n) {
  return n.properties?.kind === 'END' || n.id === 'lf_end'
}

function ensureStartEndNodes() {
  if (!lf) return
  const data = lf.getGraphData()
  if (!data.nodes.some(isStartNode)) {
    lf.addNode({
      id: 'lf_start', type: 'dsp-circle', x: 120, y: 200, text: 'START',
      properties: baseProperties({ kind: 'START' })
    })
  }
  if (!data.nodes.some(isEndNode)) {
    lf.addNode({
      id: 'lf_end', type: 'dsp-circle', x: 680, y: 200, text: 'END',
      properties: baseProperties({ kind: 'END' })
    })
  }
}

function onNodeDrag(descriptor) {
  let category = descriptor.category || 'NORMAL'
  let nodeId = descriptor.nodeId
  let displayName = descriptor.displayName
  if (descriptor.isStructural) {
    const base = category === 'SUBGRAPH' ? 'subgraph' : category === 'AGENT' ? 'agent' : category.toLowerCase()
    nodeId = `${base}_${Date.now()}`
    displayName = descriptor.displayName || nodeId
  }
  const id = `${nodeId}_${Date.now()}`
  lf.addNode({
    id,
    type: resolveNodeType(category),
    x: 300,
    y: 200,
    text: displayName,
    properties: baseProperties({
      nodeId,
      displayName,
      category,
      inputKeys: descriptor.inputKeys || [],
      outputKeys: descriptor.outputKeys || [],
      config: {},
      subgraphRef: '',
      subgraph: null
    })
  })
}

const RESERVED_NODE_IDS = new Set(['__START__', '__END__', 'lf_start', 'lf_end'])

function renderFromDefinition(def) {
  if (!def || !lf) return
  const businessNodes = (def.nodes || []).filter(n => !RESERVED_NODE_IDS.has(n.nodeId))
  const lfNodes = []
  const lfEdges = []

  lfNodes.push({
    id: 'lf_start', type: 'dsp-circle', x: 50, y: 200, text: 'START',
    properties: baseProperties({ kind: 'START' })
  })

  businessNodes.forEach((n, i) => {
    const desc = nodeStore.nodes.find(d => d.nodeId === n.nodeId)
    const category = n.category || desc?.category || 'NORMAL'
    // 同名节点（相同 nodeId）需保证 LF id 唯一，避免拖拽/选中相互干扰
    const id = `lf_${n.nodeId}_${i}`
    // 优先使用已保存的画布坐标；无坐标（历史数据/新建）时回退到分层网格，避免每次进入都重排成格子
    const px = typeof n.x === 'number' ? n.x : 200 + 200 * (i + 1)
    const py = typeof n.y === 'number' ? n.y : 150 + (i % 3) * 100
    lfNodes.push({
      id,
      type: resolveNodeType(category),
      x: px,
      y: py,
      text: n.displayName || desc?.displayName || n.nodeId,
      properties: baseProperties({
        nodeId: n.nodeId,
        displayName: n.displayName || desc?.displayName || '',
        category,
        config: n.config || {},
        inputKeys: desc?.inputKeys || [],
        outputKeys: desc?.outputKeys || [],
        subgraphRef: n.subgraphRef || '',
        subgraph: n.subgraph || null
      })
    })
  })

  lfNodes.push({
    id: 'lf_end', type: 'dsp-circle', x: 900, y: 200, text: 'END',
    properties: baseProperties({ kind: 'END' })
  })

  lfNodes.push({
    id: 'lf_error', type: 'dsp-circle', x: 1040, y: 200, text: 'ERROR',
    properties: baseProperties({ kind: 'ERROR' })
  })

  const lfNodeIds = new Set(lfNodes.map(n => n.id))
  const idMap = { '__START__': 'lf_start', '__END__': 'lf_end', '__ERROR__': 'lf_error', lf_start: 'lf_start', lf_end: 'lf_end', lf_error: 'lf_error' }
  businessNodes.forEach((n, i) => { idMap[n.nodeId] = `lf_${n.nodeId}_${i}` })

  // ── 边：先收集再分组，支持同对节点多并行边「扇形分离」 ──
  const rawEdges = []
  ;(def.edges || []).forEach((e, i) => {
    const source = resolveLfTarget(e.from, idMap)
    if (!lfNodeIds.has(source)) return

    if (e.type === 'conditional') {
      const routeLabel = e.dispatcher || e.condition || 'route'
      Object.entries(e.mapping || {}).forEach(([key, target], j) => {
        const targetNodeId = resolveLfTarget(target, idMap)
        if (!lfNodeIds.has(targetNodeId)) return
        rawEdges.push({
          id: `lf_edge_${i}_${j}`,
          sourceNodeId: source,
          targetNodeId,
          type: 'dsp-bezier',
          text: `${routeLabel}:${key}`,
          properties: baseProperties({
            type: 'conditional',
            dispatcher: e.dispatcher,
            condition: e.condition,
            conditionEngine: e.conditionEngine,
            mapping: e.mapping
          })
        })
      })
      return
    }

    const targetNodeId = resolveLfTarget(e.to, idMap)
    if (!lfNodeIds.has(targetNodeId)) return
    const edgeType = e.type === 'error' ? 'error' : 'normal'
    const isParallel = edgeType === 'normal' && e.parallel === true
    rawEdges.push({
      id: `lf_edge_${i}`,
      sourceNodeId: source,
      targetNodeId,
      type: 'dsp-bezier',
      properties: baseProperties({
        type: edgeType,
        parallel: isParallel,
        aggregation: edgeType === 'normal' ? (e.aggregation || null) : null
      })
    })
  })

  // 同 (source,target) 的多条边沿垂直方向散开，避免完全重叠
  const coordMap = new Map()
  lfNodes.forEach(n => coordMap.set(n.id, { x: n.x, y: n.y }))
  const pairMap = new Map()
  rawEdges.forEach(e => {
    const k = `${e.sourceNodeId}__${e.targetNodeId}`
    if (!pairMap.has(k)) pairMap.set(k, [])
    pairMap.get(k).push(e)
  })
  pairMap.forEach(list => {
    if (list.length < 2) return
    const s = coordMap.get(list[0].sourceNodeId)
    const t = coordMap.get(list[0].targetNodeId)
    if (!s || !t) return
    const dx = t.x - s.x
    const dy = t.y - s.y
    const len = Math.hypot(dx, dy) || 1
    const ux = dx / len
    const uy = dy / len
    const px = -uy
    const py = ux
    const spacing = 28
    const n = list.length
    list.forEach((e, idx) => {
      const off = (idx - (n - 1) / 2) * spacing
      e.startPoint = { x: s.x + ux * 32 + px * off, y: s.y + uy * 32 + py * off }
      e.endPoint = { x: t.x - ux * 32 + px * off, y: t.y - uy * 32 + py * off }
    })
    // 并行块标签：仅在并行组的首条边显示聚合策略，避免重复
    const firstParallel = list.find(e => e.properties?.parallel)
    if (firstParallel) {
      const agg = firstParallel.properties?.aggregation
      firstParallel.text = agg === 'AGG_ALL_OF' ? '并行 · ALL_OF'
        : agg === 'AGG_ANY_OF' ? '并行 · ANY_OF'
        : '并行'
    }
  })
  rawEdges.forEach(e => lfEdges.push(e))

  suppressSync = true
  try {
    renderLfGraph(lfNodes, lfEdges)
    editor.clearSelectedNode()
    refreshSelectionState()
  } catch (err) {
    console.error('[Canvas] renderFromDefinition failed:', err)
  } finally {
    suppressSync = false
    syncToStore()
  }
  // 渲染（加载图）产生的 add 操作不应进入撤销栈
  try { lf.clearHistory?.() } catch (e) { /* noop */ }
  editor.canUndo = false
  editor.canRedo = false
}

function selectEdgeModel(model) {
  if (!model) return
  const data = model.getData ? model.getData() : model
  const { nodes } = lf.getGraphData()
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  editor.setSelectedEdge({
    lfEdgeId: model.id,
    type: data.properties?.type || 'normal',
    from: lfNodeToDslId(nodeById.get(data.sourceNodeId)),
    to: lfNodeToDslId(nodeById.get(data.targetNodeId)),
    parallel: data.properties?.parallel === true,
    aggregation: data.properties?.aggregation || null,
    dispatcher: data.properties?.dispatcher || '',
    condition: data.properties?.condition || '',
    conditionEngine: data.properties?.conditionEngine || 'aviator',
    mapping: { ...(data.properties?.mapping || {}) }
  })
}

/** 按补丁重建条件边分组：删除原分组所有 LF 边，按新 mapping 重建，并重新选中首条边 */
function applyConditionalEdgeEdit(cmd) {
  if (!lf) return
  const data = lf.getGraphData()
  const nodeById = new Map(data.nodes.map(n => [n.id, n]))
  const groupEdges = data.edges.filter(e => {
    if (e.properties?.type !== 'conditional') return false
    const fromDsl = lfNodeToDslId(nodeById.get(e.sourceNodeId))
    return fromDsl === cmd.from &&
      (e.properties.dispatcher || '') === (cmd.oldDispatcher || '') &&
      (e.properties.condition || '') === (cmd.oldCondition || '')
  })
  groupEdges.forEach(e => lf.deleteEdge(e.id))

  const sourceLf = data.nodes.find(n => lfNodeToDslId(n) === cmd.from)
  if (!sourceLf) return
  const routeLabel = cmd.condition || cmd.oldDispatcher || 'route'
  const newIds = []
  Object.entries(cmd.mapping || {}).forEach(([key, target], j) => {
    const targetLf = data.nodes.find(n => lfNodeToDslId(n) === target)
    if (!targetLf) return
    const id = `lf_ce_${Date.now()}_${j}`
    lf.addEdge({
      id,
      sourceNodeId: sourceLf.id,
      targetNodeId: targetLf.id,
      type: 'dsp-bezier',
      text: `${routeLabel}:${key}`,
      properties: baseProperties({
        type: 'conditional',
        dispatcher: cmd.oldDispatcher,
        condition: cmd.condition,
        conditionEngine: cmd.conditionEngine,
        mapping: cmd.mapping
      })
    })
    newIds.push(id)
  })
  syncToStore()
  if (newIds[0]) selectEdgeModel(lf.getEdgeModelById(newIds[0]))
  editor.clearEdgeCommands()
}

/** 将普通边转换为条件边（单条默认映射，便于后续编辑） */
function convertEdgeToConditional(lfEdgeId) {
  if (!lf) return
  const model = lf.getEdgeModelById(lfEdgeId)
  if (!model) return
  const d = model.getData()
  if (d.properties?.type === 'conditional') return
  const { nodes } = lf.getGraphData()
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  const fromDsl = lfNodeToDslId(nodeById.get(d.sourceNodeId))
  const toDsl = lfNodeToDslId(nodeById.get(d.targetNodeId))
  lf.deleteEdge(lfEdgeId)
  const id = `lf_ce_${Date.now()}`
  lf.addEdge({
    id,
    sourceNodeId: d.sourceNodeId,
    targetNodeId: d.targetNodeId,
    type: 'dsp-bezier',
    text: ':default',
    properties: baseProperties({
      type: 'conditional',
      dispatcher: '',
      condition: '',
      conditionEngine: 'aviator',
      mapping: { default: toDsl }
    })
  })
  syncToStore()
  selectEdgeModel(lf.getEdgeModelById(id))
  editor.clearEdgeCommands()
}

// ── 缩放 / 缩略图 ──
function zoomIn() {
  if (lf) lf.zoom(false)
}
function zoomOut() {
  if (lf) lf.zoom(true)
}
function fitView() {
  if (lf) lf.fitView()
}
function resetZoom() {
  if (lf) lf.resetZoom()
}
function toggleMinimap() {
  if (!lf) return
  const mm = lf.extension?.miniMap
  if (!mm) return
  if (editor.minimapVisible) mm.hide?.()
  else mm.show?.()
  editor.minimapVisible = !editor.minimapVisible
}

// ── 自动布局：基于最长路径的分层布局（保留所有节点，含分组容器） ──
function autoLayout() {
  if (!lf) return
  try {
    const data = lf.getGraphData()
    const nodes = data.nodes
    const edges = data.edges
    const idSet = new Set(nodes.map(n => n.id))

    // 构建邻接表（普通边 + 条件边各分支）
    const adj = new Map()
    const indeg = new Map()
    nodes.forEach(n => { adj.set(n.id, []); indeg.set(n.id, 0) })
    edges.forEach(e => {
      const src = e.sourceNodeId
      const dst = e.targetNodeId
      if (!idSet.has(src) || !idSet.has(dst) || src === dst) return
      adj.get(src).push(dst)
      indeg.set(dst, (indeg.get(dst) || 0) + 1)
    })

    // 拓扑排序 + 最长路径分层
    const queue = []
    const layer = new Map()
    nodes.forEach(n => {
      layer.set(n.id, 0)
      if ((indeg.get(n.id) || 0) === 0) queue.push(n.id)
    })
    const q = [...queue]
    while (q.length) {
      const cur = q.shift()
      for (const nxt of adj.get(cur) || []) {
        if (layer.get(nxt) < layer.get(cur) + 1) layer.set(nxt, layer.get(cur) + 1)
        indeg.set(nxt, indeg.get(nxt) - 1)
        if (indeg.get(nxt) === 0) q.push(nxt)
      }
    }

    // 同层内按当前 y 排序，保证稳定
    const byLayer = new Map()
    nodes.forEach(n => {
      const L = layer.get(n.id) || 0
      if (!byLayer.has(L)) byLayer.set(L, [])
      byLayer.get(L).push(n)
    })

    const GAP_X = 220
    const GAP_Y = 110
    const START_X = 120
    const START_Y = 120
    const pos = new Map()
    for (const [L, layerNodes] of byLayer) {
      layerNodes.sort((a, b) => a.y - b.y)
      layerNodes.forEach((n, i) => {
        pos.set(n.id, { x: START_X + L * GAP_X, y: START_Y + i * GAP_Y })
      })
    }

    // 应用业务节点位置：直接修改数据坐标，并同步偏移文字位置
    nodes.forEach(n => {
      if (n.properties?.kind === 'GROUP') return
      const p = pos.get(n.id)
      if (!p) return
      const dx = p.x - n.x
      const dy = p.y - n.y
      n.x = p.x
      n.y = p.y
      // 同步偏移文字标签坐标（text.x/y 是相对于画布的绝对坐标）
      if (n.text) {
        if (n.text.x != null) n.text.x += dx
        if (n.text.y != null) n.text.y += dy
      }
    })

    // 分组容器：重新贴合成员包围盒
    editor.groups.forEach(g => {
      const { minX, minY, maxX, maxY } = computeBounds(g.memberIds)
      if (minX === Infinity) return
      const pad = 30
      const headerH = 26
      const tx = (minX + maxX) / 2
      const ty = (minY + maxY) / 2 - headerH / 2
      const gNode = nodes.find(n => n.id === g.id)
      if (gNode) {
        gNode.x = tx
        gNode.y = ty
        if (!g.collapsed) {
          gNode.width = (maxX - minX) + pad * 2
          gNode.height = (maxY - minY) + pad * 2 + headerH
        }
      }
    })

    // 清除边的缓存路径坐标和文字位置，让 LF 根据新路径重新计算
    edges.forEach(e => {
      delete e.startPoint
      delete e.endPoint
      delete e.pointsList
      if (e.text) {
        delete e.text.x
        delete e.text.y
      }
    })

    // 全量重新渲染画布，LogicFlow 会根据新节点坐标自动重算所有边路径
    lf.render(data)

    // 检测孤立节点（无入边且无出边的业务节点，排除 START/END/分组）
    const connected = new Set()
    edges.forEach(e => {
      if (idSet.has(e.sourceNodeId)) connected.add(e.sourceNodeId)
      if (idSet.has(e.targetNodeId)) connected.add(e.targetNodeId)
    })
    const isolatedNodes = nodes.filter(n => {
      if (n.properties?.kind === 'GROUP') return false
      if (n.id === 'lf_start' || n.id === 'lf_end') return false
      return !connected.has(n.id)
    })
    if (isolatedNodes.length > 0) {
      const names = isolatedNodes.map(n => n.text?.value || n.id).join('、')
      ElMessage.warning(`${t('canvas.isolatedNodes') || '存在孤立节点'}: ${names}`)
    }

    lf.fitView()
    syncToStore()
  } catch (e) {
    console.error('[Canvas] autoLayout failed:', e)
    ElMessage.error(t('canvas.autoLayoutFailed') || '自动布局失败')
  }
}

// ── 画布内节点定位（搜索用） ──
function focusNode(lfId) {
  if (!lf) return
  const m = lf.getNodeModelById(lfId)
  if (!m) return
  lf.focusOn({ id: lfId })
  lf.selectElement(m, true)
  refreshSelectionState()
}

/** 返回可搜索的业务节点列表（供 NodeSearch 使用） */
function getSearchableNodes() {
  if (!lf) return []
  const { nodes } = lf.getGraphData()
  return nodes
    .filter(n => !isStartNode(n) && !isEndNode(n) && n.properties?.kind !== 'GROUP')
    .map(n => ({
      id: n.id,
      label: (n.text && (n.text.value || n.text)) || n.properties?.nodeId || n.id,
      nodeId: n.properties?.nodeId || n.id
    }))
}

// ── 子流程分组 ──
function computeBounds(memberIds) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity
  memberIds.forEach(mid => {
    const m = lf.getNodeModelById(mid)
    if (!m) return
    const w = m.width || 120
    const h = m.height || 60
    minX = Math.min(minX, m.x - w / 2)
    minY = Math.min(minY, m.y - h / 2)
    maxX = Math.max(maxX, m.x + w / 2)
    maxY = Math.max(maxY, m.y + h / 2)
  })
  return { minX, minY, maxX, maxY }
}

function createGroup() {
  if (!lf) return
  const { nodes } = lf.getSelectElements(true)
  const biz = nodes.filter(n => !isReservedNodeData(n) && n.properties?.kind !== 'GROUP')
  if (biz.length < 2) {
    ElMessage.warning(t('toolbar.groupCreateHint'))
    return
  }
  const { minX, minY, maxX, maxY } = computeBounds(biz.map(n => n.id))
  const pad = 30
  const headerH = 26
  const gw = (maxX - minX) + pad * 2
  const gh = (maxY - minY) + pad * 2 + headerH
  const gx = (minX + maxX) / 2
  const gy = (minY + maxY) / 2 - headerH / 2
  const groupId = `lf_group_${Date.now()}`
  const label = `${t('toolbar.group')} ${editor.groups.length + 1}`
  lf.addNode({
    id: groupId,
    type: 'dsp-group',
    x: gx, y: gy,
    text: label,
    properties: baseProperties({ kind: 'GROUP', width: gw, height: gh, memberCount: biz.length, collapsed: false })
  })
  const memberIds = biz.map(n => n.id)
  biz.forEach(n => {
    lf.setProperties(n.id, { ...n.properties, groupId })
  })
  editor.groups.push({ id: groupId, label, memberIds, collapsed: false })
  lf.clearSelectElements()
  syncToStore()
  ElMessage.success(label)
}

function moveGroupMembers(groupId, x, y) {
  const last = groupLastPos[groupId]
  if (!last) {
    groupLastPos[groupId] = { x, y }
    return
  }
  const dx = x - last.x
  const dy = y - last.y
  if (dx === 0 && dy === 0) return
  const g = editor.groups.find(x2 => x2.id === groupId)
  if (g) {
    g.memberIds.forEach(mid => {
      const m = lf.getNodeModelById(mid)
      if (m) m.move(dx, dy)
    })
  }
  last.x = x
  last.y = y
}

function toggleGroupCollapse(lfGroupId) {
  if (!lf) return
  const g = editor.groups.find(x => x.id === lfGroupId)
  if (!g) return
  const model = lf.getNodeModelById(lfGroupId)
  if (!model) return
  const collapsed = !g.collapsed
  g.collapsed = collapsed
  lf.setProperties(lfGroupId, { ...model.properties, collapsed, memberCount: g.memberIds.length })
  g.memberIds.forEach(mid => {
    const m = lf.getNodeModelById(mid)
    if (m) m.visible = !collapsed
  })
  if (collapsed) {
    model.width = 180
    model.height = 56
  } else {
    const { minX, minY, maxX, maxY } = computeBounds(g.memberIds)
    const pad = 30
    const headerH = 26
    model.width = (maxX - minX) + pad * 2
    model.height = (maxY - minY) + pad * 2 + headerH
  }
  syncToStore()
}

function ungroup(lfGroupId) {
  if (!lf) return
  const g = editor.groups.find(x => x.id === lfGroupId)
  if (!g) return
  g.memberIds.forEach(mid => {
    const m = lf.getNodeModelById(mid)
    if (m) {
      m.visible = true
      const p = { ...m.properties }
      delete p.groupId
      lf.setProperties(mid, p)
    }
  })
  lf.deleteNode(lfGroupId)
  editor.groups = editor.groups.filter(x => x.id !== lfGroupId)
  syncToStore()
}

/** 将一组 LF 边转换为 store 边（普通/异常/条件分组合并），与 setFromLfData 逻辑对齐 */
function convertLfEdgesToStore(lfEdges, dslIdOf) {
  const normalEdges = []
  const condByKey = new Map()
  for (const e of lfEdges) {
    const from = dslIdOf(e.sourceNodeId)
    const to = dslIdOf(e.targetNodeId)
    if (e.properties?.type === 'conditional') {
      const key = `${from}|${e.properties.dispatcher || ''}|${e.properties.condition || ''}`
      if (!condByKey.has(key)) {
        const mapping = {}
        for (const [k, v] of Object.entries(e.properties.mapping || {})) mapping[k] = v
        condByKey.set(key, {
          from, to: '', type: 'conditional',
          dispatcher: e.properties.dispatcher,
          condition: e.properties.condition,
          conditionEngine: e.properties.conditionEngine,
          mapping
        })
      } else {
        const ex = condByKey.get(key)
        for (const [k, v] of Object.entries(e.properties.mapping || {})) ex.mapping[k] = v
      }
      continue
    }
    const type = e.properties?.type === 'error' ? 'error' : 'normal'
    const parallel = type === 'normal' && e.properties?.parallel === true
    const aggregation = type === 'normal' ? (e.properties?.aggregation || null) : null
    normalEdges.push({ from, to, type, parallel, aggregation })
  }
  return [...normalEdges, ...condByKey.values()]
}

/**
 * 提取选中节点为子图（P1·DX 快捷操作）：
 * 选中节点移入新 SUBGRAPH 节点（内联 subgraph 字段），内部边保留在子图内，
 * 跨边界的边重连到新子图节点（进入→子图、子图→外出）。
 * 注：内联子图默认不含 START/END（与现有内联子图一致），如需编译运行进入子图内补 START/END 即可。
 */
function extractSelectionToSubgraph() {
  if (!lf) return
  const { nodes: selNodes } = lf.getSelectElements(true)
  const bizNodes = selNodes.filter(n => !isReservedNodeData(n) && n.properties?.kind !== 'GROUP')
  if (bizNodes.length < 1) {
    ElMessage.warning(t('canvas.extractNeedNodes'))
    return
  }

  const selNodeIds = new Set(bizNodes.map(n => n.id))
  const all = lf.getGraphData()
  const nodeById = new Map(all.nodes.map(n => [n.id, n]))
  const dslIdOf = (lfId) => lfNodeToDslId(nodeById.get(lfId))

  // 内部节点 → 子图定义中的节点（保留坐标 / 配置 / 嵌套 subgraph）
  const innerNodes = bizNodes.map(n => ({
    nodeId: n.properties?.nodeId || n.id.replace(/^lf_/, ''),
    category: n.properties?.category || 'NORMAL',
    displayName: n.properties?.displayName || '',
    config: n.properties?.config || {},
    x: n.x,
    y: n.y,
    subgraphRef: n.properties?.subgraphRef || '',
    subgraph: n.properties?.subgraph || null
  }))

  // 内部边（两端都在选中集合）
  const internalLfEdges = all.edges.filter(e => selNodeIds.has(e.sourceNodeId) && selNodeIds.has(e.targetNodeId))
  const internalStoreEdges = convertLfEdgesToStore(internalLfEdges, dslIdOf)

  // 边界边（恰好一端在选中集合）：提取后重连到新子图节点
  const boundary = all.edges.filter(e => {
    const a = selNodeIds.has(e.sourceNodeId)
    const b = selNodeIds.has(e.targetNodeId)
    return (a || b) && !(a && b)
  })

  const newId = `subgraph_${Date.now()}`
  const innerDef = {
    graphId: newId,
    displayName: newId,
    version: '1.0.0',
    nodes: innerNodes,
    edges: internalStoreEdges
  }

  // 质心定位
  let cx = 0
  let cy = 0
  bizNodes.forEach(n => { cx += n.x; cy += n.y })
  cx /= bizNodes.length
  cy /= bizNodes.length

  // 先删除选中业务节点（同时移除其相连边）
  bizNodes.forEach(n => lf.deleteNode(n.id))

  // 新增 SUBGRAPH 节点
  const subLfId = `lf_${newId}_0`
  lf.addNode({
    id: subLfId,
    type: resolveNodeType('SUBGRAPH'),
    x: cx,
    y: cy,
    text: newId,
    properties: baseProperties({
      nodeId: newId,
      displayName: newId,
      category: 'SUBGRAPH',
      config: {},
      inputKeys: [],
      outputKeys: [],
      subgraphRef: '',
      subgraph: innerDef
    })
  })

  // 重连边界边：把选中端替换为子图节点
  boundary.forEach(e => {
    const fromIn = selNodeIds.has(e.sourceNodeId)
    const toIn = selNodeIds.has(e.targetNodeId)
    const source = fromIn ? subLfId : e.sourceNodeId
    const target = toIn ? subLfId : e.targetNodeId
    lf.addEdge({
      id: `lf_ex_${Date.now()}_${Math.floor(Math.random() * 1e6)}`,
      sourceNodeId: source,
      targetNodeId: target,
      type: 'dsp-bezier',
      text: e.text,
      properties: baseProperties({ ...(e.properties || {}) })
    })
  })

  lf.clearSelectElements()
  syncToStore()
  editor.requestRerender()
  ElMessage.success(t('canvas.extractSuccess', { n: bizNodes.length }))
}

defineExpose({
  onNodeDrag,
  renderFromDefinition,
  ensureStartEndNodes,
  deleteSelectedElements,
  applyConditionalEdgeEdit,
  convertEdgeToConditional,
  undo,
  redo,
  zoomIn,
  zoomOut,
  fitView,
  resetZoom,
  toggleMinimap,
  autoLayout,
  focusNode,
  getSearchableNodes,
  createGroup,
  toggleGroupCollapse,
  ungroup,
  extractSelectionToSubgraph,
  toggleSelectionSelect,
  selectionSelectActive,
  whenReady: () => lfReady
})
</script>

<template>
  <div class="designer-canvas-wrap">
    <div ref="containerRef" class="designer-canvas"></div>
    <el-tooltip v-if="canDeleteSelection" :content="t('canvas.deleteSelectionHint')" placement="right">
      <el-button
        class="canvas-delete-btn"
        type="danger"
        size="small"
        circle
        :icon="Delete"
        @click="deleteSelectedElements"
      />
    </el-tooltip>
    <transition name="el-fade-in">
      <div v-if="hasStreamingNode" class="canvas-legend">
        <span class="legend-badge">
          <svg viewBox="0 0 22 22" width="18" height="18" aria-hidden="true">
            <circle cx="11" cy="11" r="9" fill="#ffffff" stroke="#06b6d4" stroke-width="2" />
            <path d="M5 11 q2 -4 4 0 q2 4 4 0 q2 -4 4 0" fill="none" stroke="#06b6d4" stroke-width="1.8" stroke-linecap="round" />
          </svg>
        </span>
        <span class="legend-label">{{ t('canvas.legendStreaming') }}</span>
      </div>
    </transition>
    <transition name="el-fade-in">
      <div v-if="editor.subgraphLoading" class="canvas-loading-overlay">
        <el-icon class="canvas-loading-icon"><Loading /></el-icon>
        <span class="canvas-loading-text">{{ t('canvas.loadingSubgraph') }}</span>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.designer-canvas-wrap {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
}
.designer-canvas {
  width: 100%;
  height: 100%;
  min-height: 0;
}
.canvas-delete-btn {
  position: absolute;
  top: 196px;
  left: 10px;
  z-index: 10;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12);
}

.designer-canvas-wrap :deep(.lf-node-dsp-rect .lf-node-text-ellipsis-content) {
  padding: 0 10px 0 32px !important;
}

.designer-canvas-wrap :deep(.lf-node-dsp-diamond .lf-node-text-ellipsis-content) {
  padding-top: 6px !important;
}

.designer-canvas-wrap :deep(.lf-node-dsp-circle .lf-node-text-ellipsis-content) {
  font-size: 11px;
  font-weight: 600;
}

.designer-canvas-wrap :deep(.lf-mini-map) {
  border-radius: 6px;
  border: none !important;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
}
.designer-canvas-wrap :deep(.lf-mini-map-header) {
  border: none !important;
  font-size: 12px;
  height: 24px !important;
  line-height: 24px !important;
  background-color: var(--agd-color-bg-active, #ecf5ff) !important;
}

/* ── G7 流式 / 异步节点脉冲徽标（SVG 由 DspNode.js 动态渲染，需用 :deep 命中） ── */
.designer-canvas-wrap :deep(.dsp-streaming) {
  transform-box: fill-box;
  transform-origin: center;
  animation: dsp-stream-pulse 1.8s ease-in-out infinite;
  filter: drop-shadow(0 0 3px rgba(6, 182, 212, 0.7));
}
.designer-canvas-wrap :deep(.dsp-streaming-wave) {
  animation: dsp-stream-wave 1.8s ease-in-out infinite;
}
@keyframes dsp-stream-pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.55; transform: scale(1.18); }
}
@keyframes dsp-stream-wave {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

/* ── 画布图例：流式 / 异步节点说明（仅当存在此类节点时显示） ── */
.canvas-legend {
  position: absolute;
  left: 10px;
  bottom: 10px;
  z-index: 9;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 12px 4px 8px;
  font-size: 12px;
  color: var(--agd-color-text-secondary, #909399);
  background: var(--agd-color-bg-elevated, rgba(255, 255, 255, 0.82));
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 999px;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
  backdrop-filter: blur(6px);
  user-select: none;
  pointer-events: none;
}
:deep(.dark) .canvas-legend,
.designer-canvas-wrap .canvas-legend {
  background: var(--agd-color-bg-elevated-dark, rgba(40, 40, 40, 0.82));
  border-color: var(--agd-color-border-dark, #4c4d4f);
  color: var(--agd-color-text-secondary-dark, #a3a6ad);
}
.dark .canvas-legend {
  background: var(--agd-color-bg-elevated-dark, rgba(40, 40, 40, 0.82));
  border-color: var(--agd-color-border-dark, #4c4d4f);
  color: var(--agd-color-text-secondary-dark, #a3a6ad);
}
.canvas-legend .legend-badge {
  display: inline-flex;
  animation: dsp-stream-pulse 1.8s ease-in-out infinite;
}
.canvas-legend .legend-label {
  white-space: nowrap;
}

/* ── 引用型子图下钻时的懒加载遮罩 ── */
.canvas-loading-overlay {
  position: absolute;
  inset: 0;
  z-index: 20;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: var(--agd-color-overlay, rgba(255, 255, 255, 0.55));
  backdrop-filter: blur(2px);
  color: var(--agd-color-text-secondary, #909399);
  font-size: 13px;
  pointer-events: all;
}
.dark .canvas-loading-overlay {
  background: rgba(20, 20, 20, 0.55);
  color: var(--agd-color-text-secondary-dark, #cfd3dc);
}
.canvas-loading-icon {
  font-size: 28px;
  animation: agd-spin 1s linear infinite;
}
@keyframes agd-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
