<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getVersion, getEnabled, rollback } from '../../api/graph'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { diffGraphStructure } from '../../utils/graphDiff'
import { useI18n } from '../../i18n'
import VersionDiffPanel from './VersionDiffPanel.vue'

const visible = defineModel('visible', { type: Boolean, default: false })

const props = defineProps({
  canvasRef: { type: Object, default: null }
})

const emit = defineEmits(['rolled-back'])

const editor = useGraphEditorStore()
const perm = usePermissionStore()
const { t } = useI18n()

const loading = ref(false)
const rollingBack = ref(false)
const listTab = ref('published')
const enabledDef = ref(null)
const enabledVersion = ref('')
const selectedVersion = ref('')
const selectedDef = ref(null)
const diffTab = ref('structure')

const sortedDraftVersions = computed(() => {
  return [...(editor.versions || [])].sort((a, b) => {
    return String(b.version).localeCompare(String(a.version), undefined, { numeric: true })
  })
})

const currentDef = computed(() => editor.buildDefinition())

const diffResult = computed(() => {
  if (!selectedDef.value) return null
  return diffGraphStructure(currentDef.value, selectedDef.value)
})

const canRollback = computed(() => {
  return perm.can(MENU.GRAPH_ROLLBACK)
    && listTab.value === 'drafts'
    && selectedVersion.value
    && selectedVersion.value !== enabledVersion.value
})

async function loadVersions() {
  if (!editor.graphId) return
  loading.value = true
  try {
    await editor.fetchVersions()
    try {
      enabledDef.value = await getEnabled(editor.graphId)
      enabledVersion.value = enabledDef.value?.version || ''
    } catch {
      enabledDef.value = null
      enabledVersion.value = ''
    }
    if (listTab.value === 'published' && enabledVersion.value) {
      await selectVersion(enabledVersion.value, enabledDef.value)
    } else if (listTab.value === 'drafts' && sortedDraftVersions.value.length) {
      const latest = sortedDraftVersions.value[0]
      await selectVersion(latest.version, latest)
    }
  } finally {
    loading.value = false
  }
}

async function selectVersion(version, cachedDef = null) {
  selectedVersion.value = version
  if (cachedDef) {
    selectedDef.value = cachedDef
    return
  }
  try {
    selectedDef.value = await getVersion(editor.graphId, version)
  } catch (e) {
    selectedDef.value = null
    ElMessage.error(t('version.loadFailed', { msg: e.response?.data?.error || e.message }))
  }
}

async function onLoadBaseline() {
  if (!selectedDef.value || !selectedVersion.value) return
  editor.loadVersionAsBaseline(selectedDef.value)
  props.canvasRef?.renderFromDefinition?.(selectedDef.value)
  ElMessage.success(t('version.loadBaselineSuccess', { version: selectedVersion.value }))
  visible.value = false
}

async function onRollback() {
  if (!selectedVersion.value) return
  try {
    await ElMessageBox.confirm(
      t('version.rollbackConfirm', { version: selectedVersion.value }),
      t('version.rollbackTitle'),
      { type: 'warning' }
    )
  } catch {
    return
  }
  rollingBack.value = true
  try {
    const result = await rollback(editor.graphId, selectedVersion.value)
    if (!result?.success) {
      throw new Error(result?.message || 'rollback failed')
    }
    const def = await editor.loadLatest()
    if (def) {
      props.canvasRef?.renderFromDefinition?.(def)
    }
    await editor.loadEnabledVersion()
    enabledVersion.value = selectedVersion.value
    await loadVersions()
    ElMessage.success(t('version.rollbackSuccess', { version: selectedVersion.value }))
    emit('rolled-back', { version: selectedVersion.value })
  } catch (e) {
    ElMessage.error(t('version.rollbackFailed', { msg: e.response?.data?.error || e.message }))
  } finally {
    rollingBack.value = false
  }
}

watch(listTab, async () => {
  selectedVersion.value = ''
  selectedDef.value = null
  if (!visible.value) return
  if (listTab.value === 'published' && enabledVersion.value) {
    await selectVersion(enabledVersion.value, enabledDef.value)
  } else if (listTab.value === 'drafts' && sortedDraftVersions.value.length) {
    const latest = sortedDraftVersions.value[0]
    await selectVersion(latest.version, latest)
  }
})

const drawerSize = 'calc(100vw - var(--agd-panel-width-catalog-expanded, 360px))'

function setDrawerOpenClass(open) {
  document.body.classList.toggle('agd-version-drawer-open', Boolean(open))
}

watch(visible, (open) => {
  setDrawerOpenClass(open)
  if (open) {
    listTab.value = 'published'
    selectedVersion.value = ''
    selectedDef.value = null
    diffTab.value = 'structure'
    loadVersions()
  }
})

onBeforeUnmount(() => {
  setDrawerOpenClass(false)
})
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="t('version.title')"
    direction="rtl"
    :size="drawerSize"
    destroy-on-close
    append-to-body
    class="version-history-drawer"
    modal-class="version-history-drawer-modal"
    @click.stop
    @mousedown.stop
  >
    <div v-loading="loading" class="drawer-body" @click.stop @mousedown.stop>
      <aside class="version-list" @click.stop @mousedown.stop>
        <el-tabs v-model="listTab" class="version-tabs" @click.stop>
          <el-tab-pane :label="t('version.tabPublished')" name="published">
            <div
              v-if="enabledVersion"
              class="version-item active"
              @click="selectVersion(enabledVersion, enabledDef)"
            >
              <div class="version-row">
                <span class="version-no">v{{ enabledVersion }}</span>
                <el-tag type="success" size="small">{{ t('version.enabled') }}</el-tag>
              </div>
              <small>{{ t('version.publishedHint') }}</small>
            </div>
            <el-empty v-else :description="t('version.noPublished')" :image-size="48" />
          </el-tab-pane>

          <el-tab-pane :label="t('version.tabDrafts')" name="drafts">
            <div
              v-for="item in sortedDraftVersions"
              :key="item.version"
              class="version-item"
              :class="{ active: item.version === selectedVersion }"
              @click="selectVersion(item.version, item)"
            >
              <div class="version-row">
                <span class="version-no">v{{ item.version }}</span>
                <el-tag v-if="item.version === editor.version" type="info" size="small">
                  {{ t('version.editing') }}
                </el-tag>
                <el-tag v-else-if="item.version === enabledVersion" type="success" size="small">
                  {{ t('version.enabled') }}
                </el-tag>
              </div>
              <small v-if="item.displayName">{{ item.displayName }}</small>
            </div>
            <el-empty v-if="!loading && !sortedDraftVersions.length" :description="t('common.noData')" :image-size="48" />
          </el-tab-pane>
        </el-tabs>
      </aside>

      <main class="version-detail">
        <div v-if="selectedVersion" class="detail-actions">
          <el-button size="small" @click="onLoadBaseline">
            {{ t('version.loadBaseline') }}
          </el-button>
          <el-button
            v-if="canRollback"
            type="warning"
            size="small"
            :loading="rollingBack"
            @click="onRollback"
          >
            {{ t('version.rollback') }}
          </el-button>
        </div>
        <VersionDiffPanel
          v-model:active-tab="diffTab"
          :diff="diffResult"
          :base-def="currentDef"
          :target-def="selectedDef"
        />
      </main>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-body {
  display: flex;
  gap: 12px;
  height: calc(100vh - 120px);
  min-height: 400px;
}
.version-list {
  width: 200px;
  flex-shrink: 0;
  overflow-y: auto;
  border-right: 1px solid var(--agd-color-border, #e4e7ed);
  padding-right: 8px;
}
.version-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}
.version-item {
  padding: 10px 8px;
  border-radius: 4px;
  cursor: pointer;
  margin-bottom: 4px;
  border: 1px solid transparent;
  transition: background 0.15s;
}
.version-item:hover {
  background: var(--agd-color-bg-hover, #f5f7fa);
}
.version-item.active {
  border-color: var(--agd-color-primary, #409eff);
  background: var(--agd-color-bg-active, #ecf5ff);
}
.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.version-no {
  font-weight: 600;
  font-size: 13px;
  color: var(--agd-color-text, #303133);
}
.version-item small {
  display: block;
  margin-top: 4px;
  color: var(--agd-color-text-secondary, #909399);
  font-size: 11px;
  line-height: 1.4;
}
.version-detail {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.detail-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
</style>

<style>
/* 抽屉打开时禁止左侧工作流列表接收点击，避免与版本 Tab 区域重叠时误触收起 */
body.agd-version-drawer-open .ace-graph-dsl-manager .catalog-item-row {
  pointer-events: none;
}
</style>
