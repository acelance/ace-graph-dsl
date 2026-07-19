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
    <div v-if="editor.isDrilledIn" class="breadcrumb-bar">
      <span
        v-for="(c, idx) in editor.breadcrumb"
        :key="c.level"
        class="breadcrumb-item"
        :class="{ active: idx === editor.breadcrumb.length - 1 }"
        @click="editor.goToBreadcrumb(c.level)"
      >
        <span v-if="idx > 0" class="breadcrumb-sep">/</span>
        <span class="breadcrumb-label">{{ c.label || c.graphId }}</span>
        <el-tag v-if="c.kind === 'reference'" size="small" type="info" effect="plain" class="breadcrumb-tag">ref</el-tag>
        <el-tag v-else-if="c.kind === 'inline'" size="small" type="info" effect="plain" class="breadcrumb-tag">inline</el-tag>
      </span>
    </div>
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
.breadcrumb-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 2px;
  padding: 6px 16px;
  background: var(--agd-color-bg-toolbar, #fafafa);
  border-bottom: 1px solid var(--agd-color-border, #e4e7ed);
  font-size: 13px;
}
.breadcrumb-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  color: var(--agd-color-text-secondary, #606266);
  padding: 2px 4px;
  border-radius: 4px;
  transition: background 0.15s;
}
.breadcrumb-item:hover { background: rgba(64, 158, 255, 0.08); color: var(--agd-color-primary, #409eff); }
.breadcrumb-item.active { color: var(--agd-color-primary, #409eff); font-weight: 600; cursor: default; }
.breadcrumb-sep { color: var(--agd-color-text-secondary, #c0c4cc); margin: 0 2px; }
.breadcrumb-tag { margin-left: 2px; transform: scale(0.85); }
</style>
