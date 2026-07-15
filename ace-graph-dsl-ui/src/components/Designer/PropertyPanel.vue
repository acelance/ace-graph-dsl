<script setup>
import { ref, watch, computed } from 'vue'
import { Delete } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { useI18n } from '../../i18n'
import { listScriptEngines } from '../../api/graph'

const props = defineProps({
  /** 嵌入左侧目录时使用紧凑布局 */
  embedded: { type: Boolean, default: false }
})

const editor = useGraphEditorStore()
const nodeStore = useNodeRegistryStore()
const { t } = useI18n()

const activeTab = ref('node')
const keyStrategyRows = ref([])

watch(() => editor.keyStrategies, (ks) => {
  keyStrategyRows.value = Object.entries(ks || {}).map(([k, v]) => ({ key: k, strategy: v }))
}, { immediate: true, deep: true })

const selectedDescriptor = computed(() => {
  if (!editor.selectedNode) return null
  return nodeStore.nodes.find(n => n.nodeId === editor.selectedNode.nodeId) || null
})

const configSchemaEntries = computed(() => {
  const props = selectedDescriptor.value?.configurableProps || {}
  return Object.entries(props).map(([key, schema]) => ({ key, schema }))
})

function ensureNodeConfig(key, defaultValue) {
  if (!editor.selectedNode) return
  if (editor.selectedNode.config[key] === undefined) {
    editor.selectedNode.config[key] = defaultValue
    editor.updateSelectedNodeConfig(editor.selectedNode.config)
  }
}

watch(configSchemaEntries, (entries) => {
  for (const { key, schema } of entries) {
    ensureNodeConfig(key, schema.defaultValue)
  }
}, { immediate: true })

function onConfigChange(key, value) {
  if (!editor.selectedNode) return
  editor.selectedNode.config[key] = value
  editor.updateSelectedNodeConfig({ ...editor.selectedNode.config })
}

function addKey() {
  editor.keyStrategies[`custom_${Date.now()}`] = 'REPLACE'
}

function removeKey(k) {
  delete editor.keyStrategies[k]
}

/* ───────── 条件边编辑区 ───────── */
const edgeEngines = ref([])
const edgeEngine = ref('aviator')
const edgeCondition = ref('')
const edgeMappingRows = ref([])

const edgeIsConditional = computed(() => editor.selectedEdge?.type === 'conditional')

const edgeNodeOptions = computed(() => {
  // dispatcher 模式：target 只能在该 dispatcher 声明的 possibleTargets 范围内选择
  const e = editor.selectedEdge
  if (e && e.dispatcher) {
    const d = nodeStore.dispatchers.find(d => d.dispatcherId === e.dispatcher)
    if (d && Array.isArray(d.possibleTargets) && d.possibleTargets.length) {
      return d.possibleTargets
    }
  }
  // 脚本路由模式：target 可为图中任意节点（含 __END__）
  return (editor.nodes || [])
    .map(n => n.nodeId)
    .filter(id => id && id !== '__START__')
    .concat(['__END__'])
})

const selectedEdgeEngineMeta = computed(() =>
  edgeEngines.value.find(e => e.id === edgeEngine.value) || null
)
const edgeScriptRows = computed(() => (selectedEdgeEngineMeta.value?.multiLine ? 14 : 3))
const edgeScriptHint = computed(() => {
  const key = selectedEdgeEngineMeta.value?.hintKey
  if (key) {
    const txt = t(key)
    if (txt && txt !== key) return txt
  }
  return t('edgeEditor.conditionHint')
})

async function fetchEdgeEngines() {
  try {
    const list = await listScriptEngines()
    edgeEngines.value = list && list.length ? list : [{ id: 'aviator', label: 'Aviator 表达式' }]
  } catch {
    edgeEngines.value = [{ id: 'aviator', label: 'Aviator 表达式' }]
  }
}

function resetEdgeForm() {
  const e = editor.selectedEdge
  if (!e) return
  edgeEngine.value = e.conditionEngine || 'aviator'
  edgeCondition.value = e.condition || ''
  edgeMappingRows.value = Object.entries(e.mapping || {}).map(([k, v]) => ({ key: k, target: v }))
}

watch(() => editor.selectedEdge, (e) => {
  if (e) {
    fetchEdgeEngines()
    resetEdgeForm()
  }
}, { immediate: true })

function addMappingRow() {
  edgeMappingRows.value.push({ key: '', target: '' })
}

function removeMappingRow(idx) {
  edgeMappingRows.value.splice(idx, 1)
}

function applyEdgeEdit() {
  const e = editor.selectedEdge
  if (!e) return
  // 脚本路由：引擎须在 /nodes/engines 列表中（导入非法引擎时「应用」应拦截，与校验/试运行一致）
  if (!e.dispatcher && edgeCondition.value) {
    const engineId = (edgeEngine.value || '').trim() || 'aviator'
    const known = edgeEngines.value.some(en => en.id === engineId)
    if (!known) {
      ElMessage.error(t('edgeEditor.unknownEngine', { engine: engineId }))
      return
    }
  }
  const mapping = {}
  for (const r of edgeMappingRows.value) {
    if (r.key && r.target) mapping[r.key] = r.target
  }
  editor.requestEdgeEdit({
    lfEdgeId: e.lfEdgeId,
    from: e.from,
    oldDispatcher: e.dispatcher,
    oldCondition: e.condition,
    conditionEngine: edgeEngine.value,
    condition: edgeCondition.value,
    mapping
  })
  ElMessage.success(t('edgeEditor.applyOk'))
}

function convertEdge() {
  const e = editor.selectedEdge
  if (e) editor.requestEdgeConvert(e.lfEdgeId)
}
</script>

<template>
  <div class="property-panel" :class="{ 'property-panel--embedded': embedded }">
    <div v-if="!embedded" class="panel-header">{{ editor.selectedEdge ? t('edgeEditor.title') : t('propertyPanel.title') }}</div>

    <template v-if="editor.selectedEdge">
      <div class="edge-editor">
        <el-form label-width="92px" size="small">
          <el-form-item :label="t('edgeEditor.type')">
            <el-tag :type="edgeIsConditional ? 'warning' : 'info'" size="small">
              {{ edgeIsConditional ? t('edgeEditor.conditional') : t('edgeEditor.normal') }}
            </el-tag>
          </el-form-item>
          <el-form-item :label="t('edgeEditor.from')">
            <el-input :model-value="editor.selectedEdge.from" disabled />
          </el-form-item>

          <template v-if="edgeIsConditional">
            <el-form-item :label="t('edgeEditor.routingMode')">
              <el-tag v-if="editor.selectedEdge.dispatcher" size="small" type="success">{{ t('edgeEditor.dispatcherMode') }}: {{ editor.selectedEdge.dispatcher }}</el-tag>
              <el-tag v-else size="small" type="warning">{{ t('edgeEditor.scriptMode') }}</el-tag>
            </el-form-item>
            <el-form-item :label="t('edgeEditor.engine')">
              <el-select v-model="edgeEngine" style="width: 100%;">
                <el-option v-for="en in edgeEngines" :key="en.id" :label="en.label" :value="en.id" />
              </el-select>
            </el-form-item>
            <el-form-item :label="t('edgeEditor.condition')">
              <el-input v-model="edgeCondition" type="textarea" :rows="edgeScriptRows" />
              <div class="hint">{{ edgeScriptHint }}</div>
            </el-form-item>
            <el-form-item :label="t('edgeEditor.mapping')">
              <div class="mapping-editor">
                <div v-for="(row, idx) in edgeMappingRows" :key="idx" class="mapping-row">
                  <el-input v-model="row.key" :placeholder="t('edgeEditor.mappingKey')" class="mapping-key" />
                  <el-select v-model="row.target" :placeholder="t('edgeEditor.mappingTarget')" class="mapping-target">
                    <el-option v-for="opt in edgeNodeOptions" :key="opt" :label="opt" :value="opt" />
                  </el-select>
                  <el-button type="danger" link :icon="Delete" @click="removeMappingRow(idx)" />
                </div>
                <el-button size="small" @click="addMappingRow">{{ t('edgeEditor.addMapping') }}</el-button>
              </div>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" size="small" @click="applyEdgeEdit">{{ t('edgeEditor.apply') }}</el-button>
            </el-form-item>
          </template>

          <template v-else>
            <el-form-item :label="t('edgeEditor.to')">
              <el-input :model-value="editor.selectedEdge.to" disabled />
            </el-form-item>
            <el-alert :title="t('edgeEditor.noConditional')" type="info" :closable="false" />
            <el-form-item>
              <el-button size="small" @click="convertEdge">{{ t('edgeEditor.convert') }}</el-button>
            </el-form-item>
          </template>
        </el-form>
      </div>
    </template>

    <el-tabs v-else v-model="activeTab" :class="{ 'embedded-tabs': embedded }">
      <el-tab-pane :label="t('propertyPanel.tabNode')" name="node">
        <template v-if="editor.selectedNode && selectedDescriptor">
          <el-form label-width="100px" size="small">
            <el-form-item :label="t('propertyPanel.nodeId')">
              <el-input :model-value="editor.selectedNode.nodeId" disabled />
            </el-form-item>
            <el-form-item :label="t('propertyPanel.displayName')">
              <el-input :model-value="selectedDescriptor.displayName" disabled />
            </el-form-item>
            <el-form-item :label="t('propertyPanel.origin')">
              <el-tag size="small" :type="selectedDescriptor.origin === 'SCRIPT' ? 'success' : 'info'">
                {{ selectedDescriptor.origin || 'BUILTIN' }}
              </el-tag>
            </el-form-item>
            <el-form-item :label="t('propertyPanel.inputKeys')">
              <el-input :model-value="(selectedDescriptor.inputKeys || []).join(', ')" disabled />
            </el-form-item>
            <el-form-item :label="t('propertyPanel.outputKeys')">
              <el-input :model-value="(selectedDescriptor.outputKeys || []).join(', ')" disabled />
            </el-form-item>
            <template v-for="entry in configSchemaEntries" :key="entry.key">
              <el-form-item :label="entry.schema.label || entry.key">
                <el-input
                  v-if="entry.schema.type === 'string'"
                  :model-value="editor.selectedNode.config[entry.key]"
                  @update:model-value="onConfigChange(entry.key, $event)"
                />
                <el-input-number
                  v-else-if="entry.schema.type === 'number'"
                  :model-value="editor.selectedNode.config[entry.key]"
                  @update:model-value="onConfigChange(entry.key, $event)"
                  style="width: 100%;"
                />
                <el-switch
                  v-else-if="entry.schema.type === 'boolean'"
                  :model-value="!!editor.selectedNode.config[entry.key]"
                  @update:model-value="onConfigChange(entry.key, $event)"
                />
                <el-select
                  v-else-if="entry.schema.type === 'select'"
                  :model-value="editor.selectedNode.config[entry.key]"
                  @update:model-value="onConfigChange(entry.key, $event)"
                  style="width: 100%;"
                >
                  <el-option
                    v-for="opt in (entry.schema.extra?.options || [])"
                    :key="opt.value ?? opt"
                    :label="opt.label ?? opt"
                    :value="opt.value ?? opt"
                  />
                </el-select>
                <el-input
                  v-else
                  :model-value="editor.selectedNode.config[entry.key]"
                  @update:model-value="onConfigChange(entry.key, $event)"
                />
              </el-form-item>
            </template>
            <el-empty v-if="configSchemaEntries.length === 0" :description="t('propertyPanel.noConfig')" :image-size="40" />
          </el-form>
        </template>
        <el-empty v-else :description="t('propertyPanel.clickNode')" :image-size="60" />
      </el-tab-pane>

      <el-tab-pane :label="t('propertyPanel.tabMeta')" name="meta">
        <el-form label-width="90px" size="small">
          <el-form-item :label="t('propertyPanel.graphId')">
            <el-input v-model="editor.graphId" disabled />
          </el-form-item>
          <el-form-item :label="t('propertyPanel.version')">
            <el-input v-model="editor.version" />
          </el-form-item>
          <el-form-item :label="t('propertyPanel.displayName')">
            <el-input v-model="editor.displayName" />
          </el-form-item>
          <el-form-item :label="t('propertyPanel.description')">
            <el-input v-model="editor.description" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane :label="t('propertyPanel.tabCompile')" name="compile">
        <el-form label-width="100px" size="small">
          <el-form-item :label="t('propertyPanel.saver')">
            <el-radio-group v-model="editor.saver">
              <el-radio value="memory">Memory</el-radio>
              <el-radio value="jdbc">JDBC</el-radio>
              <el-radio value="redis">Redis</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item :label="t('propertyPanel.interruptBefore')">
            <el-select v-model="editor.interruptBefore" multiple filterable :placeholder="t('propertyPanel.interruptPlaceholder')" style="width: 100%;">
              <el-option v-for="n in editor.nodes" :key="n.nodeId" :label="n.nodeId" :value="n.nodeId" />
            </el-select>
          </el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane :label="t('propertyPanel.tabKeys')" name="keys">
        <el-table :data="keyStrategyRows" size="small" max-height="400">
          <el-table-column prop="key" :label="t('propertyPanel.stateKey')" min-width="120" />
          <el-table-column :label="t('propertyPanel.strategy')" width="110">
            <template #default="{ row }">
              <el-select v-model="row.strategy" size="small">
                <el-option label="REPLACE" value="REPLACE" />
                <el-option label="APPEND" value="APPEND" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="50">
            <template #default="{ row }">
              <el-button type="danger" size="small" link @click="removeKey(row.key)">X</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" @click="addKey" style="margin-top: 8px;">{{ t('propertyPanel.addKey') }}</el-button>
      </el-tab-pane>

      <el-tab-pane :label="t('propertyPanel.tabValidation')" name="validation">
        <div v-if="editor.validationErrors.length === 0" class="validation-ok">
          {{ t('propertyPanel.validationOk') }}
        </div>
        <el-alert
          v-for="(e, i) in editor.validationErrors"
          :key="i"
          :title="e"
          type="error"
          :closable="false"
          style="margin-bottom: 4px;"
        />
      </el-tab-pane>

      <el-tab-pane :label="t('propertyPanel.tabPlantUml')" name="plantuml">
        <el-input
          v-if="editor.plantUmlContent"
          :model-value="editor.plantUmlContent"
          type="textarea"
          :rows="20"
          readonly
        />
        <el-empty v-else :description="t('propertyPanel.previewHint')" :image-size="60" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.property-panel { padding: 12px; }
.property-panel--embedded {
  padding: 8px 10px 10px;
}
.property-panel--embedded :deep(.el-form-item) {
  margin-bottom: 12px;
}
.property-panel--embedded :deep(.el-form-item__label) {
  font-size: 12px;
}
.property-panel--embedded :deep(.embedded-tabs .el-tabs__item) {
  font-size: 12px;
  padding: 0 10px;
}
.panel-header {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 12px;
  color: var(--agd-color-text, #303133);
}
.validation-ok {
  color: var(--agd-color-success, #67c23a);
  padding: 20px;
  text-align: center;
}
.edge-editor { padding: 4px 2px; }
.edge-editor .hint { font-size: 12px; color: var(--agd-color-text-secondary, #909399); margin-top: 4px; }
.mapping-editor { width: 100%; }
.mapping-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.mapping-key { flex: 1; min-width: 0; }
.mapping-target { flex: 1.4; min-width: 0; }
</style>
