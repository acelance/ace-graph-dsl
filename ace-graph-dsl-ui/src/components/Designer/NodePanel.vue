<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'
import { deleteScriptNode, listReferringGraphs } from '../../api/graph'
import ScriptNodeEditor from './ScriptNodeEditor.vue'

const nodeStore = useNodeRegistryStore()
const perm = usePermissionStore()
const { t } = useI18n()

const keyword = ref('')
const activeTab = ref('ALL')
const showScriptEditor = ref(false)
const editingNode = ref(null)
const props = defineProps({ embedded: { type: Boolean, default: false } })
const emit = defineEmits(['node-drag'])

const filteredNodes = computed(() => {
  let list = nodeStore.nodes.filter(n =>
    (n.displayName || '').toLowerCase().includes(keyword.value.toLowerCase()) ||
    n.nodeId.toLowerCase().includes(keyword.value.toLowerCase())
  )
  if (activeTab.value !== 'ALL') {
    list = list.filter(n => (n.origin || 'BUILTIN') === activeTab.value)
  }
  return list
})

function onDragStart(e, n) {
  e.dataTransfer.effectAllowed = 'copy'
  e.dataTransfer.setData('application/node', JSON.stringify(n))
  emit('node-drag', n)
}

function categoryTagType(c) {
  return { NORMAL: 'info', ROUTER: 'warning', MERGE: 'success', HITL: '' }[c] || 'info'
}

function isHitlCategory(c) {
  return c === 'HITL'
}

async function ensureRegistryLoaded() {
  if (!perm.loaded) await perm.load()
  const tasks = []
  if (!nodeStore.nodes.length) tasks.push(nodeStore.fetchNodes())
  if (!nodeStore.dispatchers.length) tasks.push(nodeStore.fetchDispatchers())
  if (tasks.length) await Promise.all(tasks)
}

onMounted(() => {
  ensureRegistryLoaded()
})

async function onScriptCreated() {
  await nodeStore.fetchNodes()
}

function onEdit(node) {
  editingNode.value = node
  showScriptEditor.value = true
}

function onNew() {
  editingNode.value = null
  showScriptEditor.value = true
}

async function onDelete(node) {
  try {
    const refs = await listReferringGraphs(node.nodeId)
    let message = t('nodePanel.deleteConfirm', { name: node.displayName || node.nodeId })
    if (refs.length > 0) {
      message = t('nodePanel.referenceWarning') + '\n' + refs.join(', ') + '\n\n' + message
    }
    await ElMessageBox.confirm(message, t('nodePanel.delete'), { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') })
    await deleteScriptNode(node.nodeId)
    ElMessage.success(t('common.confirm'))
    await nodeStore.fetchNodes()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.error || e.message)
  }
}
</script>

<template>
  <div class="node-panel" :class="{ 'node-panel--embedded': props.embedded }" v-loading="nodeStore.loading">
    <div class="panel-header">{{ t('nodePanel.title') }}</div>
    <el-alert :title="t('nodePanel.canvasHint')" type="info" :closable="false" show-icon class="canvas-hint" />
    <el-input v-model="keyword" :placeholder="t('nodePanel.search')" clearable size="small" style="margin-bottom: 8px;" />
    <el-tabs v-model="activeTab" size="small" style="margin-bottom: 8px;">
      <el-tab-pane :label="t('nodePanel.all')" name="ALL" />
      <el-tab-pane :label="t('nodePanel.builtin')" name="BUILTIN" />
      <el-tab-pane :label="t('nodePanel.script')" name="SCRIPT" />
    </el-tabs>
    <el-button v-if="perm.can(MENU.SCRIPT_NODE_CREATE)" type="primary" size="small" style="width: 100%; margin-bottom: 8px;" @click="onNew">
      {{ t('nodePanel.createScript') }}
    </el-button>
    <div
      v-for="n in filteredNodes"
      :key="n.nodeId"
      class="node-item"
      draggable="true"
      @dragstart="onDragStart($event, n)"
      @click="emit('node-drag', n)"
    >
      <div class="node-row node-row--top">
        <div class="node-info">
          <span class="node-name">{{ n.displayName }}</span>
          <el-tag v-if="n.origin === 'SCRIPT'" size="small" type="success" style="margin-left: 4px;">SCRIPT</el-tag>
          <el-tag
            size="small"
            :type="categoryTagType(n.category)"
            :class="{ 'hitl-tag': isHitlCategory(n.category) }"
            style="margin-left: 4px;"
          >{{ n.category }}</el-tag>
        </div>
      </div>
      <div class="node-row node-row--bottom" v-if="n.origin === 'SCRIPT'">
        <div class="node-actions">
          <el-button link size="small" type="primary" @click.stop="onEdit(n)">{{ t('nodePanel.edit') }}</el-button>
          <el-button link size="small" type="danger" @click.stop="onDelete(n)">{{ t('nodePanel.delete') }}</el-button>
        </div>
      </div>
    </div>
    <el-empty v-if="filteredNodes.length === 0" :description="t('nodePanel.empty')" :image-size="40" />
    <ScriptNodeEditor v-model:visible="showScriptEditor" :edit-node="editingNode" @created="onScriptCreated" />
  </div>
</template>

<style scoped>
.node-panel { padding: 12px; }
.node-panel--embedded {
  padding: 10px;
}
.node-panel--embedded .panel-header {
  font-size: 13px;
  margin-bottom: 6px;
}
.node-panel--embedded .canvas-hint :deep(.el-alert__title) {
  font-size: 12px;
}
.panel-header {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
  color: var(--agd-color-text, #303133);
}
.canvas-hint {
  margin-bottom: 8px;
}
.node-item {
  display: flex; flex-direction: column;
  padding: 8px 10px; margin-bottom: 4px;
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 4px;
  cursor: grab; transition: all 0.2s;
}
.node-item:hover {
  border-color: var(--agd-color-primary, #409eff);
  background: var(--agd-color-bg-active, #ecf5ff);
}
.node-row { display: flex; align-items: center; justify-content: space-between; }
.node-row--bottom { margin-top: 6px; }
.node-name { font-size: 13px; }
.node-info { display: flex; align-items: center; flex: 1; min-width: 0; }
.node-actions { display: flex; gap: 4px; }
:deep(.hitl-tag) {
  --el-tag-bg-color: #f3e8ff;
  --el-tag-border-color: #d8b4fe;
  --el-tag-text-color: #7c3aed;
}
</style>
