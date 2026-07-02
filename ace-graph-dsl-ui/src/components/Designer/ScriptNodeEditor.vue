<script setup>
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { createScriptNode, validateScript, testRunDraft } from '../../api/graph'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'

const perm = usePermissionStore()
const { t } = useI18n()

const visible = defineModel('visible', { type: Boolean, default: false })
const emit = defineEmits(['created'])

const saving = ref(false)
const testing = ref(false)
const testOutput = ref(null)

const form = ref({
  nodeId: '',
  displayName: '',
  category: 'NORMAL',
  description: '',
  inputKeysText: 'query',
  outputKeysText: 'normalized_query',
  engine: 'aviator',
  scriptBody: "seq.map('normalized_query', string.trim(string(state.query)))",
  mockStateJson: '{"query":"  hello  "}',
  permissionTagsText: 'public'
})

watch(visible, (v) => {
  if (v) {
    form.value.nodeId = `script:${form.value.displayName ? form.value.displayName.replace(/\s+/g, '_').toLowerCase() : 'custom_' + Date.now()}`
  }
})

watch(() => form.value.displayName, (name) => {
  if (!form.value.nodeId || form.value.nodeId.startsWith('script:custom_')) {
    const slug = (name || '').trim().replace(/\s+/g, '_').toLowerCase()
    if (slug) form.value.nodeId = `script:${slug}`
  }
})

const inputKeys = computed(() => parseKeys(form.value.inputKeysText))
const outputKeys = computed(() => parseKeys(form.value.outputKeysText))

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
    const created = await createScriptNode({
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
    })
    ElMessage.success(t('scriptEditor.createOk'))
    emit('created', created)
    visible.value = false
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.message)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('scriptEditor.title')" width="680px" destroy-on-close>
    <el-form label-width="110px" size="small">
      <el-form-item label="Node ID" required>
        <el-input v-model="form.nodeId" placeholder="script:my_node" />
      </el-form-item>
      <el-form-item label="显示名" required>
        <el-input v-model="form.displayName" placeholder="入参标准化" />
      </el-form-item>
      <el-form-item label="类别">
        <el-select v-model="form.category" style="width: 100%;">
          <el-option label="NORMAL" value="NORMAL" />
          <el-option label="ROUTER" value="ROUTER" />
        </el-select>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="Input Keys">
        <el-input v-model="form.inputKeysText" placeholder="逗号分隔，如 query,score" />
      </el-form-item>
      <el-form-item label="Output Keys">
        <el-input v-model="form.outputKeysText" placeholder="逗号分隔，如 normalized_query" />
      </el-form-item>
      <el-form-item label="权限标签">
        <el-input v-model="form.permissionTagsText" placeholder="逗号分隔，如 public,cs" />
      </el-form-item>
      <el-form-item label="脚本 (Aviator)" required>
        <el-input v-model="form.scriptBody" type="textarea" :rows="6" font-family="monospace" />
        <div class="hint">可用变量：<code>state</code>（输入）、<code>config</code>（节点配置）；返回 Map 或使用 seq.map(key, value)</div>
      </el-form-item>
      <el-form-item label="Mock State">
        <el-input v-model="form.mockStateJson" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item v-if="testOutput" label="试跑结果">
        <el-input :model-value="JSON.stringify(testOutput, null, 2)" type="textarea" :rows="3" readonly />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_TEST)" @click="onValidate">{{ t('scriptEditor.validate') }}</el-button>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_TEST)" :loading="testing" @click="onTestRun">{{ t('scriptEditor.testRun') }}</el-button>
      <el-button v-if="perm.can(MENU.SCRIPT_NODE_CREATE)" type="primary" :loading="saving" @click="onSubmit">{{ t('scriptEditor.create') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { font-size: 12px; color: var(--agd-color-text-secondary, #909399); margin-top: 4px; }
code { background: var(--agd-color-bg-muted, #f5f7fa); padding: 0 4px; border-radius: 2px; }
</style>
