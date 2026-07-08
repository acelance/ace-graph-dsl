<script setup>
import { computed } from 'vue'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { useI18n } from '../../i18n'

const editor = useGraphEditorStore()
const { t } = useI18n()

const issues = computed(() => editor.topologyIssues)
const hasIssues = computed(() => issues.value.length > 0)
const errorCount = computed(() => issues.value.filter(i => i.level === 'error').length)
const warningCount = computed(() => issues.value.filter(i => i.level === 'warning').length)

function typeLabel(type) {
  return t(`topology.${type}`) || type
}
</script>

<template>
  <div v-if="hasIssues" class="topology-panel" :class="{ 'topology-panel--warn': errorCount === 0 }">
    <div class="panel-title">
      {{ t('topology.title') }}
      <span v-if="errorCount" class="badge badge--error">{{ errorCount }} {{ t('topology.error') }}</span>
      <span v-if="warningCount" class="badge badge--warn">{{ warningCount }} {{ t('topology.warning') }}</span>
    </div>
    <div class="issue-scroll">
      <ul class="issue-list">
        <li
          v-for="(issue, idx) in issues"
          :key="idx"
          class="issue-item"
          :class="issue.level === 'error' ? 'issue-item--error' : 'issue-item--warn'"
        >
          <span class="issue-type">[{{ typeLabel(issue.type) }}]</span>
          {{ issue.message }}
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.topology-panel {
  position: absolute;
  right: 12px;
  bottom: 56px;
  z-index: 6;
  width: min(380px, calc(100% - 24px));
  max-height: 200px;
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
.topology-panel--warn {
  border-color: rgba(230, 162, 60, 0.55);
  background: rgba(230, 162, 60, 0.38);
  box-shadow: 0 4px 16px rgba(230, 162, 60, 0.22);
}
.panel-title {
  flex-shrink: 0;
  padding: 8px 12px 6px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.15);
  display: flex;
  align-items: center;
  gap: 8px;
}
.badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.28);
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
  padding: 0 4px 0 4px;
  list-style: none;
}
.issue-item {
  font-size: 12px;
  line-height: 1.5;
  color: #fff;
  margin-bottom: 4px;
  word-break: break-word;
}
.issue-type {
  font-weight: 600;
  opacity: 0.92;
}
</style>
