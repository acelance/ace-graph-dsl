<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createAgentNode, updateAgentNode, validateAgentNode, testRunAgentDraft, getAgentDefinition
} from '../../api/graph'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'

const perm = usePermissionStore()
const { t } = useI18n()

const props = defineProps({ editNode: { type: Object, default: null } })
const visible = defineModel('visible', { type: Boolean, default: false })
const emit = defineEmits(['created'])

const saving = ref(false)
const testing = ref(false)
const loading = ref(false)
const testOutput = ref(null)

const defaultForm = () => ({
  nodeId: '',
  displayName: '',
  description: '',
  modelBaseUrl: '',
  modelApiKey: '',
  apiKeyMasked: false,
  modelId: '',
  prompt: '',
  promptKey: '',
  skill: '',
  skillKey: '',
  mcp: '',
  mcpKey: '',
  toolsText: '',
  inputKeysText: '',
  outputKey: 'agent_result',
  permissionTagsText: 'public',
  mockStateJson: '{"user_query":"hello"}'
})

const form = ref(defaultForm())

function applyDefinition(def) {
  const s = def.spec || {}
  form.value.nodeId = def.nodeId || ''
  form.value.displayName = def.displayName || ''
  form.value.description = def.description || ''
  form.value.modelBaseUrl = s.modelBaseUrl || ''
  form.value.modelApiKey = s.modelApiKey || ''
  form.value.apiKeyMasked = !!s.apiKeyMasked
  form.value.modelId = s.modelId || ''
  form.value.prompt = s.prompt || ''
  form.value.promptKey = s.promptKey || ''
  form.value.skill = s.skill || ''
  form.value.skillKey = s.skillKey || ''
  form.value.mcp = s.mcp || ''
  form.value.mcpKey = s.mcpKey || ''
  form.value.toolsText = Array.isArray(s.tools) ? s.tools.join(', ') : ''
  form.value.inputKeysText = s.inputKeys || ''
  form.value.outputKey = s.outputKey || 'agent_result'
  form.value.permissionTagsText = (def.permissionTags || []).join(',')
}

async function loadEditNode() {
  if (!props.editNode?.nodeId) return
  loading.value = true
  testOutput.value = null
  try {
    const def = await getAgentDefinition(props.editNode.nodeId)
    applyDefinition(def)
  } catch (e) {
    applyDefinition(props.editNode)
    ElMessage.warning(e.response?.data?.error || e.message || 'agent 详情加载失败，部分字段可能不完整')
  } finally {
    loading.value = false
  }
}

watch(visible, async (v) => {
  if (!v) return
  testOutput.value = null
  if (props.editNode) {
    await loadEditNode()
  } else {
    form.value = defaultForm()
    form.value.nodeId = `agent:custom_${Date.now()}`
  }
})

// API Key 显示：掩码态下留空（避免把掩码串当真实 key 回写）；非掩码态显示真实值
const apiKeyDisplay = computed(() => (form.value.apiKeyMasked ? '' : form.value.modelApiKey))

function onApiKeyInput(val) {
  if (form.value.apiKeyMasked && (val === '' || val == null)) return // 保持原掩码值
  form.value.modelApiKey = val ?? ''
  form.value.apiKeyMasked = false
}

function parseKeys(text) {
  return (text || '').split(',').map(s => s.trim()).filter(Boolean)
}

function buildBody() {
  return {
    nodeId: form.value.nodeId,
    displayName: form.value.displayName,
    description: form.value.description,
    modelBaseUrl: form.value.modelBaseUrl || null,
    modelApiKey: form.value.modelApiKey || null,
    apiKeyMasked: form.value.apiKeyMasked,
    modelId: form.value.modelId,
    prompt: form.value.prompt || null,
    promptKey: form.value.promptKey || null,
    skill: form.value.skill || null,
    skillKey: form.value.skillKey || null,
    mcp: form.value.mcp || null,
    mcpKey: form.value.mcpKey || null,
    tools: parseKeys(form.value.toolsText),
    inputKeys: form.value.inputKeysText,
    outputKey: form.value.outputKey,
    permissionTags: parseKeys(form.value.permissionTagsText),
    version: '1.0.0',
    operator: 'designer'
  }
}

async function onValidate() {
  try {
    await validateAgentNode(buildBody())
    ElMessage.success(t('agentEditor.validateOk'))
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.message)
  }
}

async function onTestRun() {
  testing.value = true
  testOutput.value = null
  try {
    let mockState = {}
    try {
      mockState = JSON.parse(form.value.mockStateJson || '{}')
    } catch {
      ElMessage.error(t('agentEditor.mockError'))
      return
    }
    const result = await testRunAgentDraft({ ...buildBody(), mockState })
    testOutput.value = result.output
    ElMessage.success(t('agentEditor.testOk'))
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.message)
  } finally {
    testing.value = false
  }
}

async function onSubmit() {
  if (!form.value.nodeId.startsWith('agent:')) {
    ElMessage.error(t('agentEditor.nodeIdError'))
    return
  }
  saving.value = true
  try {
    await validateAgentNode(buildBody())
    const body = buildBody()
    const result = props.editNode
      ? await updateAgentNode(form.value.nodeId, body)
      : await createAgentNode(body)
    ElMessage.success(props.editNode ? t('agentEditor.updateOk') : t('agentEditor.createOk'))
    emit('created', result)
    visible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="props.editNode ? t('agentEditor.editTitle') : t('agentEditor.title')" width="720px" destroy-on-close>
    <div v-loading="loading">
    <el-form label-width="120px" size="small">
      <el-alert :title="t('propertyPanel.genericAgentNote')" type="info" :closable="false" style="margin-bottom: 12px;" />
      <el-form-item :label="t('agentEditor.nodeId')" required>
        <el-input v-model="form.nodeId" :placeholder="t('agentEditor.nodeIdPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('agentEditor.displayName')" required>
        <el-input v-model="form.displayName" :placeholder="t('agentEditor.displayNamePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('agentEditor.description')">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.model') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.modelBaseUrl')">
        <el-input v-model="form.modelBaseUrl" placeholder="https://api.example.com/v1" />
      </el-form-item>
      <el-form-item :label="apiKeyDisplay === '' && form.apiKeyMasked ? t('propertyPanel.agentSpec.modelApiKeyMasked') : t('propertyPanel.agentSpec.modelApiKey')">
        <el-input
          type="password"
          show-password
          v-model="apiKeyDisplay"
          @update:model-value="onApiKeyInput"
          :placeholder="form.apiKeyMasked ? '******' : 'sk-...'"
        />
        <span v-if="form.apiKeyMasked" class="hint" style="display:block; margin-top:4px;">
          {{ t('propertyPanel.agentSpec.modelApiKeyMaskedHint') }}
        </span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.modelId')" required>
        <el-input v-model="form.modelId" placeholder="gpt-4o / qwen-max / ..." />
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.prompt') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.prompt')">
        <el-input v-model="form.prompt" type="textarea" :rows="4" placeholder="You are a helpful assistant..." />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.promptKey')">
        <el-input v-model="form.promptKey" placeholder="prompts:consult_v2" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.promptHint') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.skill') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.skill')">
        <el-input v-model="form.skill" placeholder="skill:tax_calc" />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.skillKey')">
        <el-input v-model="form.skillKey" placeholder="skills:tax_calc" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.skillHint') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.mcp') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.mcp')">
        <el-input v-model="form.mcp" placeholder="mcp:filesystem" />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.mcpKey')">
        <el-input v-model="form.mcpKey" placeholder="mcps:filesystem" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.mcpHint') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.advanced') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.tools')">
        <el-input v-model="form.toolsText" placeholder="search, calculator, ..." />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.toolsHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.inputKeys')">
        <el-input v-model="form.inputKeysText" placeholder="user_query, context" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.inputKeysHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.outputKey')">
        <el-input v-model="form.outputKey" placeholder="agent_result" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.outputKeyHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('agentEditor.permissionTags')">
        <el-input v-model="form.permissionTagsText" placeholder="public, cs" />
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.test') }}</el-divider>
      <el-form-item :label="t('agentEditor.mockState')">
        <el-input v-model="form.mockStateJson" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item v-if="testOutput" :label="t('agentEditor.testOutput')">
        <el-input :model-value="JSON.stringify(testOutput, null, 2)" type="textarea" :rows="4" readonly />
      </el-form-item>
    </el-form>
    </div>
    <template #footer>
      <el-button v-if="perm.can(MENU.AGENT_NODE_TEST)" @click="onValidate">{{ t('agentEditor.validate') }}</el-button>
      <el-button v-if="perm.can(MENU.AGENT_NODE_TEST)" :loading="testing" @click="onTestRun">{{ t('agentEditor.testRun') }}</el-button>
      <el-button v-if="perm.can(MENU.AGENT_NODE_CREATE)" type="primary" :loading="saving" @click="onSubmit">
        {{ props.editNode ? t('agentEditor.update') : t('agentEditor.create') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { font-size: 12px; color: var(--agd-color-text-secondary, #909399); margin-top: 4px; }
code { background: var(--agd-color-bg-muted, #f5f7fa); padding: 0 4px; border-radius: 2px; }
</style>
