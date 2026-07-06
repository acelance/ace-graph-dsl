<script setup>
import { ref, watch, computed } from 'vue'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useNodeRegistryStore } from '../../stores/nodeRegistry'
import { useI18n } from '../../i18n'

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
</script>

<template>
  <div class="property-panel" :class="{ 'property-panel--embedded': embedded }">
    <div v-if="!embedded" class="panel-header">{{ t('propertyPanel.title') }}</div>
    <el-tabs v-model="activeTab" :class="{ 'embedded-tabs': embedded }">
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
</style>
