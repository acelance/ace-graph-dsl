<script setup>
import { ref, onMounted, onBeforeUnmount, watch, computed, provide } from 'vue'
import { ElMessage } from 'element-plus'
import {
  listSummaries,
  listRecycle,
  listSubgraphReferrers,
  recycleDefinition,
  getRecycleSnapshot,
  restoreRecycle,
  purgeRecycle
} from '../api/graph'
import { usePermissionStore, MENU } from '../stores/permissions'
import { configureGraphDslI18n, useI18n } from '../i18n'
import {
  ACE_GRAPH_EMBED_KEY,
  parseEmbedFromProps,
  validateOtherBizParams,
  OTHER_BIZ_PARAMS_MAX_BYTES
} from '../embed/context'
import GraphDslDesigner from './GraphDslDesigner.vue'
import PropertyPanel from './Designer/PropertyPanel.vue'
import NodePanel from './Designer/NodePanel.vue'
import { ArrowLeft, ArrowRight, Delete, RefreshRight, CloseBold } from '@element-plus/icons-vue'

const props = defineProps({
  title: { type: String, default: '' },
  apiBaseUrl: { type: String, default: '/' },
  locale: { type: String, default: 'zh-CN' },
  /** 业务嵌入上下文（对象优先） */
  embed: { type: Object, default: null },
  /** 扁平别名：仅作缺省，不覆盖 embed 内已有键 */
  graphId: { type: String, default: undefined },
  agentCode: { type: String, default: undefined },
  otherBizParams: { type: String, default: undefined }
})

const perm = usePermissionStore()
const { t } = useI18n()

watch(() => props.locale, (loc) => {
  if (loc) configureGraphDslI18n({ locale: loc })
}, { immediate: true })

const embedCtx = computed(() => parseEmbedFromProps(props))
provide(ACE_GRAPH_EMBED_KEY, embedCtx)

watch(
  () => props.embed?.otherBizParams ?? props.otherBizParams,
  (raw) => {
    if (raw == null || String(raw) === '') return
    const checked = validateOtherBizParams(raw)
    if (!checked.ok) {
      ElMessage.warning(t('manager.otherBizParamsTooLong', { max: OTHER_BIZ_PARAMS_MAX_BYTES }))
    }
  },
  { immediate: true }
)

const summaries = ref([])
const selectedGraphId = ref('')
const panelExpanded = ref(false)
const loading = ref(false)
const showCreate = ref(false)
const newGraphId = ref('')
const newDisplayName = ref('')
const designerRef = ref()

/** 过滤框文案（可手改）；失焦后写入 appliedFilter */
const catalogFilterText = ref('')
/** 已生效的精确匹配过滤串（空 = 全量） */
const appliedFilter = ref('')

/** 回收箱抽屉是否展开（未点击不出现） */
const recycleDrawerOpen = ref(false)
const recycleLoading = ref(false)
const recycleEntries = ref([])
/** 当前选中的回收项 */
const selectedRecycle = ref(null)
/** 回收预览灌入画布的定义 */
const recyclePreviewDef = ref(null)

const visibleSummaries = computed(() => {
  const f = (appliedFilter.value || '').trim()
  if (!f) return summaries.value
  return summaries.value.filter(s => s.graphId === f)
})

const catalogEmptyDescription = computed(() => {
  if ((appliedFilter.value || '').trim()) return t('manager.emptyCatalogFiltered')
  return t('manager.emptyCatalog')
})

// 节点面板折叠抽屉：默认展开，状态持久化到 localStorage
const NODE_PANEL_COLLAPSED_KEY = 'agd.nodePanel.collapsed'
const nodePanelCollapsed = ref(localStorage.getItem(NODE_PANEL_COLLAPSED_KEY) === '1')
watch(nodePanelCollapsed, (v) => {
  try { localStorage.setItem(NODE_PANEL_COLLAPSED_KEY, v ? '1' : '0') } catch (e) { /* ignore */ }
})

// 左侧目录面板折叠抽屉：默认展开，状态持久化
const CATALOG_COLLAPSED_KEY = 'agd.catalog.collapsed'
const catalogCollapsed = ref(localStorage.getItem(CATALOG_COLLAPSED_KEY) === '1')
watch(catalogCollapsed, (v) => {
  try { localStorage.setItem(CATALOG_COLLAPSED_KEY, v ? '1' : '0') } catch (e) { /* ignore */ }
})

/** 面板宽度：可拖动手柄左右拉伸，并持久化 */
const NODE_PANEL_WIDTH_KEY = 'agd.nodePanel.width'
const CATALOG_WIDTH_KEY = 'agd.catalog.width'
const NODE_PANEL_WIDTH_DEFAULT = 240
const CATALOG_WIDTH_DEFAULT = 360
const NODE_PANEL_WIDTH_MIN = 180
const NODE_PANEL_WIDTH_MAX = 520
const CATALOG_WIDTH_MIN = 240
const CATALOG_WIDTH_MAX = 560

function readPersistedWidth(key, fallback, min, max) {
  try {
    const raw = Number(localStorage.getItem(key))
    if (Number.isFinite(raw) && raw >= min && raw <= max) return raw
  } catch (e) { /* ignore */ }
  return fallback
}

const nodePanelWidth = ref(readPersistedWidth(
  NODE_PANEL_WIDTH_KEY, NODE_PANEL_WIDTH_DEFAULT, NODE_PANEL_WIDTH_MIN, NODE_PANEL_WIDTH_MAX
))
const catalogWidth = ref(readPersistedWidth(
  CATALOG_WIDTH_KEY, CATALOG_WIDTH_DEFAULT, CATALOG_WIDTH_MIN, CATALOG_WIDTH_MAX
))

watch(nodePanelWidth, (v) => {
  try { localStorage.setItem(NODE_PANEL_WIDTH_KEY, String(v)) } catch (e) { /* ignore */ }
})
watch(catalogWidth, (v) => {
  try { localStorage.setItem(CATALOG_WIDTH_KEY, String(v)) } catch (e) { /* ignore */ }
})

const catalogPanelStyle = computed(() => (
  catalogCollapsed.value ? undefined : { width: `${catalogWidth.value}px` }
))
const nodePanelStyle = computed(() => (
  nodePanelCollapsed.value ? undefined : { width: `${nodePanelWidth.value}px` }
))

/** 当前拖拽会话：null | { kind, startX, startWidth } */
const resizeSession = ref(null)

function clamp(n, min, max) {
  return Math.min(max, Math.max(min, n))
}

function onResizeMove(evt) {
  const s = resizeSession.value
  if (!s) return
  const dx = evt.clientX - s.startX
  if (s.kind === 'catalog') {
    // 左侧目录：向右拖加宽
    catalogWidth.value = clamp(s.startWidth + dx, CATALOG_WIDTH_MIN, CATALOG_WIDTH_MAX)
  } else if (s.kind === 'nodePanel') {
    // 右侧节点面板：向左拖加宽（把手在面板左缘）
    nodePanelWidth.value = clamp(s.startWidth - dx, NODE_PANEL_WIDTH_MIN, NODE_PANEL_WIDTH_MAX)
  }
}

function endResize() {
  if (!resizeSession.value) return
  resizeSession.value = null
  document.body.classList.remove('agd-resizing-panel')
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', endResize)
  console.info('[GraphDslManager] 面板宽度已调整', {
    catalog: catalogWidth.value,
    nodePanel: nodePanelWidth.value
  })
}

function startResize(kind, evt) {
  if (evt.button !== 0) return
  evt.preventDefault()
  evt.stopPropagation()
  const startWidth = kind === 'catalog' ? catalogWidth.value : nodePanelWidth.value
  resizeSession.value = { kind, startX: evt.clientX, startWidth }
  document.body.classList.add('agd-resizing-panel')
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', endResize)
  console.info('[GraphDslManager] 开始拖拽调整面板宽度', kind, startWidth)
}

onBeforeUnmount(() => {
  endResize()
})

const displayTitle = () => props.title || t('manager.title')

const selectedSummary = computed(() =>
  summaries.value.find(s => s.graphId === selectedGraphId.value)
)

const selectedDisplayName = computed(() => {
  if (selectedRecycle.value) {
    return selectedRecycle.value.displayName || selectedRecycle.value.graphId || ''
  }
  return selectedSummary.value?.displayName || selectedGraphId.value || ''
})

const isSelectedBootstrap = computed(() =>
  Boolean(selectedSummary.value?.bootstrap)
)

const isRecyclePreview = computed(() => Boolean(selectedRecycle.value && recyclePreviewDef.value))

const showDesigner = computed(() => {
  if (isRecyclePreview.value) return true
  return Boolean(selectedGraphId.value && panelExpanded.value)
})

/** 删除确认框 */
const showDeleteDialog = ref(false)
const deleteTarget = ref(null)
const deleteConfirmInput = ref('')
const deleteReferrers = ref([])
const deleteReferrersLoading = ref(false)
const deleteSubmitting = ref(false)
const deleteConfirmOk = computed(() =>
  !!deleteTarget.value && deleteConfirmInput.value === deleteTarget.value.graphId
)

/** 清除确认框 */
const showPurgeDialog = ref(false)
const purgeTarget = ref(null)
const purgeConfirmGraphId = ref('')
const purgeConfirmSuffix = ref('')
const purgeSubmitting = ref(false)
const purgeConfirmOk = computed(() => {
  if (!purgeTarget.value) return false
  return purgeConfirmGraphId.value === purgeTarget.value.graphId
    && purgeConfirmSuffix.value === recycleSuffix(purgeTarget.value.recycleVersion)
})

function recycleSuffix(recycleVersion) {
  if (!recycleVersion || recycleVersion.length < 4) return recycleVersion || ''
  return recycleVersion.slice(-4)
}

/**
 * 从快照挑选预览定义：优先启用版本，否则取列表末条。
 */
function pickPreviewDefinition(snapshot) {
  const defs = snapshot?.definitions || []
  if (!defs.length) return null
  if (snapshot.enabledVersion) {
    const found = defs.find(d => d.version === snapshot.enabledVersion)
    if (found) return found
  }
  return defs[defs.length - 1]
}

function reconcileSelectionAfterFilter() {
  const list = visibleSummaries.value
  if (selectedGraphId.value && !list.some(s => s.graphId === selectedGraphId.value)) {
    selectedGraphId.value = ''
    panelExpanded.value = false
  }
  if (list.length === 1 && !selectedRecycle.value) {
    selectedGraphId.value = list[0].graphId
    panelExpanded.value = true
  }
}

function applyCatalogFilter() {
  appliedFilter.value = (catalogFilterText.value || '').trim()
  catalogFilterText.value = appliedFilter.value
  reconcileSelectionAfterFilter()
}

/** embed.graphId → 过滤框初值并立即生效 */
watch(
  () => embedCtx.value.graphId,
  (gid) => {
    if (gid) {
      catalogFilterText.value = gid
      appliedFilter.value = gid
    }
    reconcileSelectionAfterFilter()
  },
  { immediate: true }
)

async function refreshCatalog() {
  loading.value = true
  try {
    summaries.value = await listSummaries()
    reconcileSelectionAfterFilter()
  } finally {
    loading.value = false
  }
}

async function refreshRecycle() {
  recycleLoading.value = true
  try {
    recycleEntries.value = await listRecycle() || []
    if (selectedRecycle.value) {
      const still = recycleEntries.value.find(
        e => e.graphId === selectedRecycle.value.graphId
          && e.recycleVersion === selectedRecycle.value.recycleVersion
      )
      if (!still) {
        clearRecycleSelection()
      }
    }
  } catch (e) {
    console.error('[GraphDslManager] refreshRecycle failed', e)
    ElMessage.error(e?.response?.data?.error || e?.message || 'load recycle failed')
  } finally {
    recycleLoading.value = false
  }
}

function clearRecycleSelection() {
  selectedRecycle.value = null
  recyclePreviewDef.value = null
}

async function toggleRecycleDrawer() {
  recycleDrawerOpen.value = !recycleDrawerOpen.value
  if (recycleDrawerOpen.value) {
    await refreshRecycle()
  } else {
    clearRecycleSelection()
  }
}

function toggleGraph(graphId) {
  // 选正式图时退出回收预览
  clearRecycleSelection()
  if (selectedGraphId.value === graphId && panelExpanded.value) {
    selectedGraphId.value = ''
    panelExpanded.value = false
    return
  }
  selectedGraphId.value = graphId
  panelExpanded.value = true
}

async function selectRecycleItem(item) {
  selectedGraphId.value = ''
  panelExpanded.value = false
  selectedRecycle.value = item
  recyclePreviewDef.value = null
  try {
    const snapshot = await getRecycleSnapshot(item.graphId, item.recycleVersion)
    const def = pickPreviewDefinition(snapshot)
    if (!def) {
      ElMessage.warning(t('manager.recycleEmpty'))
      return
    }
    recyclePreviewDef.value = def
    console.info('[GraphDslManager] recycle preview loaded', item.graphId, item.recycleVersion)
  } catch (e) {
    console.error('[GraphDslManager] getRecycleSnapshot failed', e)
    ElMessage.error(e?.response?.data?.error || e?.message || 'load snapshot failed')
    clearRecycleSelection()
  }
}

function isActive(graphId) {
  return !selectedRecycle.value && selectedGraphId.value === graphId && panelExpanded.value
}

function isRecycleActive(item) {
  return selectedRecycle.value
    && selectedRecycle.value.graphId === item.graphId
    && selectedRecycle.value.recycleVersion === item.recycleVersion
}

function createGraph() {
  const id = newGraphId.value.trim()
  const displayName = newDisplayName.value.trim() || id
  if (!id) return
  clearRecycleSelection()
  selectedGraphId.value = id
  panelExpanded.value = true
  showCreate.value = false
  newGraphId.value = ''
  newDisplayName.value = ''
  if (!summaries.value.find(s => s.graphId === id)) {
    summaries.value = [{ graphId: id, displayName, version: '1.0.0' }, ...summaries.value]
  }
}

function onNodeDrag(descriptor) {
  if (isRecyclePreview.value) return
  designerRef.value?.onNodeDrag(descriptor)
}

function onNodeLocate(nodeId) {
  designerRef.value?.locateCanvasNode?.(nodeId)
}

/** 打开删除确认：先拉子图引用预检 */
async function openDeleteDialog(item, evt) {
  evt?.stopPropagation?.()
  if (!item || item.bootstrap) return
  deleteTarget.value = item
  deleteConfirmInput.value = ''
  deleteReferrers.value = []
  showDeleteDialog.value = true
  deleteReferrersLoading.value = true
  try {
    const res = await listSubgraphReferrers(item.graphId)
    deleteReferrers.value = res?.graphIds || []
  } catch (e) {
    console.error('[GraphDslManager] listSubgraphReferrers failed', e)
    ElMessage.warning(t('manager.referrersLoadFailed', {
      msg: e?.response?.data?.error || e?.message || ''
    }))
  } finally {
    deleteReferrersLoading.value = false
  }
}

async function confirmDelete() {
  if (!deleteConfirmOk.value || !deleteTarget.value) return
  deleteSubmitting.value = true
  const graphId = deleteTarget.value.graphId
  try {
    await recycleDefinition(graphId, graphId)
    ElMessage.success(t('manager.deleteSuccess'))
    showDeleteDialog.value = false
    if (selectedGraphId.value === graphId) {
      selectedGraphId.value = ''
      panelExpanded.value = false
    }
    await refreshCatalog()
    if (recycleDrawerOpen.value) await refreshRecycle()
  } catch (e) {
    console.error('[GraphDslManager] recycleDefinition failed', e)
    ElMessage.error(t('manager.deleteFailed', {
      msg: e?.response?.data?.error || e?.message || ''
    }))
  } finally {
    deleteSubmitting.value = false
  }
}

async function onRestore(item, evt) {
  evt?.stopPropagation?.()
  if (!item) return
  try {
    await restoreRecycle(item.graphId, item.recycleVersion)
    ElMessage.success(t('manager.restoreSuccess'))
    if (isRecycleActive(item)) clearRecycleSelection()
    await refreshRecycle()
    await refreshCatalog()
  } catch (e) {
    console.error('[GraphDslManager] restoreRecycle failed', e)
    ElMessage.error(t('manager.restoreFailed', {
      msg: e?.response?.data?.error || e?.message || ''
    }))
  }
}

function openPurgeDialog(item, evt) {
  evt?.stopPropagation?.()
  purgeTarget.value = item
  purgeConfirmGraphId.value = ''
  purgeConfirmSuffix.value = ''
  showPurgeDialog.value = true
}

async function confirmPurge() {
  if (!purgeConfirmOk.value || !purgeTarget.value) return
  purgeSubmitting.value = true
  const item = purgeTarget.value
  try {
    // recycleVersion 全串由选中行带上，用户只输入 graphId 与末 4 位
    await purgeRecycle(item.graphId, {
      recycleVersion: item.recycleVersion,
      confirmGraphId: purgeConfirmGraphId.value,
      confirmSuffix: purgeConfirmSuffix.value
    })
    ElMessage.success(t('manager.purgeSuccess'))
    showPurgeDialog.value = false
    if (isRecycleActive(item)) clearRecycleSelection()
    await refreshRecycle()
  } catch (e) {
    console.error('[GraphDslManager] purgeRecycle failed', e)
    ElMessage.error(t('manager.purgeFailed', {
      msg: e?.response?.data?.error || e?.message || ''
    }))
  } finally {
    purgeSubmitting.value = false
  }
}

onMounted(async () => {
  if (!perm.loaded) await perm.load()
  await refreshCatalog()
})
</script>

<template>
  <div class="ace-graph-dsl-manager">
    <aside
      :class="['left-dock', { 'left-dock--collapsed': catalogCollapsed }]"
      :style="catalogPanelStyle"
    >
      <!-- 展开态：完整目录内容 -->
      <template v-if="!catalogCollapsed">
        <div class="catalog-header">
          <h3>{{ displayTitle() }}</h3>
          <div style="display:flex;align-items:center;gap:8px;">
            <el-button size="small" text @click="catalogCollapsed = true" :title="t('nodePanel.collapse')">
              <el-icon><ArrowLeft /></el-icon>
            </el-button>
            <el-button size="small" @click="refreshCatalog" :loading="loading">{{ t('common.refresh') }}</el-button>
          </div>
        </div>
        <div class="catalog-filter">
          <el-input
            v-model="catalogFilterText"
            size="small"
            clearable
            :placeholder="t('manager.catalogFilterPlaceholder')"
            @blur="applyCatalogFilter"
            @clear="applyCatalogFilter"
            @keydown.enter.prevent="applyCatalogFilter"
          />
        </div>
        <el-button v-if="perm.can(MENU.GRAPH_CREATE)" type="primary" plain class="create-btn" @click="showCreate = true">
          {{ t('manager.createGraph') }}
        </el-button>

        <div v-loading="loading" class="catalog-scroll">
          <div v-if="visibleSummaries.length" class="catalog-list">
            <div v-for="item in visibleSummaries" :key="item.graphId" class="catalog-block">
              <div
                class="catalog-item-row"
                :class="{ active: isActive(item.graphId) }"
                @click="toggleGraph(item.graphId)"
              >
                <div class="catalog-item">
                  <strong>{{ item.displayName || item.graphId }}</strong>
                  <small>{{ item.graphId }} · v{{ item.version }}
                    <el-tag v-if="item.bootstrap" size="small" type="warning" effect="plain" style="margin-left: 6px;">内置</el-tag>
                  </small>
                </div>
                <el-button
                  v-if="!item.bootstrap && perm.can(MENU.GRAPH_DELETE)"
                  class="catalog-row-action"
                  size="small"
                  text
                  type="danger"
                  :icon="Delete"
                  :title="t('manager.deleteGraph')"
                  @click="openDeleteDialog(item, $event)"
                />
              </div>
              <div
                v-if="isActive(item.graphId)"
                class="catalog-property-drawer"
                @click.stop
              >
                <div class="drawer-title">{{ t('propertyPanel.title') }}</div>
                <PropertyPanel embedded />
              </div>
            </div>
          </div>
          <el-empty v-else-if="!loading" :description="catalogEmptyDescription" />
        </div>

        <!-- 目录底部：图回收箱入口 -->
        <div class="recycle-footer">
          <el-button
            v-if="perm.can(MENU.GRAPH_VIEW)"
            class="recycle-toggle"
            text
            @click="toggleRecycleDrawer"
          >
            {{ t('manager.recycleBin') }}
            <el-icon style="margin-left:4px;">
              <ArrowRight v-if="!recycleDrawerOpen" />
              <ArrowLeft v-else />
            </el-icon>
          </el-button>
        </div>

        <!-- 回收箱抽屉：仅点击后出现 -->
        <div v-if="recycleDrawerOpen" v-loading="recycleLoading" class="recycle-drawer">
          <div class="recycle-drawer-head">
            <span>{{ t('manager.recycleBin') }}</span>
            <el-button size="small" text @click="refreshRecycle" :loading="recycleLoading">{{ t('common.refresh') }}</el-button>
          </div>
          <div v-if="recycleEntries.length" class="catalog-list recycle-list">
            <div v-for="item in recycleEntries" :key="`${item.graphId}::${item.recycleVersion}`" class="catalog-block">
              <div
                class="catalog-item-row"
                :class="{ active: isRecycleActive(item) }"
                @click="selectRecycleItem(item)"
              >
                <div class="catalog-item">
                  <strong>{{ item.displayName || item.graphId }}</strong>
                  <small>{{ item.graphId }}</small>
                  <small class="recycle-version">{{ item.recycleVersion }}</small>
                </div>
                <div class="catalog-row-actions" @click.stop>
                  <el-button
                    v-if="perm.can(MENU.GRAPH_RESTORE)"
                    size="small"
                    text
                    type="primary"
                    :icon="RefreshRight"
                    :title="t('manager.restoreGraph')"
                    @click="onRestore(item, $event)"
                  />
                  <el-button
                    v-if="perm.can(MENU.GRAPH_PURGE)"
                    size="small"
                    text
                    type="danger"
                    :icon="CloseBold"
                    :title="t('manager.purgeGraph')"
                    @click="openPurgeDialog(item, $event)"
                  />
                </div>
              </div>
              <div
                v-if="isRecycleActive(item)"
                class="catalog-property-drawer"
                @click.stop
              >
                <div class="drawer-title">{{ t('manager.recyclePreviewHint') }}</div>
                <PropertyPanel embedded read-only />
              </div>
            </div>
          </div>
          <el-empty v-else-if="!recycleLoading" :description="t('manager.recycleEmpty')" />
        </div>
      </template>

      <!-- 折叠态：竖向把手 -->
      <div v-else class="collapse-handle collapse-handle--left" @click="catalogCollapsed = false" :title="t('nodePanel.expand')">
        <el-icon class="handle-icon"><ArrowRight /></el-icon>
        <span class="handle-text">{{ t('nodePanel.title') }}</span>
      </div>
      <!-- 右缘拉伸把手：展开时可左右拖宽 -->
      <div
        v-if="!catalogCollapsed"
        class="panel-resize-handle panel-resize-handle--catalog"
        :title="t('manager.catalogResize')"
        @mousedown="startResize('catalog', $event)"
      />
    </aside>

    <main class="designer-panel">
      <template v-if="showDesigner">
        <GraphDslDesigner
          v-if="isRecyclePreview"
          :key="`recycle-${selectedRecycle.graphId}-${selectedRecycle.recycleVersion}`"
          :graph-id="selectedRecycle.graphId"
          :title="selectedDisplayName"
          :api-base-url="apiBaseUrl"
          :locale="locale"
          recycle-preview
          :initial-definition="recyclePreviewDef"
          read-only
        />
        <GraphDslDesigner
          v-else
          ref="designerRef"
          :key="`${selectedGraphId}-${locale}`"
          :graph-id="selectedGraphId"
          :title="selectedDisplayName"
          :api-base-url="apiBaseUrl"
          :locale="locale"
          :read-only="isSelectedBootstrap"
          @saved="refreshCatalog"
        />
      </template>
      <div v-else class="empty-center">
        <el-empty :description="t('manager.selectOrCreate')" />
      </div>
      <div
        v-if="!isRecyclePreview"
        class="node-panel-wrap"
        :class="{ 'node-panel-wrap--collapsed': nodePanelCollapsed }"
        :style="nodePanelStyle"
      >
        <!-- 左缘拉伸把手：向左拖加宽节点面板 -->
        <div
          v-if="!nodePanelCollapsed"
          class="panel-resize-handle panel-resize-handle--node"
          :title="t('nodePanel.resize')"
          @mousedown="startResize('nodePanel', $event)"
        />
        <NodePanel
          v-model:collapsed="nodePanelCollapsed"
          :class="['node-panel-right', { 'node-panel-right--collapsed': nodePanelCollapsed }]"
          @node-drag="onNodeDrag"
          @node-locate="onNodeLocate"
        />
      </div>
    </main>

    <el-dialog v-model="showCreate" :title="t('manager.createDialogTitle')" width="420px">
      <el-form label-width="90px">
        <el-form-item :label="t('manager.graphId')" required>
          <el-input v-model="newGraphId" :placeholder="t('manager.graphIdPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('manager.displayName')">
          <el-input v-model="newDisplayName" :placeholder="t('manager.displayNamePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreate = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="createGraph">{{ t('common.create') }}</el-button>
      </template>
    </el-dialog>

    <!-- 删除二次确认：输入 graphId；展示子图引用预检 -->
    <el-dialog
      v-model="showDeleteDialog"
      :title="t('manager.deleteConfirmTitle')"
      width="480px"
      @closed="deleteTarget = null"
    >
      <p class="dialog-hint">{{ t('manager.deleteConfirmHint') }}</p>
      <p v-if="deleteTarget" class="dialog-target">
        <strong>{{ deleteTarget.displayName || deleteTarget.graphId }}</strong>
        <small>{{ deleteTarget.graphId }}</small>
      </p>
      <div v-loading="deleteReferrersLoading" class="referrers-box">
        <div class="referrers-title">{{ t('manager.referrersTitle') }}</div>
        <ul v-if="deleteReferrers.length" class="referrers-list">
          <li v-for="id in deleteReferrers" :key="id">{{ id }}</li>
        </ul>
        <div v-else-if="!deleteReferrersLoading" class="referrers-none">{{ t('manager.referrersNone') }}</div>
      </div>
      <el-input
        v-model="deleteConfirmInput"
        :placeholder="t('manager.deleteConfirmPlaceholder')"
        style="margin-top: 12px;"
      />
      <template #footer>
        <el-button @click="showDeleteDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="danger"
          :disabled="!deleteConfirmOk"
          :loading="deleteSubmitting"
          @click="confirmDelete"
        >{{ t('manager.deleteConfirmOk') }}</el-button>
      </template>
    </el-dialog>

    <!-- 清除二次确认：用户只输入 graphId 与末 4 位；全串由选中行带上 -->
    <el-dialog
      v-model="showPurgeDialog"
      :title="t('manager.purgeConfirmTitle')"
      width="480px"
      @closed="purgeTarget = null"
    >
      <p class="dialog-hint">{{ t('manager.purgeConfirmHint') }}</p>
      <p v-if="purgeTarget" class="dialog-target">
        <strong>{{ purgeTarget.displayName || purgeTarget.graphId }}</strong>
        <small>{{ purgeTarget.graphId }}</small>
        <small class="recycle-version">{{ purgeTarget.recycleVersion }}</small>
      </p>
      <el-form label-width="100px" style="margin-top: 12px;">
        <el-form-item :label="t('manager.graphId')" required>
          <el-input v-model="purgeConfirmGraphId" :placeholder="t('manager.deleteConfirmPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('manager.purgeSuffixPlaceholder')" required>
          <el-input v-model="purgeConfirmSuffix" maxlength="4" :placeholder="t('manager.purgeSuffixPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showPurgeDialog = false">{{ t('common.cancel') }}</el-button>
        <el-button
          type="danger"
          :disabled="!purgeConfirmOk"
          :loading="purgeSubmitting"
          @click="confirmPurge"
        >{{ t('manager.purgeConfirmOk') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ace-graph-dsl-manager {
  display: flex;
  width: 100%;
  height: 100%;
  min-height: 600px;
  margin: 0;
  padding: 0;
}
.left-dock {
  width: var(--agd-panel-width-catalog-expanded, 360px);
  flex-shrink: 0;
  border-right: 1px solid var(--agd-color-border, #e4e7ed);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--agd-color-bg, #fff);
  position: relative;
}
.catalog-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--agd-color-border, #e4e7ed);
  flex-shrink: 0;
}
.catalog-header h3 {
  margin: 0;
  font-size: 15px;
  color: var(--agd-color-text, #303133);
}
.catalog-filter {
  padding: 8px 16px 0;
  flex-shrink: 0;
}
.create-btn {
  margin: 12px 16px 0;
  flex-shrink: 0;
}
.catalog-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 0 4px;
}
.catalog-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.catalog-block {
  display: flex;
  flex-direction: column;
}
.catalog-item-row {
  margin: 0 10px;
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.15s, border-color 0.15s;
  display: flex;
  align-items: flex-start;
  gap: 4px;
}
.catalog-item-row:hover:not(.active) {
  background: var(--agd-color-bg-hover, #f5f7fa);
}
.catalog-item-row.active {
  background: var(--agd-color-bg-active, #ecf5ff);
  border-color: rgba(64, 158, 255, 0.35);
}
.catalog-item-row.active .catalog-item strong {
  color: var(--agd-color-primary, #409eff);
}
.catalog-item {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
  flex: 1;
  min-width: 0;
}
.catalog-item strong {
  font-size: 13px;
  color: var(--agd-color-text, #303133);
  transition: color 0.15s;
}
.catalog-item small {
  color: var(--agd-color-text-secondary, #909399);
  font-size: 11px;
}
.catalog-row-action,
.catalog-row-actions {
  flex-shrink: 0;
  pointer-events: auto;
}
.catalog-row-actions {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.recycle-version {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
}
.catalog-property-drawer {
  margin: 4px 10px 8px;
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 6px;
  background: var(--agd-color-bg, #fff);
  overflow: hidden;
  box-shadow: 0 1px 4px rgba(64, 158, 255, 0.08);
}
.drawer-title {
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 600;
  color: var(--agd-color-primary, #409eff);
  background: var(--agd-color-bg-active, #ecf5ff);
  border-bottom: 1px solid rgba(64, 158, 255, 0.15);
}
.recycle-footer {
  flex-shrink: 0;
  border-top: 1px solid var(--agd-color-border, #e4e7ed);
  padding: 6px 10px;
}
.recycle-toggle {
  width: 100%;
  justify-content: space-between;
  color: var(--agd-color-text-secondary, #909399);
}
.recycle-drawer {
  flex-shrink: 0;
  max-height: 42%;
  overflow-y: auto;
  border-top: 1px solid var(--agd-color-border, #e4e7ed);
  background: var(--agd-color-bg, #fff);
  padding-bottom: 8px;
}
.recycle-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 16px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--agd-color-text-secondary, #909399);
}
.recycle-list {
  padding-top: 4px;
}
.designer-panel {
  flex: 1;
  overflow: hidden;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: row;
}
.node-panel-wrap {
  position: relative;
  width: var(--agd-panel-width-left, 240px);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid var(--agd-color-border, #e4e7ed);
  background: var(--agd-color-bg, #fff);
}
.node-panel-wrap--collapsed {
  width: 38px !important;
  min-width: 38px;
  border-left: none;
  background: linear-gradient(to right, rgba(64,158,255,0.03), transparent);
}
.node-panel-right {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow-y: auto;
  background: transparent;
  border-left: none;
}
.node-panel-right--collapsed {
  width: 38px;
  min-width: 38px;
  border-left: none;
  background: transparent;
}
/* 面板左右拉伸把手 */
.panel-resize-handle {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 6px;
  z-index: 5;
  cursor: col-resize;
  touch-action: none;
  background: transparent;
  transition: background 0.15s;
}
.panel-resize-handle::after {
  content: '';
  position: absolute;
  top: 50%;
  left: 50%;
  width: 2px;
  height: 28px;
  margin: -14px 0 0 -1px;
  border-radius: 1px;
  background: transparent;
  transition: background 0.15s;
}
.panel-resize-handle:hover,
.panel-resize-handle:active {
  background: rgba(64, 158, 255, 0.12);
}
.panel-resize-handle:hover::after,
.panel-resize-handle:active::after {
  background: var(--agd-color-primary, #409eff);
}
.panel-resize-handle--catalog {
  right: -3px;
}
.panel-resize-handle--node {
  left: -3px;
}
.designer-panel > :first-child:not(.el-empty) {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
.empty-center {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 0;
}
.dialog-hint {
  margin: 0 0 8px;
  color: var(--agd-color-text-secondary, #909399);
  font-size: 13px;
}
.dialog-target {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 0 0 8px;
}
.dialog-target small {
  color: var(--agd-color-text-secondary, #909399);
}
.referrers-box {
  min-height: 48px;
  padding: 8px 10px;
  border: 1px solid var(--agd-color-border, #e4e7ed);
  border-radius: 6px;
  background: var(--agd-color-bg-hover, #f5f7fa);
}
.referrers-title {
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 6px;
}
.referrers-list {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
}
.referrers-none {
  font-size: 12px;
  color: var(--agd-color-text-secondary, #909399);
}
/* ====== 左侧目录面板折叠态 ====== */
.left-dock--collapsed {
  width: 38px !important;
  min-width: 38px !important;
  padding: 0 !important;
  border-right: none !important;
  overflow: hidden;
  display: flex;
  justify-content: center;
  align-items: center;
  background: linear-gradient(to right, rgba(64,158,255,0.03), transparent);
}
.collapse-handle--left {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: var(--agd-color-text-secondary, #909399);
  user-select: none;
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
  border-radius: 20px;
  padding: 10px 4px;
  border: 1px solid transparent;
}
.collapse-handle--left .handle-icon { font-size: 14px; }
.collapse-handle--left .handle-text {
  writing-mode: vertical-rl;
  letter-spacing: 3px;
  font-size: 11px;
  line-height: 1.4;
}
.collapse-handle--left:hover {
  color: var(--agd-color-primary, #409eff);
  background: rgba(64, 158, 255, 0.06);
  border-color: rgba(64, 158, 255, 0.2);
  transform: translateX(2px);
}
</style>

<!-- 拖宽时禁止选中文字、统一光标（非 scoped，作用在 body） -->
<style>
body.agd-resizing-panel {
  cursor: col-resize !important;
  user-select: none !important;
}
body.agd-resizing-panel * {
  cursor: col-resize !important;
  user-select: none !important;
}
</style>
