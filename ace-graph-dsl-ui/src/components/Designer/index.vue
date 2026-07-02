<script setup>
import { ref, onMounted } from 'vue'
import NodePanel from './NodePanel.vue'
import Canvas from './Canvas.vue'
import Toolbar from './Toolbar.vue'
import { useGraphEditorStore } from '../../stores/graphEditor'

const editor = useGraphEditorStore()
const canvasRef = ref()

onMounted(async () => {
  try {
    const def = await editor.loadLatest()
    if (def) {
      canvasRef.value?.renderFromDefinition(def)
    }
  } catch (e) {
    console.log('未找到已有图定义，将使用空白画布')
  }
})
</script>

<template>
  <div class="designer-layout">
    <Toolbar @save="editor.save()" @validate="editor.validate()" @preview="editor.loadPlantUml()"
             @publish="editor.publishCurrent()" :canvas-ref="canvasRef" />
    <div class="designer-body">
      <Canvas ref="canvasRef" class="center-panel" />
      <NodePanel class="right-panel" @node-drag="canvasRef?.onNodeDrag($event)" />
    </div>
  </div>
</template>

<style scoped>
.designer-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
}
.designer-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}
.center-panel { flex: 1; min-width: 0; }
.right-panel {
  width: var(--agd-panel-width-left, 240px);
  border-left: 1px solid var(--agd-color-border, #e4e7ed);
  overflow-y: auto;
}
</style>
