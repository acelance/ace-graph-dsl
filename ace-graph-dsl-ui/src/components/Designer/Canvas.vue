<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import LogicFlow from '@logicflow/core'
import { MiniMap, Control, Snapshot } from '@logicflow/extension'
import '@logicflow/core/dist/index.css'
import '@logicflow/extension/lib/style/index.css'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'
import { DspRectNode, DspDiamondNode, DspCircleNode, resolveNodeType } from './DspNode.js'
import { DspBezierEdge } from './DspEdge.js'

const nodeStore = useNodeRegistryStore()
const editor = useGraphEditorStore()
const { t } = useI18n()

const containerRef = ref(null)
const canDeleteSelection = ref(false)
let lf = null
let suppressSync = false
let lfReadyResolve
const lfReady = new Promise(resolve => { lfReadyResolve = resolve })

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
  return id === 'lf_start' || id === 'lf_end'
}

function isReservedNodeData(data) {
  return isReservedLfNodeId(data?.id)
    || data?.properties?.kind === 'START'
    || data?.properties?.kind === 'END'
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

  lf.clearSelectElements()
  edges.forEach(edge => edge.id && lf.deleteEdge(edge.id))
  nodes.forEach(node => node.id && lf.deleteNode(node.id))
  editor.clearSelectedNode()
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
  lf.register(DspBezierEdge)
  lf.setDefaultEdgeType('dsp-bezier')
  applyLfTheme()
}

function initLf() {
  lf = new LogicFlow({
    container: containerRef.value,
    grid: { size: 10, visible: true, type: 'dot' },
    plugins: [MiniMap, Control, Snapshot],
    keyboard: { enabled: true },
    edgeTextDraggable: false,
    adjustEdge: true,
    hoverOutline: true,
    edgeSelectedOutline: true,
    guards: {
      beforeDelete: (data) => !isReservedNodeData(data)
    }
  })
  registerCustomElements()
  lf.render({ nodes: [], edges: [] })
  ensureStartEndNodes()
  registerDeleteShortcut()

  lf.on('node:add,node:delete,node:dnd-add,edge:add,edge:delete,node:properties-update', syncToStore)
  lf.on('node:click', ({ data }) => {
    refreshSelectionState()
    if (data.properties?.kind === 'START' || data.properties?.kind === 'END') {
      editor.clearSelectedNode()
      return
    }
    editor.setSelectedNode(data.id, data.properties?.nodeId || data.id, data.properties?.config || {})
  })
  lf.on('edge:click', () => {
    editor.clearSelectedNode()
    refreshSelectionState()
  })
  lf.on('blank:click', () => {
    editor.clearSelectedNode()
    refreshSelectionState()
  })
  lf.on('element:click', () => refreshSelectionState())
  lfReadyResolve?.()
}

function resolveLfTarget(token, idMap) {
  if (!token) return ''
  if (token === '__START__' || token === 'lf_start') return 'lf_start'
  if (token === '__END__' || token === 'lf_end') return 'lf_end'
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
  const category = descriptor.category || 'NORMAL'
  const id = `${descriptor.nodeId}_${Date.now()}`
  lf.addNode({
    id,
    type: resolveNodeType(category),
    x: 300,
    y: 200,
    text: descriptor.displayName,
    properties: baseProperties({
      nodeId: descriptor.nodeId,
      displayName: descriptor.displayName,
      category,
      inputKeys: descriptor.inputKeys,
      outputKeys: descriptor.outputKeys,
      config: {}
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
    const category = desc?.category || 'NORMAL'
    const id = `lf_${n.nodeId}`
    lfNodes.push({
      id,
      type: resolveNodeType(category),
      x: 200 + 200 * (i + 1),
      y: 150 + (i % 3) * 100,
      text: desc?.displayName || n.nodeId,
      properties: baseProperties({
        nodeId: n.nodeId,
        displayName: desc?.displayName,
        category,
        config: n.config || {},
        inputKeys: desc?.inputKeys || [],
        outputKeys: desc?.outputKeys || []
      })
    })
  })

  lfNodes.push({
    id: 'lf_end', type: 'dsp-circle', x: 900, y: 200, text: 'END',
    properties: baseProperties({ kind: 'END' })
  })

  const lfNodeIds = new Set(lfNodes.map(n => n.id))
  const idMap = { '__START__': 'lf_start', '__END__': 'lf_end', lf_start: 'lf_start', lf_end: 'lf_end' }
  businessNodes.forEach(n => { idMap[n.nodeId] = `lf_${n.nodeId}` })

  ;(def.edges || []).forEach((e, i) => {
    const source = resolveLfTarget(e.from, idMap)
    if (!lfNodeIds.has(source)) return

    if (e.type === 'conditional') {
      const dispatcher = e.dispatcher || e.condition || 'route'
      Object.entries(e.mapping || {}).forEach(([key, target], j) => {
        const targetNodeId = resolveLfTarget(target, idMap)
        if (!lfNodeIds.has(targetNodeId)) return
        lfEdges.push({
          id: `lf_edge_${i}_${j}`,
          sourceNodeId: source,
          targetNodeId,
          type: 'dsp-bezier',
          text: `${dispatcher}:${key}`,
          properties: baseProperties({
            type: 'conditional',
            dispatcher: e.dispatcher || e.condition,
            mapping: e.mapping
          })
        })
      })
      return
    }

    const targetNodeId = resolveLfTarget(e.to, idMap)
    if (!lfNodeIds.has(targetNodeId)) return
    lfEdges.push({
      id: `lf_edge_${i}`,
      sourceNodeId: source,
      targetNodeId,
      type: 'dsp-bezier',
      properties: baseProperties({ type: 'normal' })
    })
  })

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
}

defineExpose({
  onNodeDrag,
  renderFromDefinition,
  ensureStartEndNodes,
  deleteSelectedElements,
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
</style>
