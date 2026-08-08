<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'
import { useAgentEditorBus } from '../../stores/agentEditorBus'
import { deleteScriptNode, listReferringGraphs, deleteAgentNode, listAgentReferences } from '../../api/graph'
import ScriptNodeEditor from './ScriptNodeEditor.vue'
import AgentNodeEditor from './AgentNodeEditor.vue'

const nodeStore = useNodeRegistryStore()
const perm = usePermissionStore()
const { t } = useI18n()

const keyword = ref('')
const activeTab = ref('ALL')
const showScriptEditor = ref(false)
const editingNode = ref(null)
const showAgentEditor = ref(false)
const editingAgent = ref(null)
const props = defineProps({ embedded: { type: Boolean, default: false } })
const emit = defineEmits(['node-drag'])
// P3 UX：节点面板可折叠为抽屉；默认不折叠（向后兼容既有页面）
const collapsed = defineModel('collapsed', { type: Boolean, default: false })

const filteredNodes = computed(() => {
  let list = nodeStore.nodes
    // 隐藏 agent:script（代码岛节点 toAction 会抛异常，拖入画布会崩溃；已搁置）
    .filter(n => n.category !== 'AGENT')
    .filter(n =>
      (n.displayName || '').toLowerCase().includes(keyword.value.toLowerCase()) ||
      n.nodeId.toLowerCase().includes(keyword.value.toLowerCase())
    )
  if (activeTab.value !== 'ALL') {
    list = list.filter(n => {
      if (activeTab.value === 'AGENT') return n.origin === 'GENERIC_AGENT'
      return (n.origin || 'BUILTIN') === activeTab.value
    })
  }
  return list
})

/** 结构型节点（不在注册表中）：子图，从面板拖入画布。
 * 注意：通用 Agent 走「定义→入库→复用」注册式流程（AGENT tab 入口）；
 * Agent（代码岛）已搁置——其"智能"属后端代码，与可视化设计器定位矛盾，
 * 后端代码保留但面板不暴露入口。 */
const structuralNodes = computed(() => ([
  { nodeId: '', category: 'SUBGRAPH', displayName: t('nodePanel.subgraph'), isStructural: true, inputKeys: [], outputKeys: [] }
]))

function onDragStart(e, n) {
  e.dataTransfer.effectAllowed = 'copy'
  e.dataTransfer.setData('application/node', JSON.stringify(n))
  emit('node-drag', n)
}

function categoryTagType(c) {
  return { NORMAL: 'info', ROUTER: 'warning', MERGE: 'success', HITL: '', SUBGRAPH: 'primary', AGENT: 'success', GENERIC_AGENT: 'danger' }[c] || 'info'
}

function isHitlCategory(c) {
  return c === 'HITL'
}

function canEdit(node) {
  return node.origin === 'SCRIPT' || node.origin === 'GENERIC_AGENT'
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

// 跨组件：属性面板选中「注册式通用 Agent」节点时，请求此处打开编辑器
const agentBus = useAgentEditorBus()
watch(
  () => agentBus.requestId,
  async () => {
    const nodeId = agentBus.nodeId
    if (!nodeId) return
    await ensureRegistryLoaded()
    const target = nodeStore.nodes.find(n => n.origin === 'GENERIC_AGENT' && n.nodeId === nodeId)
    if (target) onEdit(target)
  }
)

async function onScriptCreated() {
  await nodeStore.fetchNodes()
}

async function onAgentCreated() {
  await nodeStore.fetchNodes()
}

function onEdit(node) {
  if (node.origin === 'GENERIC_AGENT') {
    editingAgent.value = node
    showAgentEditor.value = true
  } else {
    editingNode.value = node
    showScriptEditor.value = true
  }
}

function onNew() {
  editingNode.value = null
  showScriptEditor.value = true
}

function onAgentNew() {
  editingAgent.value = null
  showAgentEditor.value = true
}

async function onDelete(node) {
  try {
    const refs = node.origin === 'GENERIC_AGENT'
      ? await listAgentReferences(node.nodeId)
      : await listReferringGraphs(node.nodeId)
    let message = t('nodePanel.deleteConfirm', { name: node.displayName || node.nodeId })
    if (node.origin === 'GENERIC_AGENT') {
      message = t('nodePanel.deleteAgentConfirm', { name: node.displayName || node.nodeId })
    }
    if (refs.length > 0) {
      message = t('nodePanel.referenceWarning') + '\n' + refs.join(', ') + '\n\n' + message
    }
    await ElMessageBox.confirm(message, t('nodePanel.delete'), { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') })
    if (node.origin === 'GENERIC_AGENT') {
      await deleteAgentNode(node.nodeId)
    } else {
      await deleteScriptNode(node.nodeId)
    }
    ElMessage.success(t('common.confirm'))
    await nodeStore.fetchNodes()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.response?.data?.error || e.message)
  }
}
</script>

<template>
  <div class="node-panel" :class="{ 'node-panel--embedded': props.embedded, 'node-panel--collapsed': collapsed }" v-loading="!collapsed && nodeStore.loading">
    <!-- 折叠把手：仅在 collapsed 时显示 -->
    <div v-show="collapsed" class="collapse-handle" :title="t('nodePanel.expand')" @click="collapsed = false">
      <el-icon class="handle-icon"><ArrowLeft /></el-icon>
      <span class="handle-text">{{ t('nodePanel.title') }}</span>
    </div>

    <!-- 完整内容：仅在展开时显示 -->
    <div v-show="!collapsed" class="panel-content">
      <div class="panel-header">
        <span>{{ t('nodePanel.title') }}</span>
        <el-button link size="small" class="collapse-btn" :title="t('nodePanel.collapse')" @click="collapsed = true">
          <el-icon><ArrowRight /></el-icon>
        </el-button>
      </div>
    <div class="structural-section">
      <div class="structural-title">{{ t('nodePanel.structural') }}</div>
      <div
        v-for="s in structuralNodes"
        :key="s.category"
        class="node-item"
        draggable="true"
        @dragstart="onDragStart($event, s)"
        @click="emit('node-drag', s)"
      >
        <div class="node-row node-row--top">
          <div class="node-info">
            <span class="node-name">{{ s.displayName }}</span>
            <el-tag size="small" :type="categoryTagType(s.category)" style="margin-left: 4px;">{{ s.category }}</el-tag>
          </div>
        </div>
      </div>
    </div>
    <el-alert :title="t('nodePanel.canvasHint')" type="info" :closable="false" show-icon class="canvas-hint" />
    <el-input v-model="keyword" :placeholder="t('nodePanel.search')" clearable size="small" style="margin-bottom: 8px;" />
    <el-tabs v-model="activeTab" size="small" style="margin-bottom: 8px;">
      <el-tab-pane :label="t('nodePanel.all')" name="ALL" />
      <el-tab-pane :label="t('nodePanel.builtin')" name="BUILTIN" />
      <el-tab-pane :label="t('nodePanel.script')" name="SCRIPT">
        <el-button v-if="perm.can(MENU.SCRIPT_NODE_CREATE)" type="primary" size="small" style="width: 100%; margin-bottom: 8px;" @click="onNew">
          {{ t('nodePanel.createScript') }}
        </el-button>
      </el-tab-pane>
      <el-tab-pane :label="t('nodePanel.agentTab')" name="AGENT">
        <el-button v-if="perm.can(MENU.AGENT_NODE_CREATE)" type="success" size="small" style="width: 100%; margin-bottom: 8px;" @click="onAgentNew">
          {{ t('nodePanel.createAgent') }}
        </el-button>
      </el-tab-pane>
    </el-tabs>
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
      <div class="node-row node-row--bottom" v-if="canEdit(n)">
        <div class="node-actions">
          <el-button link size="small" type="primary" @click.stop="onEdit(n)">{{ t('nodePanel.edit') }}</el-button>
          <el-button link size="small" type="danger" @click.stop="onDelete(n)">{{ t('nodePanel.delete') }}</el-button>
        </div>
      </div>
    </div>
    <el-empty v-if="filteredNodes.length === 0" :description="t('nodePanel.empty')" :image-size="40" />
    <ScriptNodeEditor v-model:visible="showScriptEditor" :edit-node="editingNode" @created="onScriptCreated" />
    <AgentNodeEditor v-model:visible="showAgentEditor" :edit-node="editingAgent" @created="onAgentCreated" />
    </div><!-- /.panel-content -->
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
.structural-section {
  margin-bottom: 8px;
  padding: 0 2px;
}
.structural-title {
  font-size: 12px;
  color: var(--agd-color-text-secondary, #909399);
  margin-bottom: 4px;
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

/* 折叠态：抽屉收起为右侧窄条，仅显示竖向把手 */
.node-panel--collapsed {
  width: 38px;
  min-width: 38px;
  padding: 8px 4px;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;   /* 把手垂直居中于面板可视区域 */
  height: 100%;
}
.collapse-handle {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 6px;
  margin-top: 12px;           /* 与展开态 header 的 padding(12px) 对齐 */
  border-radius: 20px;        /* 胶囊圆角 */
  cursor: pointer;
  color: var(--agd-color-text-secondary, #909399);
  background: rgba(0, 0, 0, 0.03);
  border: 1px solid transparent;
  user-select: none;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.collapse-handle:hover {
  color: var(--agd-color-primary, #409eff);
  background: rgba(64, 158, 255, 0.08);
  border-color: rgba(64, 158, 255, 0.2);
  transform: translateX(-1px); /* 微微左移暗示可展开 */
}
.handle-icon { font-size: 14px; }
.handle-text {
  writing-mode: vertical-rl;
  letter-spacing: 3px;
  font-size: 11px;
  line-height: 1.4;
}
.collapse-btn { margin-left: auto; padding: 2px; }
</style>
