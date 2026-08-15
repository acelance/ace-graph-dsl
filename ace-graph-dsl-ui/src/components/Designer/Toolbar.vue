<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { RefreshLeft, RefreshRight, VideoPlay, Upload, Download, Share, Switch, ZoomIn, ZoomOut, FullScreen, ScaleToOriginal, MagicStick, MapLocation, FolderOpened, Rank, Crop } from '@element-plus/icons-vue'
import { useGraphEditorStore } from '../../stores/graphEditor'
import { usePermissionStore, MENU } from '../../stores/permissions'
import { useI18n } from '../../i18n'
import VersionHistoryDrawer from './VersionHistoryDrawer.vue'

const editor = useGraphEditorStore()
const perm = usePermissionStore()
const { t } = useI18n()

const props = defineProps({
  canvasRef: { type: Object, default: null },
  title: { type: String, default: '' },
  /** 标题悬浮在画布左上角（右下斜切文件夹签） */
  floatingMeta: { type: Boolean, default: false },
  /** 操作按钮悬浮在画布底部中央 */
  floatingActions: { type: Boolean, default: false },
  showMeta: { type: Boolean, default: true },
  showActions: { type: Boolean, default: true },
  readOnly: { type: Boolean, default: false }
})
const emit = defineEmits(['save', 'validate', 'preview', 'publish', 'undo', 'redo', 'dryRun', 'importDsl', 'exportDsl', 'topology', 'zoomIn', 'zoomOut', 'fit', 'resetZoom', 'autoLayout', 'toggleMinimap', 'createGroup', 'toggleBoxSelect', 'extractSubgraph'])

const showVersionHistory = ref(false)

// ── 工具栏拖拽 ──
const actionsRef = ref(null)
const isDragging = ref(false)
const dragOffset = ref({ x: 0, y: 0 })
/** 拖拽后的自定义位置；null 表示使用 CSS 默认的居中底部定位 */
const dragPosition = ref(null)

function onDragStart(e) {
  if (!props.floatingActions || !actionsRef.value) return
  // 仅左键且点击在工具栏自身（非按钮交互区域）时才触发拖拽
  if (e.button !== 0) return
  const rect = actionsRef.value.getBoundingClientRect()
  dragOffset.value = { x: e.clientX - rect.left, y: e.clientY - rect.top }
  isDragging.value = true
  e.preventDefault()
  document.addEventListener('mousemove', onDragMove)
  document.addEventListener('mouseup', onDragEnd)
}

function onDragMove(e) {
  if (!isDragging.value || !actionsRef.value) return
  const parent = actionsRef.value.parentElement
  if (!parent) return
  const pRect = parent.getBoundingClientRect()
  const aRect = actionsRef.value.getBoundingClientRect()
  let x = e.clientX - pRect.left - dragOffset.value.x
  let y = e.clientY - pRect.top - dragOffset.value.y
  // 限制不超出父容器边界
  x = Math.max(0, Math.min(x, pRect.width - aRect.width))
  y = Math.max(0, Math.min(y, pRect.height - aRect.height))
  dragPosition.value = { x, y }
}

function onDragEnd() {
  isDragging.value = false
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', onDragEnd)
}

onBeforeUnmount(() => {
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', onDragEnd)
})

const BADGE_BEVEL = 14
const BADGE_BEVEL_RISE = 24
const badgeRef = ref(null)
const badgeSize = ref({ w: 0, h: 0 })

const badgeOutlinePath = computed(() => {
  const { w, h } = badgeSize.value
  if (!w || !h) return ''
  const inset = 0.625
  return (
    'M ' + inset + ' ' + inset +
    ' H ' + (w - inset) +
    ' V ' + (h - BADGE_BEVEL_RISE - inset) +
    ' L ' + (w - BADGE_BEVEL - inset) + ' ' + (h - inset) +
    ' H ' + inset +
    ' Z'
  )
})

function syncBadgeSize() {
  const el = badgeRef.value
  if (!el) return
  badgeSize.value = { w: el.offsetWidth, h: el.offsetHeight }
}

let badgeResizeObserver = null

watch(
  () => props.floatingMeta,
  async (enabled) => {
    if (!enabled) return
    await nextTick()
    syncBadgeSize()
  },
  { immediate: true }
)

onMounted(() => {
  if (!props.floatingMeta || typeof ResizeObserver === 'undefined') return
  const el = badgeRef.value
  if (!el) return
  badgeResizeObserver = new ResizeObserver(() => syncBadgeSize())
  badgeResizeObserver.observe(el)
})

onBeforeUnmount(() => {
  badgeResizeObserver?.disconnect()
})

const isDraftUnpublished = computed(() => {
  return editor.enabledVersion && editor.version !== editor.enabledVersion
})

async function ensureVersionBeforePersist() {
  if (!editor.hasContentChanged()) return true
  if (!editor.needsVersionBump()) return true
  const suggested = editor.suggestNextVersion()
  const max = editor.maxKnownVersion() || editor.baselineVersion
  try {
    await ElMessageBox.confirm(
      t('toolbar.versionBumpMessage', {
        current: editor.version,
        suggested,
        max: max || editor.baselineVersion
      }),
      t('toolbar.versionBumpTitle'),
      { type: 'warning', confirmButtonText: t('toolbar.versionBumpConfirm', { suggested }), cancelButtonText: t('common.cancel') }
    )
    editor.version = suggested
    return true
  } catch {
    return false
  }
}

async function onSave() {
  if (!(await ensureVersionBeforePersist())) return
  try {
    const result = await editor.save()
    if (result.unchanged) {
      ElMessage.info(t('toolbar.saveUnchanged'))
      return
    }
    if (result.collapsedToRoot) {
      ElMessage.success(t('toolbar.saveCollapsed'))
      emit('save')
      return
    }
    ElMessage.success(t('toolbar.saveSuccess'))
    emit('save')
  } catch (e) {
    ElMessage.error(t('toolbar.saveFailed', { msg: e.response?.data?.error || e.message || e }))
  }
}

async function onValidate() {
  const result = await editor.validate()
  if (result.ok) {
    ElMessage.success(t('toolbar.validateSuccess'))
  } else {
    ElMessage.warning(t('toolbar.validateFailed', { count: result.errors.length }))
  }
  emit('validate', result)
}

async function onPreview() {
  try {
    await editor.loadPlantUml()
    ElMessage.success(t('toolbar.previewSuccess'))
    emit('preview')
  } catch (e) {
    ElMessage.error(t('toolbar.previewFailed', { msg: e.message || e }))
  }
}

async function onPublish() {
  if (!(await ensureVersionBeforePersist())) return
  try {
    await ElMessageBox.confirm(t('toolbar.publishConfirm'), t('toolbar.publishTitle'), { type: 'warning' })
  } catch {
    return
  }
  try {
    const result = await editor.publishCurrent()
    if (result.success) {
      ElMessage.success(t('toolbar.publishSuccess', { version: result.version }))
      emit('publish', result)
    } else {
      ElMessage.error(t('toolbar.publishFailed', { msg: result.message }))
    }
  } catch (e) {
    ElMessage.error(t('toolbar.publishFailed', { msg: e.message || e }))
  }
}
</script>

<template>
  <div
    class="toolbar-root"
    :class="{
      'toolbar-root--floating-overlay': floatingMeta || floatingActions,
      'toolbar-root--floating-actions': floatingActions
    }"
  >
    <div
      v-if="showMeta"
      class="toolbar-meta-shell"
      :class="{ 'toolbar-meta-shell--badge': floatingMeta }"
    >
      <div
        ref="badgeRef"
        class="toolbar-meta"
        :class="{ 'toolbar-meta--badge': floatingMeta }"
      >
        <svg
          v-if="floatingMeta && badgeOutlinePath"
          class="toolbar-meta-badge-outline"
          aria-hidden="true"
        >
          <path
            :d="badgeOutlinePath"
            fill="none"
            stroke="rgba(64, 158, 255, 0.55)"
            stroke-width="1.25"
            vector-effect="non-scaling-stroke"
          />
        </svg>
        <span class="toolbar-title">{{ title || t('toolbar.title') }}</span>
        <div class="version-status">
          <el-tag v-if="editor.enabledVersion" type="success" size="small" effect="plain">
            {{ t('toolbar.statusRunning', { version: editor.enabledVersion }) }}
          </el-tag>
          <el-tag v-if="editor.baselineVersion" type="info" size="small" effect="plain">
            {{ t('toolbar.statusBaseline', { version: editor.baselineVersion }) }}
          </el-tag>
          <el-tag type="info" size="small" effect="plain">
            {{ t('toolbar.statusDraft', { version: editor.version }) }}
          </el-tag>
          <el-tag v-if="isDraftUnpublished" type="warning" size="small" effect="plain">
            {{ t('toolbar.statusUnpublished') }}
          </el-tag>
        </div>
      </div>
    </div>

    <div v-if="showActions" ref="actionsRef" class="toolbar-actions" :class="{ 'toolbar-actions--floating': floatingActions, 'is-dragging': isDragging }" :style="dragPosition ? { left: dragPosition.x + 'px', top: dragPosition.y + 'px', bottom: 'auto', right: 'auto', transform: 'none' } : {}">
      <span v-if="floatingActions" class="toolbar-drag-handle" :title="t('toolbar.dragHint') || '拖动移动'" @mousedown="onDragStart">
        <Rank />
      </span>
      <div class="toolbar-actions-body">
      <el-button-group>
        <el-button :icon="RefreshLeft" :disabled="!editor.canUndo" :title="t('toolbar.undo')" size="small" @click="emit('undo')" />
        <el-button :icon="RefreshRight" :disabled="!editor.canRedo" :title="t('toolbar.redo')" size="small" @click="emit('redo')" />
        <el-button v-if="!readOnly && perm.can(MENU.GRAPH_SAVE)" @click="onSave" :loading="editor.saving" size="small">
          {{ t('toolbar.save') }}
        </el-button>
        <el-button v-if="perm.can(MENU.GRAPH_VALIDATE)" @click="onValidate" size="small">
          {{ t('toolbar.validate') }}
        </el-button>
        <el-button v-if="perm.can(MENU.GRAPH_PREVIEW)" @click="onPreview" size="small">
          {{ t('toolbar.preview') }}
        </el-button>
        <el-button v-if="!readOnly && perm.can(MENU.GRAPH_PUBLISH)" type="primary" @click="onPublish" :loading="editor.publishing" size="small">
          {{ t('toolbar.publish') }}
        </el-button>
        <el-button v-if="perm.can(MENU.GRAPH_VALIDATE)" :icon="VideoPlay" @click="emit('dryRun')" size="small">
          {{ t('toolbar.dryRun') }}
        </el-button>
        <el-button v-if="perm.can(MENU.GRAPH_VALIDATE)" :icon="Share" @click="emit('topology')" size="small">
          {{ t('toolbar.topology') }}
        </el-button>
        <el-button
          v-if="!readOnly"
          :type="editor.conditionalDrawMode ? 'warning' : 'default'"
          :icon="Switch"
          :plain="!editor.conditionalDrawMode"
          :title="t('toolbar.conditionalEdgeHint')"
          size="small"
          @click="editor.conditionalDrawMode = !editor.conditionalDrawMode"
        >
          {{ t('toolbar.conditionalEdge') }}
        </el-button>
        <el-button
          v-if="!readOnly"
          :type="canvasRef?.selectionSelectActive ? 'warning' : 'default'"
          :icon="Crop"
          :plain="!canvasRef?.selectionSelectActive"
          :title="t('toolbar.boxSelectHint')"
          size="small"
          @click="emit('toggleBoxSelect')"
        >
          {{ t('toolbar.boxSelect') }}
        </el-button>
        <el-button
          v-if="!readOnly"
          :icon="FolderOpened"
          size="small"
          :title="t('toolbar.groupHint')"
          @click="emit('createGroup')"
        >
          {{ t('toolbar.group') }}
        </el-button>
        <el-button
          v-if="!readOnly"
          :icon="Crop"
          size="small"
          :title="t('toolbar.extractSubgraphHint')"
          @click="emit('extractSubgraph')"
        >
          {{ t('toolbar.extractSubgraph') }}
        </el-button>
        <!-- TODO: 自动布局功能待完善，暂时隐藏 -->
        <!-- <el-button v-if="!readOnly && perm.can(MENU.GRAPH_VALIDATE)" :icon="MagicStick" size="small" @click="emit('autoLayout')">
          {{ t('toolbar.autoLayout') }}
        </el-button> -->
        <el-button-group v-if="perm.can(MENU.GRAPH_VIEW)">
          <el-button :icon="ZoomIn" :title="t('toolbar.zoomIn')" size="small" @click="emit('zoomIn')" />
          <el-button :icon="ZoomOut" :title="t('toolbar.zoomOut')" size="small" @click="emit('zoomOut')" />
          <el-button :icon="FullScreen" :title="t('toolbar.fitView')" size="small" @click="emit('fit')" />
          <el-button :icon="ScaleToOriginal" :title="t('toolbar.resetZoom')" size="small" @click="emit('resetZoom')" />
          <el-button :icon="MapLocation" :title="editor.minimapVisible ? t('toolbar.minimapHide') : t('toolbar.minimapShow')" size="small" @click="emit('toggleMinimap')" />
        </el-button-group>
        <el-button v-if="perm.can(MENU.GRAPH_VIEW)" :icon="Download" size="small" @click="emit('exportDsl')">
          {{ t('toolbar.export') }}
        </el-button>
        <el-button v-if="!readOnly && perm.can(MENU.GRAPH_SAVE)" :icon="Upload" size="small" @click="emit('importDsl')">
          {{ t('toolbar.import') }}
        </el-button>
        <el-button v-if="perm.can(MENU.GRAPH_VIEW)" size="small" @click="showVersionHistory = true">
          {{ t('toolbar.versionHistory') }}
        </el-button>
      </el-button-group>
      <el-tag v-if="readOnly" type="warning" effect="plain" size="small">{{ t('toolbar.readOnly') || '只读' }}</el-tag>
      </div>
      <span v-if="floatingActions" class="toolbar-drag-handle toolbar-drag-handle--end" :title="t('toolbar.dragHint') || '拖动移动'" @mousedown="onDragStart">
        <Rank />
      </span>
    </div>

    <VersionHistoryDrawer v-model:visible="showVersionHistory" :canvas-ref="canvasRef" />
  </div>
</template>

<style scoped>
.toolbar-root {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: var(--agd-spacing-toolbar, 8px 16px);
  border-bottom: 1px solid var(--agd-color-border, #e4e7ed);
  background: var(--agd-color-bg-toolbar, #fff);
  flex-shrink: 0;
}
.toolbar-root--floating-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  border: none;
  background: transparent;
  padding: 0;
  z-index: 5;
}
.toolbar-meta-shell:not(.toolbar-meta-shell--badge) {
  display: contents;
}
.toolbar-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
  pointer-events: auto;
}
.toolbar-meta-shell--badge {
  --badge-bevel: 14px;
  position: absolute;
  top: 1px;
  left: 1px;
  display: inline-block;
  max-width: calc(100% - 88px);
  pointer-events: none;
  z-index: 1;
  overflow: visible;
}
.toolbar-meta-shell--badge::after {
  content: '';
  position: absolute;
  top: 0;
  left: 100%;
  width: 999px;
  height: 0;
  border-top: 1.25px solid rgba(64, 158, 255, 0.55);
  pointer-events: none;
}
.toolbar-meta--badge {
  --badge-bevel: 14px;
  --badge-bevel-rise: 24px;
  --badge-clip: polygon(
    0 0,
    100% 0,
    100% calc(100% - var(--badge-bevel-rise)),
    calc(100% - var(--badge-bevel)) 100%,
    0 100%
  );
  position: relative;
  top: auto;
  left: auto;
  flex: none;
  box-sizing: border-box;
  width: max-content;
  max-width: min(320px, 100%);
  min-width: 168px;
  min-height: 58px;
  padding: 10px calc(var(--badge-bevel) + 8px) 12px 14px;
  isolation: isolate;
  overflow: visible;
  clip-path: var(--badge-clip);
  background: transparent;
  pointer-events: auto;
  filter:
    drop-shadow(0 2px 8px rgba(64, 158, 255, 0.18))
    drop-shadow(0 4px 14px rgba(0, 0, 0, 0.06));
}
.toolbar-meta--badge::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  clip-path: var(--badge-clip);
  background: linear-gradient(
      180deg,
      rgba(255, 255, 255, 0.55) 0%,
      transparent 38%
    ),
    linear-gradient(
      145deg,
      var(--agd-color-bg-active, #ecf5ff) 0%,
      rgba(255, 255, 255, 0.98) 52%,
      rgba(255, 255, 255, 0.94) 100%
    );
  pointer-events: none;
}
.toolbar-meta-badge-outline {
  position: absolute;
  inset: 0;
  z-index: 2;
  width: 100%;
  height: 100%;
  overflow: visible;
  pointer-events: none;
}
.toolbar-meta--badge .toolbar-title {
  position: relative;
  z-index: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
  color: var(--agd-color-primary, #409eff);
}
.toolbar-meta--badge .version-status {
  position: relative;
  z-index: 1;
  max-width: 100%;
}
.version-status {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.toolbar-title {
  font-size: var(--agd-font-size-title, 14px);
  font-weight: 600;
  color: var(--agd-color-text, #303133);
}
.toolbar-actions {
  flex-shrink: 0;
  pointer-events: auto;
}
.toolbar-actions--floating {
  position: absolute;
  left: 50%;
  bottom: 20px;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--agd-color-border, #e4e7ed);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  cursor: default;
}
.toolbar-actions-body {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
}
.toolbar-actions--floating.is-dragging {
  cursor: grabbing;
  box-shadow: 0 6px 24px rgba(0, 0, 0, 0.18);
  opacity: 0.92;
}
.toolbar-drag-handle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-right: 6px;
  color: #909399;
  font-size: 12px;
  cursor: grab;
  vertical-align: middle;
  flex-shrink: 0;
}
.toolbar-drag-handle--end {
  margin-right: 0;
  margin-left: 6px;
}
.toolbar-drag-handle:hover {
  color: #409eff;
}
.toolbar-drag-handle:active {
  cursor: grabbing;
}
</style>
