<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { buildSideBySideLineDiff, countLineDiffStats } from '../../utils/jsonLineDiff'
import { useI18n } from '../../i18n'

const props = defineProps({
  leftLabel: { type: String, default: '' },
  rightLabel: { type: String, default: '' },
  leftText: { type: String, default: '' },
  rightText: { type: String, default: '' }
})

const { t } = useI18n()

const leftBodyRef = ref(null)
const rightBodyRef = ref(null)
let syncing = false

const diffRows = computed(() => buildSideBySideLineDiff(props.leftText, props.rightText))
const stats = computed(() => countLineDiffStats(diffRows.value))
const hasDiff = computed(() => stats.value.added + stats.value.removed + stats.value.modified > 0)

function syncScroll(source, target) {
  if (syncing || !source || !target) return
  syncing = true
  target.scrollTop = source.scrollTop
  requestAnimationFrame(() => { syncing = false })
}

function onLeftScroll() {
  syncScroll(leftBodyRef.value, rightBodyRef.value)
}

function onRightScroll() {
  syncScroll(rightBodyRef.value, leftBodyRef.value)
}

onMounted(() => {
  leftBodyRef.value?.addEventListener('scroll', onLeftScroll)
  rightBodyRef.value?.addEventListener('scroll', onRightScroll)
})

onBeforeUnmount(() => {
  leftBodyRef.value?.removeEventListener('scroll', onLeftScroll)
  rightBodyRef.value?.removeEventListener('scroll', onRightScroll)
})

watch(() => [props.leftText, props.rightText], () => {
  if (leftBodyRef.value) leftBodyRef.value.scrollTop = 0
  if (rightBodyRef.value) rightBodyRef.value.scrollTop = 0
})
</script>

<template>
  <div class="json-line-diff">
    <div v-if="hasDiff" class="diff-stats">
      <el-tag v-if="stats.modified" type="warning" size="small">~{{ stats.modified }}</el-tag>
      <el-tag v-if="stats.added" type="success" size="small">+{{ stats.added }}</el-tag>
      <el-tag v-if="stats.removed" type="danger" size="small">-{{ stats.removed }}</el-tag>
    </div>
    <div class="diff-panes">
      <section class="diff-pane">
        <div class="pane-label">{{ leftLabel || t('version.baseLabel') }}</div>
        <div ref="leftBodyRef" class="pane-body">
          <div
            v-for="(row, idx) in diffRows"
            :key="'l-' + idx"
            class="diff-line"
            :class="[`diff-line--${row.type}`, { 'diff-line--empty': row.leftEmpty }]"
          >
            <span class="line-gutter">{{ row.leftNum ?? '' }}</span>
            <code class="line-code">{{ row.left }}</code>
          </div>
        </div>
      </section>
      <section class="diff-pane">
        <div class="pane-label">{{ rightLabel || t('version.targetLabel') }}</div>
        <div ref="rightBodyRef" class="pane-body">
          <div
            v-for="(row, idx) in diffRows"
            :key="'r-' + idx"
            class="diff-line"
            :class="[`diff-line--${row.type}`, { 'diff-line--empty': row.rightEmpty }]"
          >
            <span class="line-gutter">{{ row.rightNum ?? '' }}</span>
            <code class="line-code">{{ row.right }}</code>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.json-line-diff {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.diff-stats {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
  flex-shrink: 0;
}
.diff-panes {
  display: flex;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 4px;
  overflow: hidden;
}
.diff-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--agd-color-border, #e4e7ed);
}
.diff-pane:last-child {
  border-right: none;
}
.pane-label {
  flex-shrink: 0;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--agd-color-text-secondary, #606266);
  background: var(--agd-color-bg, #fff);
  border-bottom: 1px solid var(--agd-color-border, #e4e7ed);
}
.pane-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--agd-color-bg-muted, #fafafa);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  line-height: 1.5;
}
.diff-line {
  display: flex;
  width: max-content;
  min-width: 100%;
  min-height: 1.5em;
}
.diff-line--empty .line-code {
  min-height: 1.5em;
}
.line-gutter {
  flex: 0 0 40px;
  position: sticky;
  left: 0;
  z-index: 1;
  padding: 0 6px;
  text-align: right;
  color: var(--agd-color-text-secondary, #909399);
  background: var(--agd-color-bg-gutter, #f0f2f5);
  border-right: 1px solid var(--agd-color-border, #e4e7ed);
  user-select: none;
}
.line-code {
  flex: 1;
  padding: 0 10px;
  white-space: pre;
}
.diff-line--unchanged .line-code {
  background: transparent;
}
.diff-line--modified .line-code {
  background: #fff8e6;
}
.diff-line--removed .line-code {
  background: #ffeef0;
}
.diff-line--added .line-code {
  background: #e6ffec;
}
</style>
