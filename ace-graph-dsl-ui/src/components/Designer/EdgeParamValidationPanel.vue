<script setup>
import { computed } from 'vue'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'

const editor = useGraphEditorStore()
const { t } = useI18n()

const issues = computed(() => editor.edgeParamIssues)
const hasIssues = computed(() => issues.value.length > 0)

function formatIssue(issue) {
  return t('edgeValidation.issue', {
    from: issue.from,
    to: issue.to,
    target: issue.targetNodeId,
    keys: issue.missingKeys.join(', ')
  })
}
</script>

<template>
  <div v-if="hasIssues" class="edge-validation-panel">
    <div class="panel-title">{{ t('edgeValidation.title') }}</div>
    <div class="issue-scroll">
      <ul class="issue-list">
        <li v-for="issue in issues" :key="issue.edgeKey" class="issue-item">
          {{ formatIssue(issue) }}
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.edge-validation-panel {
  position: absolute;
  left: 12px;
  bottom: 56px;
  z-index: 6;
  width: min(360px, calc(100% - 24px));
  height: 120px;
  display: flex;
  flex-direction: column;
  border-radius: 8px;
  border: 1px solid rgba(245, 108, 108, 0.55);
  background: rgba(245, 108, 108, 0.42);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 4px 16px rgba(245, 108, 108, 0.25);
  pointer-events: auto;
  overflow: hidden;
}
.panel-title {
  flex-shrink: 0;
  padding: 8px 12px 6px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.15);
}
.issue-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 8px 8px;
}
.issue-scroll::-webkit-scrollbar {
  width: 6px;
}
.issue-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.45);
  border-radius: 3px;
}
.issue-list {
  margin: 0;
  padding: 0 4px 0 18px;
  list-style: disc;
}
.issue-item {
  font-size: 12px;
  line-height: 1.5;
  color: #fff;
  margin-bottom: 4px;
  word-break: break-word;
}
</style>
