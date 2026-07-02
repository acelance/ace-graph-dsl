<script setup>
import { computed } from 'vue'
import { hasStructuralDiff, formatDefinitionJson } from '../../utils/graphDiff'
import { useI18n } from '../../i18n'
import JsonLineDiffView from './JsonLineDiffView.vue'

const props = defineProps({
  diff: { type: Object, default: null },
  baseDef: { type: Object, default: null },
  targetDef: { type: Object, default: null },
  activeTab: { type: String, default: 'structure' }
})

const emit = defineEmits(['update:activeTab'])

const { t } = useI18n()

const tab = computed({
  get: () => props.activeTab,
  set: (v) => emit('update:activeTab', v)
})

const hasDiff = computed(() => hasStructuralDiff(props.diff))

const baseJson = computed(() => props.baseDef ? formatDefinitionJson(props.baseDef) : '')
const targetJson = computed(() => props.targetDef ? formatDefinitionJson(props.targetDef) : '')

function sectionSummary(section) {
  if (!section) return { added: 0, removed: 0, modified: 0 }
  return {
    added: section.added?.length || 0,
    removed: section.removed?.length || 0,
    modified: section.modified?.length || 0
  }
}

const nodeSummary = computed(() => sectionSummary(props.diff?.nodes))
const edgeSummary = computed(() => sectionSummary(props.diff?.edges))
</script>

<template>
  <div class="version-diff-panel">
    <el-tabs v-model="tab">
      <el-tab-pane :label="t('version.tabStructure')" name="structure">
        <div v-if="!diff" class="empty-hint">{{ t('version.selectVersion') }}</div>
        <div v-else-if="!hasDiff" class="empty-hint ok">{{ t('version.noDiff') }}</div>
        <div v-else class="structure-diff">
          <section class="diff-section">
            <h4>{{ t('version.nodes') }}</h4>
            <div class="summary-tags">
              <el-tag v-if="nodeSummary.added" type="success" size="small">+{{ nodeSummary.added }}</el-tag>
              <el-tag v-if="nodeSummary.removed" type="danger" size="small">-{{ nodeSummary.removed }}</el-tag>
              <el-tag v-if="nodeSummary.modified" type="warning" size="small">~{{ nodeSummary.modified }}</el-tag>
            </div>
            <ul class="diff-list">
              <li v-for="item in diff.nodes.added" :key="'na-' + item.key" class="added">
                [{{ t('version.added') }}] {{ item.key }}
              </li>
              <li v-for="item in diff.nodes.removed" :key="'nr-' + item.key" class="removed">
                [{{ t('version.removed') }}] {{ item.key }}
              </li>
              <li v-for="item in diff.nodes.modified" :key="'nm-' + item.key" class="modified">
                [{{ t('version.modified') }}] {{ item.key }}
              </li>
            </ul>
          </section>

          <section class="diff-section">
            <h4>{{ t('version.edges') }}</h4>
            <div class="summary-tags">
              <el-tag v-if="edgeSummary.added" type="success" size="small">+{{ edgeSummary.added }}</el-tag>
              <el-tag v-if="edgeSummary.removed" type="danger" size="small">-{{ edgeSummary.removed }}</el-tag>
              <el-tag v-if="edgeSummary.modified" type="warning" size="small">~{{ edgeSummary.modified }}</el-tag>
            </div>
            <ul class="diff-list">
              <li v-for="item in diff.edges.added" :key="'ea-' + item.key" class="added">
                [{{ t('version.added') }}] {{ item.key }}
              </li>
              <li v-for="item in diff.edges.removed" :key="'er-' + item.key" class="removed">
                [{{ t('version.removed') }}] {{ item.key }}
              </li>
              <li v-for="item in diff.edges.modified" :key="'em-' + item.key" class="modified">
                [{{ t('version.modified') }}] {{ item.key }}
              </li>
            </ul>
          </section>

          <section v-if="diff.compile?.length" class="diff-section">
            <h4>{{ t('version.compile') }}</h4>
            <ul class="diff-list">
              <li v-for="item in diff.compile" :key="'c-' + item.key" class="modified">
                {{ item.key }}: {{ JSON.stringify(item.before) }} → {{ JSON.stringify(item.after) }}
              </li>
            </ul>
          </section>

          <section v-if="diff.meta?.length" class="diff-section">
            <h4>{{ t('version.meta') }}</h4>
            <ul class="diff-list">
              <li v-for="item in diff.meta" :key="'m-' + item.key" class="modified">
                {{ item.key }}: {{ JSON.stringify(item.before) }} → {{ JSON.stringify(item.after) }}
              </li>
            </ul>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="t('version.tabJson')" name="json">
        <div v-if="!baseDef || !targetDef" class="empty-hint">{{ t('version.selectVersion') }}</div>
        <JsonLineDiffView
          v-else
          class="json-diff-view"
          :left-text="baseJson"
          :right-text="targetJson"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.version-diff-panel {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.version-diff-panel :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.version-diff-panel :deep(.el-tabs__header) {
  flex-shrink: 0;
  margin-bottom: 8px;
}
.version-diff-panel :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
.version-diff-panel :deep(.el-tab-pane) {
  height: 100%;
}
.empty-hint {
  padding: 24px;
  text-align: center;
  color: var(--agd-color-text-secondary, #909399);
}
.empty-hint.ok {
  color: var(--agd-color-success, #67c23a);
}
.structure-diff {
  padding: 0 4px 12px;
  overflow: auto;
  max-height: 100%;
}
.diff-section {
  margin-bottom: 16px;
}
.diff-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--agd-color-text, #303133);
}
.summary-tags {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}
.diff-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.6;
  font-family: ui-monospace, monospace;
}
.diff-list .added { color: var(--agd-color-success, #67c23a); }
.diff-list .removed { color: var(--agd-color-danger, #f56c6c); }
.diff-list .modified { color: var(--agd-color-warning, #e6a23c); }
.json-diff-view {
  height: 100%;
}
</style>
