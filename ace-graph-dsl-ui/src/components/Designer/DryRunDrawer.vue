<script setup>
/**
 * 试运行抽屉：展示节点轨迹；若含 SAA 子步骤保留键则渲染子步骤树（M4，不依赖 SSE 扩展）。
 */
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { dryRunGraph } from '../../api/graph'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'
import { extractSaaSubSteps, SAA_SUB_STEPS_KEY, SAA_SUB_STEPS_META_KEY } from '../../utils/saaSubSteps'

const props = defineProps({
  graphId: { type: String, required: true }
})
const visible = defineModel('visible', { type: Boolean, default: false })
const editor = useGraphEditorStore()
const { t } = useI18n()

const mockStateJson = ref('{}')
const running = ref(false)
const trace = ref([])
const finalState = ref(null)
const errorMsg = ref('')

function stateWithoutSubSteps(state) {
  if (!state || typeof state !== 'object') return state
  const copy = { ...state }
  delete copy[SAA_SUB_STEPS_KEY]
  delete copy[SAA_SUB_STEPS_META_KEY]
  return copy
}

async function onRun() {
  let inputs = {}
  try {
    inputs = JSON.parse(mockStateJson.value || '{}')
  } catch {
    ElMessage.error(t('dryRun.mockError'))
    return
  }
  running.value = true
  errorMsg.value = ''
  trace.value = []
  finalState.value = null
  try {
    const def = editor.buildDefinition()
    const result = await dryRunGraph(props.graphId, def, inputs)
    if (result.error) {
      errorMsg.value = result.error
    } else {
      trace.value = result.trace || []
      finalState.value = result.finalState || {}
    }
  } catch (e) {
    errorMsg.value = e.response?.data?.error || e.message || String(e)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <el-drawer v-model="visible" :title="t('dryRun.title')" size="44%" destroy-on-close>
    <div class="dry-run">
      <el-alert :title="t('dryRun.hint')" type="info" :closable="false" show-icon class="mb" />
      <el-form label-width="96px" size="small">
        <el-form-item :label="t('dryRun.mockState')">
          <el-input v-model="mockStateJson" type="textarea" :rows="5" placeholder="{}" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="running" :icon="VideoPlay" @click="onRun">
            {{ t('dryRun.run') }}
          </el-button>
        </el-form-item>
      </el-form>

      <el-alert v-if="errorMsg" :title="t('dryRun.error')" type="error" :closable="false" show-icon class="mb">
        <pre class="err">{{ errorMsg }}</pre>
      </el-alert>

      <template v-if="trace.length">
        <div class="section-title">{{ t('dryRun.trace') }}</div>
        <el-timeline>
          <el-timeline-item v-for="(step, i) in trace" :key="i" :timestamp="step.node" placement="top">
            <div v-if="extractSaaSubSteps(step.state).length" class="sub-steps">
              <div class="sub-title">{{ t('dryRun.saaSubSteps') }}</div>
              <el-tree
                :data="extractSaaSubSteps(step.state).map((s, idx) => ({
                  label: `#${idx + 1} ${s.name || s.outputKey} · ${s.status}${s.costMs != null ? ' · ' + s.costMs + 'ms' : ''}`,
                  children: [
                    ...(s.outputKey ? [{ label: `outputKey: ${s.outputKey}` }] : []),
                    ...(s.preview ? [{ label: `preview: ${s.preview}` }] : []),
                    ...(s.error ? [{ label: `error: ${s.error}` }] : [])
                  ]
                }))"
                default-expand-all
                :expand-on-click-node="false"
                class="sub-tree"
              />
              <div v-if="step.state && step.state[SAA_SUB_STEPS_META_KEY]" class="sub-meta">
                meta: {{ JSON.stringify(step.state[SAA_SUB_STEPS_META_KEY]) }}
              </div>
            </div>
            <pre class="state">{{ JSON.stringify(stateWithoutSubSteps(step.state), null, 2) }}</pre>
          </el-timeline-item>
        </el-timeline>
        <div class="section-title">{{ t('dryRun.finalState') }}</div>
        <pre class="state final">{{ JSON.stringify(finalState, null, 2) }}</pre>
      </template>
      <el-empty v-else-if="!running && !errorMsg" :description="t('dryRun.empty')" :image-size="60" />
    </div>
  </el-drawer>
</template>

<style scoped>
.dry-run { padding: 4px 2px; }
.mb { margin-bottom: 12px; }
.section-title { font-weight: 600; margin: 14px 0 8px; color: var(--agd-color-text, #303133); }
.sub-steps {
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  background: var(--el-fill-color-blank, #fff);
}
.sub-title { font-size: 12px; font-weight: 600; margin-bottom: 6px; color: var(--el-color-primary); }
.sub-tree { background: transparent; }
.sub-meta { font-size: 11px; color: var(--el-text-color-secondary); margin-top: 4px; }
.state {
  background: var(--agd-color-bg-muted, #f5f7fa);
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.5;
  overflow: auto;
  max-height: 240px;
  margin: 0;
}
.state.final { max-height: 200px; }
.err { white-space: pre-wrap; margin: 0; font-size: 12px; }
</style>
