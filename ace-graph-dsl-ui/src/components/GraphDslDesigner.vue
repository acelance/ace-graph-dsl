<script setup>
import { ref, watch, onMounted, nextTick } from 'vue'
import Toolbar from './Designer/Toolbar.vue'
import Canvas from './Designer/Canvas.vue'
import { useGraphEditorStore } from '../stores/graphEditor'
import { useNodeRegistryStore } from '../stores/nodeRegistry'
import { usePermissionStore } from '../stores/permissions'
import { configureGraphDslI18n } from '../i18n'

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
