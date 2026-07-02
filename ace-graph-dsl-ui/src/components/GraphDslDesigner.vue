<script setup>
import { ref, watch, onMounted } from 'vue'
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
  locale: { type: String, default: 'zh-CN' }
})

const emit = defineEmits(['saved', 'published'])

const editor = useGraphEditorStore()
const nodeStore = useNodeRegistryStore()
const perm = usePermissionStore()
const canvasRef = ref()

watch(() => props.locale, (loc) => {
  if (loc) configureGraphDslI18n({ locale: loc })
}, { immediate: true })

async function loadGraph() {
  editor.selectGraph(props.graphId)
  try {
    const def = await editor.loadLatest()
    if (def) {
      canvasRef.value?.renderFromDefinition(def)
    } else {
      editor.initNewGraph(props.graphId, { displayName: props.title || props.graphId })
      canvasRef.value?.ensureStartEndNodes?.()
    }
  } catch {
    editor.initNewGraph(props.graphId, { displayName: props.title || props.graphId })
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
