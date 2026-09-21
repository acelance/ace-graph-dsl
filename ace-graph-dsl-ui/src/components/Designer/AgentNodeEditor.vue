<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createAgentNode, updateAgentNode, validateAgentNode, testRunAgentDraft, getAgentDefinition
} from '../../api/graph'
import { loadStreamKindOptions } from '../../utils/streamKinds'
import { loadBizParamInterpreterOptions } from '../../utils/bizParamInterpreters'
import {
  loadAgentResource,
  buildMcpTreeData,
  buildSkillTreeData,
  mcpCheckedIdsFromSpec,
  mcpBindingFromChecked,
  skillCheckedIdsFromSpec,
  skillKeysFromChecked
} from '../../utils/agentResourceCatalog'
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
const streamKindOptions = ref([])
const streamKindsLoading = ref(false)
const bizParamInterpreterOptions = ref([])
const bizParamInterpretersLoading = ref(false)

const defaultForm = () => ({
  nodeId: '',
  displayName: '',
  description: '',
  modelBaseUrl: '',
  modelApiKey: '',
  apiKeyMasked: false,
  modelId: '',
  prompt: '',
  inputKeysText: '',
  outputKey: 'agent_result',
  mediaInputKey: '',
  streamResponseKind: '',
  enablePrompt: false,
  promptKeysText: '',
  enableModel: false,
  modelConfigKey: '',
  enableMcp: false,
  mcpKeysText: '',
  mcpKeys: [],
  mcpToolWhitelistText: '',
  mcpToolWhitelist: {},
  enableSkill: false,
  skillKeysText: '',
  skillKeys: [],
  enableLocalTools: false,
  localToolKeysText: '',
  applyDeepThinking: false,
  enableBizParams: false,
  bizParamInterpreterId: 'string',
  bizParamRaw: '',
  permissionTagsText: 'public',
  mockStateJson: '{"user_query":"hello"}'
})

const form = ref(defaultForm())

function csvOf(arr) {
  return Array.isArray(arr) ? arr.join(', ') : ''
}

function parseKeys(text) {
  return (text || '').split(',').map(s => s.trim()).filter(Boolean)
}

function parseMcpWhitelist(text) {
  const map = {}
  ;(text || '').split(';').map(x => x.trim()).filter(Boolean).forEach(part => {
    const idx = part.indexOf(':')
    if (idx < 0) return
    const server = part.slice(0, idx).trim()
    const tools = part.slice(idx + 1).split('|').map(x => x.trim()).filter(Boolean)
    if (server) map[server] = tools
  })
  return map
}

function formatMcpWhitelist(wl) {
  if (!wl || typeof wl !== 'object') return ''
  return Object.entries(wl).map(([k, v]) => `${k}:${(v || []).join('|')}`).join('; ')
}

function copyWhitelist(wl) {
  if (!wl || typeof wl !== 'object') return {}
  const next = {}
  for (const [server, tools] of Object.entries(wl)) {
    next[server] = Array.isArray(tools) ? [...tools] : []
  }
  return next
}

const mcpCatalog = ref({ items: [], error: null, empty: false })
const skillCatalog = ref({ items: [], error: null, empty: false })
const mcpTreeRef = ref(null)
const skillTreeRef = ref(null)
const mcpTreeData = computed(() => buildMcpTreeData(mcpCatalog.value.items))
const skillTreeData = computed(() => buildSkillTreeData(skillCatalog.value.items))
const mcpHasCatalog = computed(() => mcpCatalog.value.items.length > 0 && !mcpCatalog.value.error)
const skillHasCatalog = computed(() => skillCatalog.value.items.length > 0 && !skillCatalog.value.error)

async function loadEditorCatalogs() {
  const [mcp, skills] = await Promise.all([
    loadAgentResource('mcp'),
    loadAgentResource('skills')
  ])
  mcpCatalog.value = mcp
  skillCatalog.value = skills
}

async function syncMcpTreeChecks() {
  if (!mcpHasCatalog.value || !form.value.enableMcp) return
  await nextTick()
  const ids = mcpCheckedIdsFromSpec({
    mcpKeys: form.value.mcpKeys,
    mcpToolWhitelist: form.value.mcpToolWhitelist
  }, mcpTreeData.value)
  mcpTreeRef.value?.setCheckedKeys?.(ids)
}

async function syncSkillTreeChecks() {
  if (!skillHasCatalog.value || !form.value.enableSkill) return
  await nextTick()
  const ids = skillCheckedIdsFromSpec(form.value.skillKeys, skillTreeData.value)
  skillTreeRef.value?.setCheckedKeys?.(ids)
}

function onEditorMcpCheck() {
  const binding = mcpBindingFromChecked(mcpTreeRef.value, mcpTreeData.value)
  form.value.mcpKeys = binding.mcpKeys
  form.value.mcpToolWhitelist = binding.mcpToolWhitelist
}

function onEditorSkillCheck() {
  form.value.skillKeys = skillKeysFromChecked(skillTreeRef.value)
}

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
  form.value.inputKeysText = s.inputKeys || ''
  form.value.outputKey = s.outputKey || 'agent_result'
  form.value.mediaInputKey = s.mediaInputKey || ''
  form.value.streamResponseKind = s.streamResponseKind || ''
  form.value.enablePrompt = !!s.enablePrompt
  form.value.promptKeysText = csvOf(s.promptKeys)
  form.value.enableModel = !!s.enableModel
  form.value.modelConfigKey = s.modelConfigKey || ''
  form.value.enableMcp = !!s.enableMcp
  form.value.mcpKeysText = csvOf(s.mcpKeys)
  form.value.mcpKeys = Array.isArray(s.mcpKeys) ? [...s.mcpKeys] : []
  form.value.mcpToolWhitelistText = formatMcpWhitelist(s.mcpToolWhitelist)
  form.value.mcpToolWhitelist = copyWhitelist(s.mcpToolWhitelist)
  form.value.enableSkill = !!s.enableSkill
  form.value.skillKeysText = csvOf(s.skillKeys)
  form.value.skillKeys = Array.isArray(s.skillKeys) ? [...s.skillKeys] : []
  form.value.enableLocalTools = !!s.enableLocalTools
  form.value.localToolKeysText = csvOf(s.localToolKeys)
  form.value.applyDeepThinking = !!s.applyDeepThinking
  form.value.enableBizParams = !!s.enableBizParams
  form.value.bizParamInterpreterId = s.bizParamInterpreterId || 'string'
  form.value.bizParamRaw = s.bizParamRaw || ''
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

async function ensureStreamKinds() {
  streamKindsLoading.value = true
  try {
    streamKindOptions.value = await loadStreamKindOptions()
  } finally {
    streamKindsLoading.value = false
  }
}

async function ensureBizParamInterpreters() {
  bizParamInterpretersLoading.value = true
  try {
    bizParamInterpreterOptions.value = await loadBizParamInterpreterOptions()
  } finally {
    bizParamInterpretersLoading.value = false
  }
}

watch(visible, async (v) => {
  if (!v) return
  testOutput.value = null
  const catalogPromise = loadEditorCatalogs()
  await Promise.all([ensureStreamKinds(), ensureBizParamInterpreters(), catalogPromise])
  if (props.editNode) {
    await loadEditNode()
  } else {
    form.value = defaultForm()
    form.value.nodeId = `agent:custom_${Date.now()}`
  }
  await syncMcpTreeChecks()
  await syncSkillTreeChecks()
})

watch(
  () => [visible.value, form.value.enableMcp, mcpHasCatalog.value, form.value.nodeId],
  async ([open, enabled, hasCatalog]) => {
    if (!open || !enabled || !hasCatalog) return
    await syncMcpTreeChecks()
  }
)

watch(
  () => [visible.value, form.value.enableSkill, skillHasCatalog.value, form.value.nodeId],
  async ([open, enabled, hasCatalog]) => {
    if (!open || !enabled || !hasCatalog) return
    await syncSkillTreeChecks()
  }
)

const apiKeyDisplay = computed(() => (form.value.apiKeyMasked ? '' : form.value.modelApiKey))

function onApiKeyInput(val) {
  if (form.value.apiKeyMasked && (val === '' || val == null)) return
  form.value.modelApiKey = val ?? ''
  form.value.apiKeyMasked = false
}

function buildBody() {
  const promptKeys = parseKeys(form.value.promptKeysText)
  const mcpKeys = mcpHasCatalog.value ? [...(form.value.mcpKeys || [])] : parseKeys(form.value.mcpKeysText)
  const skillKeys = skillHasCatalog.value ? [...(form.value.skillKeys || [])] : parseKeys(form.value.skillKeysText)
  const localToolKeys = parseKeys(form.value.localToolKeysText)
  const mcpToolWhitelist = mcpHasCatalog.value
    ? copyWhitelist(form.value.mcpToolWhitelist)
    : parseMcpWhitelist(form.value.mcpToolWhitelistText)
  const spec = {
    modelBaseUrl: form.value.modelBaseUrl || null,
    modelApiKey: form.value.modelApiKey || null,
    apiKeyMasked: form.value.apiKeyMasked,
    modelId: form.value.modelId,
    prompt: form.value.prompt || null,
    inputKeys: form.value.inputKeysText,
    outputKey: form.value.outputKey,
    mediaInputKey: form.value.mediaInputKey || null,
    streamResponseKind: form.value.streamResponseKind || null,
    enablePrompt: form.value.enablePrompt,
    promptKeys,
    enableModel: form.value.enableModel,
    modelConfigKey: form.value.modelConfigKey || null,
    enableMcp: form.value.enableMcp,
    mcpKeys,
    mcpToolWhitelist,
    enableSkill: form.value.enableSkill,
    skillKeys,
    enableLocalTools: form.value.enableLocalTools,
    localToolKeys,
    applyDeepThinking: form.value.applyDeepThinking,
    enableBizParams: form.value.enableBizParams,
    bizParamInterpreterId: form.value.enableBizParams
      ? (form.value.bizParamInterpreterId || 'string')
      : null,
    bizParamRaw: form.value.enableBizParams ? (form.value.bizParamRaw || null) : null
  }
  return {
    nodeId: form.value.nodeId,
    displayName: form.value.displayName,
    description: form.value.description,
    spec,
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
  <el-dialog v-model="visible" :title="props.editNode ? t('agentEditor.editTitle') : t('agentEditor.title')" width="760px" destroy-on-close>
    <div v-loading="loading">
    <el-form label-width="130px" size="small">
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

      <el-divider content-position="left">{{ t('propertyPanel.agentSpec.streamResponseKind') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.streamResponseKind')">
        <el-select v-model="form.streamResponseKind" clearable filterable :loading="streamKindsLoading" style="width:100%;">
          <el-option
            v-for="opt in streamKindOptions"
            :key="opt.code"
            :label="`${opt.label} (${opt.code})`"
            :value="opt.code"
          />
        </el-select>
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.streamResponseKindHint') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('propertyPanel.agentSpec.deepThinking') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.applyDeepThinking')">
        <el-switch v-model="form.applyDeepThinking" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.applyDeepThinkingHint') }}</span>
      </el-form-item>

      <el-divider content-position="left">{{ t('propertyPanel.agentSpec.bizParams') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.enableBizParams')">
        <el-switch v-model="form.enableBizParams" @change="(on) => { if (on && !form.bizParamInterpreterId) form.bizParamInterpreterId = 'string' }" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.enableBizParamsHint') }}</span>
      </el-form-item>
      <template v-if="form.enableBizParams">
        <el-form-item :label="t('propertyPanel.agentSpec.bizParamInterpreterId')" required>
          <el-select v-model="form.bizParamInterpreterId" filterable :loading="bizParamInterpretersLoading" style="width:100%;">
            <el-option
              v-for="opt in bizParamInterpreterOptions"
              :key="opt.id"
              :label="`${opt.displayName} (${opt.id})`"
              :value="opt.id"
            />
          </el-select>
          <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.bizParamInterpreterIdHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('propertyPanel.agentSpec.bizParamRaw')">
          <el-input v-model="form.bizParamRaw" type="textarea" :rows="4" :placeholder="t('propertyPanel.agentSpec.bizParamRawPlaceholder')" />
          <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.bizParamRawHint') }}</span>
        </el-form-item>
      </template>

      <el-divider content-position="left">{{ t('propertyPanel.agentSpec.resources') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.enablePrompt')">
        <el-switch v-model="form.enablePrompt" />
      </el-form-item>
      <el-form-item v-if="form.enablePrompt" :label="t('propertyPanel.agentSpec.promptKeys')">
        <el-input v-model="form.promptKeysText" placeholder="prompts:a, prompts:b" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.promptKeysHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.enableModel')">
        <el-switch v-model="form.enableModel" />
      </el-form-item>
      <el-form-item v-if="form.enableModel" :label="t('propertyPanel.agentSpec.modelConfigKey')">
        <el-input v-model="form.modelConfigKey" placeholder="models:default" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.modelConfigKeyHint') }}</span>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.enableMcp')">
        <el-switch v-model="form.enableMcp" />
      </el-form-item>
      <template v-if="form.enableMcp">
        <el-form-item :label="mcpHasCatalog ? t('propertyPanel.agentSpec.mcpTree') : t('propertyPanel.agentSpec.mcpKeys')">
          <div v-if="mcpHasCatalog" class="mcp-tree-wrap">
            <el-tree
              ref="mcpTreeRef"
              :data="mcpTreeData"
              node-key="id"
              show-checkbox
              default-expand-all
              :props="{ label: 'label', children: 'children' }"
              @check="onEditorMcpCheck"
            />
            <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.mcpTreeHint') }}</span>
          </div>
          <div v-else>
            <span v-if="mcpCatalog.error" class="hint" style="display:block; margin-bottom:4px;">{{ t('propertyPanel.agentSpec.catalogLoadFailed') }}</span>
            <span v-else-if="mcpCatalog.empty" class="hint" style="display:block; margin-bottom:4px;">{{ t('propertyPanel.agentSpec.catalogEmpty') }}</span>
            <el-input v-model="form.mcpKeysText" placeholder="mcp:crm, mcp:erp" />
            <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.mcpKeysHint') }}</span>
          </div>
        </el-form-item>
        <el-form-item v-if="!mcpHasCatalog" :label="t('propertyPanel.agentSpec.mcpToolWhitelist')">
          <el-input v-model="form.mcpToolWhitelistText" placeholder="crm:query|list" />
          <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.mcpToolWhitelistHint') }}</span>
        </el-form-item>
      </template>
      <el-form-item :label="t('propertyPanel.agentSpec.enableSkill')">
        <el-switch v-model="form.enableSkill" />
      </el-form-item>
      <el-form-item v-if="form.enableSkill" :label="skillHasCatalog ? t('propertyPanel.agentSpec.skillTree') : t('propertyPanel.agentSpec.skillKeys')">
        <div v-if="skillHasCatalog" class="mcp-tree-wrap">
          <el-tree
            ref="skillTreeRef"
            :data="skillTreeData"
            node-key="id"
            show-checkbox
            default-expand-all
            :props="{ label: 'label', children: 'children' }"
            @check="onEditorSkillCheck"
          />
          <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.skillTreeHint') }}</span>
        </div>
        <div v-else>
          <span v-if="skillCatalog.error" class="hint" style="display:block; margin-bottom:4px;">{{ t('propertyPanel.agentSpec.catalogLoadFailed') }}</span>
          <span v-else-if="skillCatalog.empty" class="hint" style="display:block; margin-bottom:4px;">{{ t('propertyPanel.agentSpec.catalogEmpty') }}</span>
          <el-input v-model="form.skillKeysText" placeholder="skills:tax" />
          <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.skillKeysHint') }}</span>
        </div>
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.enableLocalTools')">
        <el-switch v-model="form.enableLocalTools" />
      </el-form-item>
      <el-form-item v-if="form.enableLocalTools" :label="t('propertyPanel.agentSpec.localToolKeys')">
        <el-input v-model="form.localToolKeysText" placeholder="tools:search" />
      </el-form-item>

      <el-divider content-position="left">{{ t('agentEditor.advanced') }}</el-divider>
      <el-form-item :label="t('propertyPanel.agentSpec.inputKeys')">
        <el-input v-model="form.inputKeysText" placeholder="user_query, context" />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.outputKey')">
        <el-input v-model="form.outputKey" placeholder="agent_result" />
      </el-form-item>
      <el-form-item :label="t('propertyPanel.agentSpec.mediaInputKey')">
        <el-input v-model="form.mediaInputKey" placeholder="multimodal_refs" />
        <span class="hint" style="display:block; margin-top:4px;">{{ t('propertyPanel.agentSpec.mediaInputKeyHint') }}</span>
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
.mcp-tree-wrap {
  width: 100%;
  max-height: 280px;
  overflow: auto;
  border: 1px solid var(--agd-color-border, #dcdfe6);
  border-radius: 4px;
  padding: 6px 8px;
}
</style>
