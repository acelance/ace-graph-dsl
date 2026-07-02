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

const nodeStore = useNodeRegistryStore()
const editor = useGraphEditorStore()
const { t } = useI18n()

const containerRef = ref(null)
const canDeleteSelection = ref(false)
let lf = null
let suppressSync = false

const CATEGORY_COLORS = {
  NORMAL: { stroke: '#409eff', fill: '#ecf5ff' },
  ROUTER: { stroke: '#e6a23c', fill: '#fdf6ec' },
  MERGE: { stroke: '#67c23a', fill: '#f0f9eb' },
  HITL: { stroke: '#f56c6c', fill: '#fef0f0' }
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

function initLf() {
  lf = new LogicFlow({
    container: containerRef.value,
    grid: { size: 10, visible: true, type: 'dot' },
    plugins: [MiniMap, Control, Snapshot],
    keyboard: { enabled: true },
    edgeTextDraggable: false,
    adjustEdge: true,
    guards: {
      beforeDelete: (data) => !isReservedNodeData(data)
    }
  })
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
      id: 'lf_start', type: 'circle', x: 120, y: 200, text: 'START',
      properties: { kind: 'START' }
    })
    const m = lf.getNodeModelById('lf_start')
    if (m) { m.r = 28 }
  }
  if (!data.nodes.some(isEndNode)) {
    lf.addNode({
      id: 'lf_end', type: 'circle', x: 680, y: 200, text: 'END',
      properties: { kind: 'END' }
    })
    const m = lf.getNodeModelById('lf_end')
    if (m) { m.r = 28 }
  }
}

function onNodeDrag(descriptor) {
  const category = descriptor.category || 'NORMAL'
  const colors = CATEGORY_COLORS[category] || CATEGORY_COLORS.NORMAL
  const id = `${descriptor.nodeId}_${Date.now()}`
  lf.addNode({
    id, type: 'rect', x: 300, y: 200, text: descriptor.displayName,
    properties: {
      nodeId: descriptor.nodeId, displayName: descriptor.displayName, category,
      inputKeys: descriptor.inputKeys, outputKeys: descriptor.outputKeys, config: {}
    }
  })
  const model = lf.getNodeModel(id)
  if (model) {
    model.width = 160; model.height = 56; model.radius = 8
    model.stroke = colors.stroke; model.fill = colors.fill
  }
}

const RESERVED_NODE_IDS = new Set(['__START__', '__END__', 'lf_start', 'lf_end'])

function renderFromDefinition(def) {
  if (!def) return
  const businessNodes = (def.nodes || []).filter(n => !RESERVED_NODE_IDS.has(n.nodeId))
  const lfNodes = []
  const lfEdges = []

  lfNodes.push({ id: 'lf_start', type: 'circle', x: 50, y: 200, text: 'START', properties: { kind: 'START' } })

  businessNodes.forEach((n, i) => {
    const desc = nodeStore.nodes.find(d => d.nodeId === n.nodeId)
    const category = desc?.category || 'NORMAL'
    const id = `lf_${n.nodeId}`
    lfNodes.push({
      id, type: 'rect', x: 200 + 200 * (i + 1), y: 150 + (i % 3) * 100,
      text: desc?.displayName || n.nodeId,
      properties: { nodeId: n.nodeId, displayName: desc?.displayName, category, config: n.config || {},
                    inputKeys: desc?.inputKeys || [], outputKeys: desc?.outputKeys || [] }
    })
  })

  lfNodes.push({ id: 'lf_end', type: 'circle', x: 900, y: 200, text: 'END', properties: { kind: 'END' } })

  const idMap = { '__START__': 'lf_start', '__END__': 'lf_end', lf_start: 'lf_start', lf_end: 'lf_end' }
  businessNodes.forEach(n => { idMap[n.nodeId] = `lf_${n.nodeId}` })

  def.edges.forEach((e, i) => {
    const source = idMap[e.from] || e.from
    if (e.type === 'conditional') {
      Object.entries(e.mapping || {}).forEach(([key, target], j) => {
        lfEdges.push({
          id: `lf_edge_${i}_${j}`, sourceNodeId: source, targetNodeId: idMap[target] || target,
          type: 'polyline', text: `${e.dispatcher}:${key}`,
          properties: { type: 'conditional', dispatcher: e.dispatcher, mapping: e.mapping }
        })
      })
    } else {
      lfEdges.push({
        id: `lf_edge_${i}`, sourceNodeId: source, targetNodeId: idMap[e.to] || e.to,
        type: 'polyline', properties: { type: 'normal' }
      })
    }
  })

  suppressSync = true
  try {
    lf.render({ nodes: lfNodes, edges: lfEdges })
    editor.clearSelectedNode()
    refreshSelectionState()
  } finally {
    suppressSync = false
  }
}

defineExpose({ onNodeDrag, renderFromDefinition, ensureStartEndNodes, deleteSelectedElements })
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
</style>
