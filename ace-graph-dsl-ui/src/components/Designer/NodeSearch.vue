<script setup>
import { ref, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { useI18n } from '../../i18n'

const props = defineProps({
  canvasRef: { type: Object, default: null }
})

const { t } = useI18n()
const keyword = ref('')
const open = ref(false)

const matches = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const all = props.canvasRef?.getSearchableNodes?.() || []
  if (!kw) return all.slice(0, 20)
  return all
    .filter(n => n.label.toLowerCase().includes(kw) || n.nodeId.toLowerCase().includes(kw))
    .slice(0, 20)
})

function onSelect(node) {
  props.canvasRef?.focusNode?.(node.id)
  open.value = false
  keyword.value = node.label
}

function onBlur() {
  // 延迟关闭，避免点击选项时先触发 blur
  setTimeout(() => { open.value = false }, 150)
}
</script>

<template>
  <div class="node-search">
    <el-popover
      :visible="open && matches.length > 0"
      placement="bottom-start"
      :width="260"
      trigger="manual"
      :show-arrow="false"
      popper-class="node-search-popover"
    >
      <template #reference>
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          :placeholder="t('search.placeholder')"
          size="small"
          clearable
          @focus="open = true"
          @blur="onBlur"
          @input="open = true"
          @keyup.enter="matches[0] && onSelect(matches[0])"
        />
      </template>
      <ul class="node-search-list">
        <li
          v-for="m in matches"
          :key="m.id"
          class="node-search-item"
          @mousedown.prevent="onSelect(m)"
        >
          <span class="node-search-label">{{ m.label }}</span>
          <span class="node-search-id">{{ m.nodeId }}</span>
        </li>
        <li v-if="!matches.length" class="node-search-empty">{{ t('search.noResult') }}</li>
      </ul>
    </el-popover>
  </div>
</template>

<style scoped>
.node-search {
  position: absolute;
  top: 12px;
  right: 16px;
  z-index: 12;
  width: 220px;
}
.node-search-list {
  margin: 0;
  padding: 0;
  list-style: none;
  max-height: 260px;
  overflow-y: auto;
}
.node-search-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.node-search-item:hover {
  background: var(--agd-color-bg-active, #ecf5ff);
}
.node-search-label {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-search-id {
  color: #909399;
  font-size: 11px;
  flex-shrink: 0;
}
.node-search-empty {
  padding: 8px 10px;
  color: #909399;
  font-size: 12px;
}
</style>
