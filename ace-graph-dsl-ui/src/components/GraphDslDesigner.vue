<script setup>
import { ref, watch, onMounted, nextTick } from 'vue'
import Toolbar from './Designer/Toolbar.vue'
import Canvas from './Designer/Canvas.vue'
import EdgeParamValidationPanel from './Designer/EdgeParamValidationPanel.vue'
import DryRunDrawer from './Designer/DryRunDrawer.vue'
import TopologyValidationPanel from './Designer/TopologyValidationPanel.vue'
import NodeSearch from './Designer/NodeSearch.vue'
import GroupPanel from './Designer/GroupPanel.vue'
import { useGraphEditorStore } from '../stores/graphEditor'
import { useNodeRegistryStore } from '../stores/nodeRegistry'
import { usePermissionStore } from '../stores/permissions'
import { configureGraphDslI18n, useI18n } from '../i18n'
import { ElMessage } from 'element-plus'

const props = defineProps({
  graphId: { type: String, required: true },
  apiBaseUrl: { type: String, default: '/' },
  title: { type: String, default: '' },
  locale: { type: String, default: 'zh-CN' },
  readOnly: { type: Boolean, default: false }
})

const emit = defineEmits(['saved', 'published'])

const editor = useGraphEditorStore()
const nodeStore = useNodeRegistryStore()
const perm = usePermissionStore()
const canvasRef = ref()
const showDryRun = ref(false)
const importFileRef = ref()
const { t } = useI18n()

watch(() => props.locale, (loc) => {
  if (loc) configureGraphDslI18n({ locale: loc })
}, { immediate: true })

async function paintCanvas(def) {
  await nextTick()
  const canvas = canvasRef.value
  if (!canvas || !def) return
  if (typeof canvas.whenReady === 'function') {
    await canvas.whenReady()
  }
  await canvas.renderFromDefinition(def)
}

async function loadGraph() {
  editor.selectGraph(props.graphId)
  let def
  try {
    def = await editor.loadLatest()
  } catch (e) {
    console.warn('[GraphDslDesigner] loadLatest failed:', e)
    editor.initNewGraph(props.graphId, { displayName: props.title || props.graphId })
    await nextTick()
    canvasRef.value?.ensureStartEndNodes?.()
    await editor.loadEnabledVersion()
    return
  }

  if (def) {
    try {
      await paintCanvas(def)
    } catch (e) {
      console.error('[GraphDslDesigner] paintCanvas failed:', e)
    }
  } else {
    editor.initNewGraph(props.graphId, { displayName: props.title || props.graphId })
    await nextTick()
    canvasRef.value?.ensureStartEndNodes?.()
  }
  await editor.loadEnabledVersion()
  editor.refreshEdgeParamValidation(nodeStore.nodes)
}

watch(() => props.graphId, loadGraph)

onMounted(async () => {
  if (!perm.loaded) await perm.load()
  if (!nodeStore.nodes.length) {
    await nodeStore.fetchNodes()
    await nodeStore.fetchDispatchers()
  }
  await loadGraph()
})

async function onSave() {
  await editor.save()
  emit('saved')
}

async function onPublish() {
  await editor.publishCurrent()
  emit('published')
  emit('saved')
}

function onNodeDrag(descriptor) {
  canvasRef.value?.onNodeDrag(descriptor)
}

function onUndo() {
  canvasRef.value?.undo()
}

function onRedo() {
  canvasRef.value?.redo()
}

function onDryRun() {
  showDryRun.value = true
}

function onTopologyCheck() {
  const res = editor.validateTopologyNow()
  if (res.ok) {
    ElMessage.success(t('topology.ok'))
  } else {
    const errCount = res.issues.filter(i => i.level === 'error').length
    ElMessage.warning(t('toolbar.validateFailed', { count: errCount }))
  }
}

function onZoomIn() { canvasRef.value?.zoomIn() }
function onZoomOut() { canvasRef.value?.zoomOut() }
function onFit() { canvasRef.value?.fitView() }
function onResetZoom() { canvasRef.value?.resetZoom() }
function onToggleMinimap() { canvasRef.value?.toggleMinimap() }
function onAutoLayout() { canvasRef.value?.autoLayout() }
function onCreateGroup() { canvasRef.value?.createGroup() }
function onToggleBoxSelect() { canvasRef.value?.toggleSelectionSelect() }

/** 导出当前 DSL 为 JSON 文件 */
function onExportDsl() {
  const def = editor.buildDefinition()
  const json = JSON.stringify(def, null, 2)
  const blob = new Blob([json], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${def.graphId || 'graph'}_${def.version || '1.0.0'}.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
  ElMessage.success(t('toolbar.exportSuccess'))
}

/** 触发文件选择框以导入 DSL */
function onImportDsl() {
  importFileRef.value?.click()
}

function onImportFileChange(e) {
  const file = e.target.files && e.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => {
    try {
      const def = JSON.parse(String(reader.result))
      if (!def || typeof def !== 'object' || (!Array.isArray(def.nodes) && !Array.isArray(def.edges))) {
        throw new Error(t('toolbar.importInvalid'))
      }
      editor.applyDefinition(def)
      paintCanvas(def).then(() => {
        editor.refreshEdgeParamValidation(nodeStore.nodes)
        ElMessage.success(t('toolbar.importSuccess', { graphId: def.graphId, version: def.version }))
      })
    } catch (err) {
      ElMessage.error(t('toolbar.importFailed', { msg: err.message || err }))
    } finally {
      e.target.value = ''
    }
  }
  reader.onerror = () => {
    ElMessage.error(t('toolbar.importFailed', { msg: 'read error' }))
    e.target.value = ''
  }
  reader.readAsText(file)
}

defineExpose({ onNodeDrag, canvasRef })
</script>

<template>
  <div class="ace-graph-dsl-designer">
    <div class="canvas-shell">
      <Canvas ref="canvasRef" class="canvas-panel" />
      <Toolbar
        floating-meta
        floating-actions
        :title="title || graphId"
        :canvas-ref="canvasRef"
        :read-only="readOnly"
        @save="onSave"
        @validate="editor.validate()"
        @preview="editor.loadPlantUml()"
        @publish="onPublish()"
        @undo="onUndo"
        @redo="onRedo"
        @dryRun="onDryRun"
        @importDsl="onImportDsl"
        @exportDsl="onExportDsl"
        @topology="onTopologyCheck"
        @zoomIn="onZoomIn"
        @zoomOut="onZoomOut"
        @fit="onFit"
        @resetZoom="onResetZoom"
        @autoLayout="onAutoLayout"
        @toggleMinimap="onToggleMinimap"
        @createGroup="onCreateGroup"
        @toggleBoxSelect="onToggleBoxSelect"
      />
      <NodeSearch :canvas-ref="canvasRef" />
      <GroupPanel :canvas-ref="canvasRef" />
      <EdgeParamValidationPanel />
      <TopologyValidationPanel />
      <DryRunDrawer v-model:visible="showDryRun" :graph-id="graphId" />
      <input
        ref="importFileRef"
        type="file"
        accept=".json,application/json"
        style="display: none"
        @change="onImportFileChange"
      />
    </div>
  </div>
</template>

<style scoped>
.ace-graph-dsl-designer {
  display: flex;
  flex: 1;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}
.canvas-shell {
  position: relative;
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.canvas-panel {
  width: 100%;
  height: 100%;
}
.canvas-shell :deep(.lf-control) {
  z-index: 4;
}
</style>
