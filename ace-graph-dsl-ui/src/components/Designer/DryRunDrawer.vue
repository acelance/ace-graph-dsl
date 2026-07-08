<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { dryRunGraph } from '../../api/graph'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'

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
            <pre class="state">{{ JSON.stringify(step.state, null, 2) }}</pre>
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
