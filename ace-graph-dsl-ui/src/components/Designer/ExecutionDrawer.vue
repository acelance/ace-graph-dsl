<script setup>
import { ref, computed, onBeforeUnmount, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay, Refresh } from '@element-plus/icons-vue'
import { debugStreamGraph, resumeGraph, getExecutionState } from '../../api/graph'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'

const props = defineProps({
  graphId: { type: String, required: true }
})
const visible = defineModel('visible', { type: Boolean, default: false })
const editor = useGraphEditorStore()
const perm = usePermissionStore()
const { t } = useI18n()

const inputsJson = ref('{}')
const agentCode = ref('')
/** 调试用：逗号分隔的 skill key，写入 ace.graph.dsl.forceSkills */
const forceSkillsText = ref('')
const running = ref(false)
const trace = ref([])
const errorMsg = ref('')
const threadId = ref('')

/** 设计器默认走调试端点（框架标准格式）；无 validate 权限时禁用并提示 */
const useDebugEndpoint = computed(() => perm.can(MENU.GRAPH_VALIDATE))

// HITL 暂停状态
const hitlPaused = ref(false)
const hitlInfo = ref(null)
const resumeUpdatesJson = ref('{}')
const abortController = ref(null)

const hasHitl = computed(() => hitlPaused.value && hitlInfo.value)

watch(visible, (v) => {
  if (v && !useDebugEndpoint.value) {
    ElMessage.warning(t('execution.debugPermissionDenied'))
  }
})

function resetExecution() {
  trace.value = []
  errorMsg.value = ''
  hitlPaused.value = false
  hitlInfo.value = null
  resumeUpdatesJson.value = '{}'
}

function startStream(inputs) {
  const handlers = {
    onEvent: (evt) => {
      if (evt.data && typeof evt.data === 'object') {
        trace.value.push(evt.data)
      }
    },
    onSubgraphInterrupted: (data) => {
      hitlPaused.value = true
      hitlInfo.value = { type: 'subgraph', ...data }
      running.value = false
    },
    onError: (err) => {
      errorMsg.value = err.message || String(err)
      running.value = false
    },
    onComplete: () => {
      running.value = false
      checkTopLevelHitl()
    }
  }
  const opts = { agentCode: agentCode.value || undefined }
  // 设计器必须走 debug/stream，禁止把生产用户协议当调试所见（§9.6.7 / B7）
  if (useDebugEndpoint.value) {
    return debugStreamGraph(props.graphId, inputs, threadId.value, handlers, opts)
  }
  ElMessage.error(t('execution.debugPermissionDenied'))
  running.value = false
  return null
}

async function onRun() {
  if (!useDebugEndpoint.value) {
    ElMessage.error(t('execution.debugPermissionDenied'))
    return
  }
  let inputs = {}
  try {
    inputs = JSON.parse(inputsJson.value || '{}')
  } catch {
    ElMessage.error(t('execution.inputsError'))
    return
  }
  const force = (forceSkillsText.value || '')
    .split(',')
    .map(s => s.trim())
    .filter(Boolean)
  if (force.length) {
    inputs = { ...inputs, 'ace.graph.dsl.forceSkills': force }
  }
  resetExecution()
  running.value = true
  threadId.value = crypto.randomUUID()
  abortController.value = startStream(inputs)
}

async function checkTopLevelHitl() {
  if (!threadId.value) return
  try {
    const state = await getExecutionState(props.graphId, threadId.value)
    if (state.exists && state.next) {
      hitlPaused.value = true
      hitlInfo.value = { type: 'top-level', next: state.next, state: state.state }
    }
  } catch {
    // 忽略 — 执行端点可能未开启
  }
}

async function onResume() {
  let updates = {}
  try {
    updates = JSON.parse(resumeUpdatesJson.value || '{}')
  } catch {
    ElMessage.error(t('execution.updatesError'))
    return
  }

  const subgraphNodeId = hitlInfo.value?.type === 'subgraph' ? hitlInfo.value.parentNodeId : null
  hitlPaused.value = false
  hitlInfo.value = null
  running.value = true

  abortController.value = resumeGraph(props.graphId, threadId.value, updates, subgraphNodeId, {
    onEvent: (evt) => {
      if (evt.data && typeof evt.data === 'object') {
        trace.value.push(evt.data)
      }
    },
    onSubgraphInterrupted: (data) => {
      hitlPaused.value = true
      hitlInfo.value = { type: 'subgraph', ...data }
      running.value = false
    },
    onError: (err) => {
      errorMsg.value = err.message || String(err)
      running.value = false
    },
    onComplete: () => {
      running.value = false
      checkTopLevelHitl()
    }
  })
}

function onStop() {
  abortController.value?.abort()
  running.value = false
}

onBeforeUnmount(() => {
  abortController.value?.abort()
})
</script>

<template>
  <el-drawer v-model="visible" :title="t('execution.title')" size="50%" destroy-on-close>
    <div class="exec-drawer">
      <el-alert :title="t('execution.debugFormatHint')" type="warning" :closable="false" show-icon class="mb" />
      <el-alert :title="t('execution.hint')" type="info" :closable="false" show-icon class="mb" />

      <!-- 输入区 -->
      <el-form label-width="110px" size="small">
        <el-form-item :label="t('execution.agentCode')">
          <el-input v-model="agentCode" :placeholder="t('execution.agentCodePlaceholder')" :disabled="running" />
        </el-form-item>
        <el-form-item :label="t('execution.forceSkills')">
          <el-input
            v-model="forceSkillsText"
            :placeholder="t('execution.forceSkillsPlaceholder')"
            :disabled="running"
          />
          <span class="hint">{{ t('execution.forceSkillsHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('execution.inputs')">
          <el-input v-model="inputsJson" type="textarea" :rows="4" placeholder="{}" :disabled="running" />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="running"
            :icon="VideoPlay"
            @click="onRun"
            :disabled="running || !useDebugEndpoint"
          >
            {{ t('execution.runDebug') }}
          </el-button>
          <el-button v-if="running" type="danger" @click="onStop">
            {{ t('execution.stop') }}
          </el-button>
        </el-form-item>
      </el-form>

      <!-- HITL 暂停区 -->
      <el-alert v-if="hasHitl" :title="t('execution.hitlPaused')" type="warning" :closable="false" show-icon class="mb">
        <template #default>
          <div v-if="hitlInfo.type === 'subgraph'" class="hitl-detail">
            <p>{{ t('execution.subgraphPaused', { parent: hitlInfo.parentNodeId, node: hitlInfo.nodeId }) }}</p>
          </div>
          <div v-else class="hitl-detail">
            <p>{{ t('execution.topLevelPaused', { node: hitlInfo.next }) }}</p>
          </div>
          <pre class="hitl-state">{{ JSON.stringify(hitlInfo.state, null, 2) }}</pre>
          <el-form label-width="96px" size="small" class="resume-form">
            <el-form-item :label="t('execution.resumeUpdates')">
              <el-input v-model="resumeUpdatesJson" type="textarea" :rows="3" placeholder='{"key":"value"}' />
            </el-form-item>
            <el-form-item>
              <el-button type="warning" :icon="Refresh" @click="onResume">
                {{ t('execution.resume') }}
              </el-button>
            </el-form-item>
          </el-form>
        </template>
      </el-alert>

      <!-- 错误区 -->
      <el-alert v-if="errorMsg" :title="t('execution.error')" type="error" :closable="false" show-icon class="mb">
        <pre class="err">{{ errorMsg }}</pre>
      </el-alert>

      <!-- 执行轨迹 -->
      <template v-if="trace.length">
        <div class="section-title">{{ t('execution.trace') }} ({{ trace.length }})</div>
        <el-timeline>
          <el-timeline-item
            v-for="(step, i) in trace"
            :key="i"
            :timestamp="step.node || step.type || `#${i}`"
            placement="top"
          >
            <pre class="state">{{ JSON.stringify(step.state || step, null, 2) }}</pre>
          </el-timeline-item>
        </el-timeline>
      </template>
      <el-empty v-else-if="!running && !errorMsg && !hasHitl" :description="t('execution.empty')" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.hint { display: block; margin-top: 4px; font-size: 12px; color: var(--agd-color-text-secondary, #909399); }
</style>
.exec-drawer { padding: 4px 2px; }
.mb { margin-bottom: 12px; }
.section-title { font-weight: 600; margin: 14px 0 8px; color: var(--agd-color-text, #303133); }
.hitl-detail p { margin: 4px 0; font-size: 13px; }
.hitl-state, .state {
  background: var(--agd-color-bg-muted, #f5f7fa);
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
  max-height: 240px;
  margin: 0;
}
.hitl-state { max-height: 160px; margin-bottom: 8px; }
.resume-form { margin-top: 8px; }
.err { white-space: pre-wrap; margin: 0; font-size: 12px; }
.hint { display: block; margin-top: 4px; font-size: 12px; color: var(--agd-color-text-secondary, #909399); }
</style>
