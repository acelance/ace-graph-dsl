<script setup>
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'
import { FolderOpened, ArrowDown, ArrowRight, Close } from '@element-plus/icons-vue'

const props = defineProps({
  canvasRef: { type: Object, default: null }
})

const editor = useGraphEditorStore()
const { t } = useI18n()

function toggle(g) {
  props.canvasRef?.toggleGroupCollapse?.(g.id)
}
function ungroup(g) {
  props.canvasRef?.ungroup?.(g.id)
}
</script>

<template>
  <div v-if="editor.groups.length" class="group-panel">
    <div class="group-panel-title">
      <el-icon><FolderOpened /></el-icon>
      <span>{{ t('group.title') }}</span>
    </div>
    <ul class="group-list">
      <li v-for="g in editor.groups" :key="g.id" class="group-item">
        <el-button text size="small" class="group-toggle" @click="toggle(g)">
          <el-icon>
            <ArrowDown v-if="!g.collapsed" />
            <ArrowRight v-else />
          </el-icon>
          <span class="group-label">{{ g.label }}</span>
          <span class="group-count">{{ t('toolbar.groupCount', { n: g.memberIds.length }) }}</span>
        </el-button>
        <el-button text size="small" class="group-remove" :icon="Close" :title="t('toolbar.groupUngroup')" @click="ungroup(g)" />
      </li>
    </ul>
  </div>
</template>

<style scoped>
.group-panel {
  position: absolute;
  left: 16px;
  bottom: 16px;
  z-index: 11;
  width: 240px;
  max-height: 240px;
  overflow-y: auto;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  padding: 8px 10px;
}
.group-panel-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #8b5cf6;
  margin-bottom: 6px;
}
.group-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.group-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-radius: 4px;
}
.group-item:hover {
  background: var(--agd-color-bg-active, #f5f7fa);
}
.group-toggle {
  flex: 1;
  justify-content: flex-start;
  color: #303133;
}
.group-label {
  font-weight: 500;
  margin-left: 2px;
}
.group-count {
  color: #909399;
  font-size: 11px;
  margin-left: 6px;
}
.group-remove {
  color: #909399;
}
</style>
