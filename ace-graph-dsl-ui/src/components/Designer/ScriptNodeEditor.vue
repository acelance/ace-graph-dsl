<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createScriptNode, updateScriptNode, validateScript, testRunDraft, listScriptEngines, getScriptNodeDefinition } from '../../api/graph'
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
const engines = ref([])

const defaultForm = () => ({
  nodeId: '',
  displayName: '',
  category: 'NORMAL',
  description: '',
  inputKeysText: 'query',
  outputKeysText: 'normalized_query',
  engine: 'aviator',
  scriptBody: "seq.map('normalized_query', state.query)",
  mockStateJson: '{"query":"  hello  "}',
  permissionTagsText: 'public'
})

const form = ref(defaultForm())

function applyDefinition(def) {
  form.value.nodeId = def.nodeId || ''
  form.value.displayName = def.displayName || ''
  form.value.category = def.category || 'NORMAL'
  form.value.description = def.description || ''
  form.value.inputKeysText = (def.inputKeys || []).join(',')
  form.value.outputKeysText = (def.outputKeys || []).join(',')
  form.value.engine = def.engine || 'aviator'
  form.value.scriptBody = def.scriptBody || ''
  form.value.permissionTagsText = (def.permissionTags || []).join(',')
}

async function loadEditNode() {
  if (!props.editNode?.nodeId) return
  loading.value = true
  testOutput.value = null
  try {
    const def = await getScriptNodeDefinition(props.editNode.nodeId)
    applyDefinition(def)
  } catch (e) {
    applyDefinition(props.editNode)
    ElMessage.warning(e.response?.data?.error || e.message || '脚本详情加载失败，部分字段可能不完整')
  } finally {
    loading.value = false
  }
}

watch(visible, async (v) => {
  if (!v) return
  testOutput.value = null
  await fetchEngines()
  if (props.editNode) {
    await loadEditNode()
  } else {
    form.value = defaultForm()
    form.value.nodeId = `script:custom_${Date.now()}`
  }
})

async function fetchEngines() {
  try {
    engines.value = await listScriptEngines()
    if (engines.value.length === 0) {
      engines.value = [{ id: 'aviator', label: 'Aviator 表达式' }]
    }
  } catch {
    engines.value = [{ id: 'aviator', label: 'Aviator 表达式' }]
  }
}

const inputKeys = computed(() => parseKeys(form.value.inputKeysText))
const outputKeys = computed(() => parseKeys(form.value.outputKeysText))

// 当前选中引擎元数据：用于决定脚本编辑器行数与提示文案
const selectedEngine = computed(() =>
  engines.value.find(e => e.id === form.value.engine) || null
)
const scriptRows = computed(() => (selectedEngine.value?.multiLine ? 14 : 3))
const scriptHintText = computed(() => {
  const key = selectedEngine.value?.hintKey
  if (key) {
    const txt = t(key)
    if (txt && txt !== key) return txt
  }
  return t('scriptEditor.scriptHint')
})

function parseKeys(text) {
  return (text || '').split(',').map(s => s.trim()).filter(Boolean)
}

async function onValidate() {
  try {
    await validateScript({ engine: form.value.engine, scriptBody: form.value.scriptBody })
    ElMessage.success(t('scriptEditor.validateOk'))
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
      ElMessage.error(t('scriptEditor.mockError'))
      return
    }
    const result = await testRunDraft({
      engine: form.value.engine,
      scriptBody: form.value.scriptBody,
      inputKeys: inputKeys.value,
      outputKeys: outputKeys.value,
      mockState,
      config: {}
    })
    testOutput.value = result.output
    ElMessage.success(t('scriptEditor.testOk'))
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.message)
  } finally {
    testing.value = false
  }
}

async function onSubmit() {
  if (!form.value.nodeId.startsWith('script:')) {
    ElMessage.error(t('scriptEditor.nodeIdError'))
    return
  }
  saving.value = true
  try {
    await validateScript({ engine: form.value.engine, scriptBody: form.value.scriptBody })
    const body = {
      nodeId: form.value.nodeId,
      displayName: form.value.displayName,
      category: form.value.category,
      description: form.value.description,
      inputKeys: inputKeys.value,
      outputKeys: outputKeys.value,
      engine: form.value.engine,
      scriptBody: form.value.scriptBody,
      permissionTags: parseKeys(form.value.permissionTagsText),
      supportsParallel: false,
      version: '1.0.0',
      operator: 'designer'
    }
    const result = props.editNode
      ? await updateScriptNode(form.value.nodeId, body)
      : await createScriptNode(body)
    ElMessage.success(props.editNode ? t('scriptEditor.updateOk') : t('scriptEditor.createOk'))
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
  <el-dialog v-model="visible" :title="props.editNode ? t('scriptEditor.editTitle') : t('scriptEditor.title')" width="680px" destroy-on-close>
    <div v-loading="loading">
    <el-form label-width="110px" size="small">
      <el-form-item :label="t('scriptEditor.nodeId')" required>
        <el-input v-model="form.nodeId" :placeholder="t('scriptEditor.nodeIdPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.displayName')" required>
        <el-input v-model="form.displayName" :placeholder="t('scriptEditor.displayNamePlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.category')">
        <el-select v-model="form.category" style="width: 100%;">
          <el-option label="NORMAL" value="NORMAL" />
          <el-option label="ROUTER" value="ROUTER" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('scriptEditor.description')">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.inputKeys')">
        <el-input v-model="form.inputKeysText" :placeholder="t('scriptEditor.inputKeysPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.outputKeys')">
        <el-input v-model="form.outputKeysText" :placeholder="t('scriptEditor.outputKeysPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.permissionTags')">
        <el-input v-model="form.permissionTagsText" :placeholder="t('scriptEditor.permissionTagsPlaceholder')" />
      </el-form-item>
      <el-form-item :label="t('scriptEditor.engine')">
        <el-select v-model="form.engine" style="width: 100%;">
          <el-option v-for="e in engines" :key="e.id" :label="e.label" :value="e.id" />
        </el-select>
      </el-form-item>
      <el-form-item :label="t('scriptEditor.scriptBody')" required>
        <el-input v-model="form.scriptBody" type="textarea" :rows="scriptRows" />
        <div class="hint">{{ scriptHintText }}</div>
      </el-form-item>
      <el-form-item :label="t('scriptEditor.mockState')">
        <el-input v-model="form.mockStateJson" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item v-if="testOutput" :label="t('scriptEditor.testOutput')">
        <el-input :model-value="JSON.stringify(testOutput, null, 2)" type="textarea" :rows="3" readonly />
      </el-form-item>
    </el-form>
    </div>
    <template #footer>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_TEST)" @click="onValidate">{{ t('scriptEditor.validate') }}</el-button>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_TEST)" :loading="testing" @click="onTestRun">{{ t('scriptEditor.testRun') }}</el-button>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_CREATE)" type="primary" :loading="saving" @click="onSubmit">
        {{ props.editNode ? t('scriptEditor.update') : t('scriptEditor.create') }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { font-size: 12px; color: var(--agd-color-text-secondary, #909399); margin-top: 4px; }
code { background: var(--agd-color-bg-muted, #f5f7fa); padding: 0 4px; border-radius: 2px; }
</style>
