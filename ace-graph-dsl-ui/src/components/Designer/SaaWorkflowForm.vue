<script setup>
/**
 * SAA_WORKFLOW 属性表单（M2：四 pattern + 子 Agent 表 + ROUTING/LOOP 字段）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from '../../i18n'
import { listAgentDefinitions } from '../../api/graph'
import { defaultSubAgent, normalizeSaaSpec, SAA_PATTERNS } from '../../utils/saaWorkflow'

const props = defineProps({
  /** 当前 saaSpec */
  modelValue: { type: Object, default: null },
  /** 模块是否启用（null=未知，false=未启用） */
  moduleEnabled: { type: Boolean, default: true },
  /** AgentScope 子 Agent 模块是否启用（M3） */
  agentscopeEnabled: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue'])

const { t } = useI18n()
const agentOptions = ref([])
const agentLoading = ref(false)

const spec = computed(() => normalizeSaaSpec(props.modelValue) || normalizeSaaSpec({
  pattern: 'SEQUENTIAL',
  inputKeys: 'user_query',
  outputKey: 'agent_result',
  subAgents: []
}))

const isRouting = computed(() => spec.value.pattern === 'ROUTING')
const isLoop = computed(() => spec.value.pattern === 'LOOP')

function patch(partial) {
  emit('update:modelValue', { ...spec.value, ...partial })
}

function updateField(field, value) {
  patch({ [field]: value })
}

/** pattern 切换时按需补默认子 Agent 数量（PARALLEL/ROUTING 至少 2） */
function onPatternChange(pattern) {
  const list = [...(spec.value.subAgents || [])]
  if ((pattern === 'PARALLEL' || pattern === 'ROUTING') && list.length < 2) {
    while (list.length < 2) {
      list.push(defaultSubAgent(list.length + 1))
    }
  }
  if (pattern === 'LOOP' && (spec.value.maxIterations == null || spec.value.maxIterations === '')) {
    patch({ pattern, subAgents: list, maxIterations: 3 })
    return
  }
  patch({ pattern, subAgents: list })
}

function updateSubAgent(index, field, value) {
  const list = (spec.value.subAgents || []).map((row, i) =>
    i === index ? { ...row, [field]: value } : { ...row })
  patch({ subAgents: list })
}

function addSubAgent() {
  const list = [...(spec.value.subAgents || [])]
  list.push(defaultSubAgent(list.length + 1))
  patch({ subAgents: list })
}

function removeSubAgent(index) {
  const list = (spec.value.subAgents || []).filter((_, i) => i !== index)
  patch({ subAgents: list })
}

function onRefChange(index, val) {
  const trimmed = (val || '').trim()
  const row = spec.value.subAgents[index]
  const impl = (row?.impl || 'GENERIC_AGENT').toUpperCase()
  let ref = trimmed
  if (trimmed && !trimmed.includes(':')) {
    const prefix = impl === 'AGENTSCOPE' ? 'agentscope:' : 'generic:'
    ref = `${prefix}${trimmed}`
  }
  updateSubAgent(index, 'ref', ref)
  // 若 name 仍为默认，用注册 id 填充
  if (row && (!row.name || /^agent_\d+$/.test(row.name))) {
    let id = trimmed
    if (ref.startsWith('generic:')) id = ref.slice('generic:'.length)
    else if (ref.startsWith('agentscope:')) id = ref.slice('agentscope:'.length)
    if (id) updateSubAgent(index, 'name', id.replace(/[^a-zA-Z0-9_]/g, '_'))
  }
}

/** 切换 impl 时同步 ref 前缀 */
function onImplChange(index, impl) {
  updateSubAgent(index, 'impl', impl)
  const row = spec.value.subAgents[index]
  const raw = (row?.ref || '').trim()
  if (!raw) return
  const id = raw.includes(':') ? raw.slice(raw.indexOf(':') + 1) : raw
  const prefix = impl === 'AGENTSCOPE' ? 'agentscope:' : 'generic:'
  updateSubAgent(index, 'ref', `${prefix}${id}`)
}

async function loadAgents() {
  agentLoading.value = true
  try {
    const list = await listAgentDefinitions()
    agentOptions.value = Array.isArray(list) ? list : []
  } catch (e) {
    console.warn('[SaaWorkflowForm] listAgentDefinitions failed', e)
    agentOptions.value = []
  } finally {
    agentLoading.value = false
  }
}

onMounted(loadAgents)

watch(() => props.modelValue, (v) => {
  if (!v) {
    emit('update:modelValue', normalizeSaaSpec({
      pattern: 'SEQUENTIAL',
      inputKeys: 'user_query',
      outputKey: 'agent_result',
      streamResponseKind: 'BIZ',
      subAgents: [defaultSubAgent(1)]
    }))
  }
}, { immediate: true })
</script>

<template>
  <div class="saa-workflow-form">
    <el-alert
      v-if="moduleEnabled === false"
      :title="t('propertyPanel.saa.moduleDisabled')"
      type="warning"
      :closable="false"
      style="margin-bottom: 8px;"
    />
    <el-alert
      :title="t('propertyPanel.saa.note')"
      type="info"
      :closable="false"
      style="margin-bottom: 8px;"
    />
    <div class="hint" style="margin: -4px 0 10px;">
      {{ t('propertyPanel.saa.faqLink') }}
      <code style="font-size: 11px;">docs/ACE-Graph-DSL-何时用图边何时用高阶节点.md</code>
    </div>

    <el-form-item :label="t('propertyPanel.saa.pattern')">
      <el-select
        :model-value="spec.pattern"
        style="width: 100%;"
        @update:model-value="onPatternChange"
      >
        <el-option v-for="p in SAA_PATTERNS" :key="p" :label="p" :value="p" />
      </el-select>
      <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.saa.patternHint') }}</span>
    </el-form-item>

    <el-form-item :label="t('propertyPanel.saa.inputKeys')">
      <el-input
        :model-value="spec.inputKeys"
        :placeholder="t('propertyPanel.saa.inputKeysPlaceholder')"
        @update:model-value="updateField('inputKeys', $event)"
      />
    </el-form-item>
    <el-form-item :label="t('propertyPanel.saa.outputKey')">
      <el-input
        :model-value="spec.outputKey"
        @update:model-value="updateField('outputKey', $event)"
      />
    </el-form-item>
    <el-form-item :label="t('propertyPanel.saa.streamResponseKind')">
      <el-input
        :model-value="spec.streamResponseKind"
        placeholder="BIZ"
        @update:model-value="updateField('streamResponseKind', $event)"
      />
    </el-form-item>

    <template v-if="isRouting">
      <el-divider content-position="left">{{ t('propertyPanel.saa.routingSection') }}</el-divider>
      <el-form-item :label="t('propertyPanel.saa.modelConfigKey')">
        <el-input
          :model-value="spec.modelConfigKey"
          :placeholder="t('propertyPanel.saa.modelConfigKeyPlaceholder')"
          @update:model-value="updateField('modelConfigKey', $event)"
        />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.saa.modelConfigKeyHint') }}</span>
      </el-form-item>
    </template>

    <template v-if="isLoop">
      <el-divider content-position="left">{{ t('propertyPanel.saa.loopSection') }}</el-divider>
      <el-form-item :label="t('propertyPanel.saa.maxIterations')">
        <el-input-number
          :model-value="spec.maxIterations == null || spec.maxIterations === '' ? 3 : Number(spec.maxIterations)"
          :min="1"
          :max="10"
          controls-position="right"
          style="width: 100%;"
          @update:model-value="updateField('maxIterations', $event)"
        />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.saa.maxIterationsHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.exitConditionKey')">
        <el-input
          :model-value="spec.exitConditionKey"
          :placeholder="t('propertyPanel.saa.exitConditionKeyPlaceholder')"
          @update:model-value="updateField('exitConditionKey', $event)"
        />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.exitConditionOp')">
        <el-select
          :model-value="spec.exitConditionOp || 'GT'"
          clearable
          style="width: 100%;"
          @update:model-value="updateField('exitConditionOp', $event)"
        >
          <el-option label="GT" value="GT" />
          <el-option label="GTE" value="GTE" />
          <el-option label="LT" value="LT" />
          <el-option label="LTE" value="LTE" />
          <el-option label="EQ" value="EQ" />
          <el-option label="NEQ" value="NEQ" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.exitConditionValue')">
        <el-input
          :model-value="spec.exitConditionValue"
          :placeholder="t('propertyPanel.saa.exitConditionValuePlaceholder')"
          @update:model-value="updateField('exitConditionValue', $event)"
        />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.saa.exitConditionHint') }}</span>
      </el-form-item>
    </template>

    <el-divider content-position="left">{{ t('propertyPanel.saa.subAgents') }}</el-divider>
    <div v-for="(row, index) in spec.subAgents" :key="index" class="sub-agent-card">
      <div class="sub-agent-header">
        <span>#{{ index + 1 }}</span>
        <el-button link type="danger" size="small" @click="removeSubAgent(index)">
          {{ t('nodePanel.delete') }}
        </el-button>
      </div>
      <el-form-item :label="t('propertyPanel.saa.subName')">
        <el-input :model-value="row.name" @update:model-value="updateSubAgent(index, 'name', $event)" />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.subImpl')">
        <el-select
          :model-value="row.impl || 'GENERIC_AGENT'"
          style="width: 100%;"
          @update:model-value="onImplChange(index, $event)"
        >
          <el-option label="GENERIC_AGENT" value="GENERIC_AGENT" />
          <el-option
            v-if="agentscopeEnabled"
            label="AGENTSCOPE"
            value="AGENTSCOPE"
          />
        </el-select>
        <span class="hint" style="display:block; margin-top:4px;">
          {{ agentscopeEnabled ? t('propertyPanel.saa.subImplHint') : t('propertyPanel.saa.subImplHintNoAgentscope') }}
        </span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.subRef')">
        <el-select
          :model-value="row.ref"
          filterable
          allow-create
          default-first-option
          :loading="agentLoading"
          :placeholder="t('propertyPanel.saa.subRefPlaceholder')"
          style="width: 100%;"
          @update:model-value="onRefChange(index, $event)"
        >
          <el-option
            v-for="a in agentOptions"
            :key="a.nodeId || a.id"
            :label="`${a.displayName || a.nodeId} (${(row.impl || 'GENERIC_AGENT') === 'AGENTSCOPE' ? 'agentscope' : 'generic'}:${a.nodeId})`"
            :value="`${(row.impl || 'GENERIC_AGENT') === 'AGENTSCOPE' ? 'agentscope' : 'generic'}:${a.nodeId}`"
          />
        </el-select>
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.saa.subRefHint') }}</span>
      </el-form-item>      <el-form-item :label="t('propertyPanel.saa.subInstruction')">
        <el-input
          type="textarea"
          :rows="2"
          :model-value="row.instruction"
          :placeholder="t('propertyPanel.saa.subInstructionPlaceholder')"
          @update:model-value="updateSubAgent(index, 'instruction', $event)"
        />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.saa.subOutputKey')">
        <el-input :model-value="row.outputKey" @update:model-value="updateSubAgent(index, 'outputKey', $event)" />
      </el-form-item>
    </div>
    <el-button size="small" type="primary" plain @click="addSubAgent">
      {{ t('propertyPanel.saa.addSubAgent') }}
    </el-button>
  </div>
</template>

<style scoped>
.sub-agent-card {
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 8px 10px 2px;
  margin-bottom: 10px;
  background: var(--el-fill-color-blank, #fff);
}
.sub-agent-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
.hint {
  font-size: 12px;
  color: var(--el-text-color-secondary, #909399);
}
</style>
