<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import { listSummaries } from '../api/graph'
import { usePermissionStore, MENU } from '../stores/permissions'
import { configureGraphDslI18n, useI18n } from '../i18n'
import GraphDslDesigner from './GraphDslDesigner.vue'
import PropertyPanel from './Designer/PropertyPanel.vue'
import NodePanel from './Designer/NodePanel.vue'

const props = defineProps({
  title: { type: String, default: '' },
  apiBaseUrl: { type: String, default: '/' },
  locale: { type: String, default: 'zh-CN' }
})

const perm = usePermissionStore()
const { t } = useI18n()

watch(() => props.locale, (loc) => {
  if (loc) configureGraphDslI18n({ locale: loc })
}, { immediate: true })

const summaries = ref([])
const selectedGraphId = ref('')
const panelExpanded = ref(false)
const loading = ref(false)
const showCreate = ref(false)
const newGraphId = ref('')
const newDisplayName = ref('')
const designerRef = ref()

const displayTitle = () => props.title || t('manager.title')

const selectedSummary = computed(() =>
  summaries.value.find(s => s.graphId === selectedGraphId.value)
)

const selectedDisplayName = computed(() =>
  selectedSummary.value?.displayName || selectedGraphId.value || ''
)

const showDesigner = computed(() => Boolean(selectedGraphId.value && panelExpanded.value))

async function refreshCatalog() {
  loading.value = true
  try {
    summaries.value = await listSummaries()
    if (selectedGraphId.value && !summaries.value.some(s => s.graphId === selectedGraphId.value)) {
      selectedGraphId.value = ''
      panelExpanded.value = false
    }
  } finally {
    loading.value = false
  }
}

function toggleGraph(graphId) {
  if (selectedGraphId.value === graphId && panelExpanded.value) {
    selectedGraphId.value = ''
    panelExpanded.value = false
    return
  }
  selectedGraphId.value = graphId
  panelExpanded.value = true
}

function createGraph() {
  const id = newGraphId.value.trim()
  const displayName = newDisplayName.value.trim() || id
  if (!id) return
  selectedGraphId.value = id
  panelExpanded.value = true
  showCreate.value = false
  newGraphId.value = ''
  newDisplayName.value = ''
  if (!summaries.value.find(s => s.graphId === id)) {
    summaries.value = [{ graphId: id, displayName, version: '1.0.0' }, ...summaries.value]
  }
}

function isActive(graphId) {
  return selectedGraphId.value === graphId && panelExpanded.value
}

function onNodeDrag(descriptor) {
  designerRef.value?.onNodeDrag(descriptor)
}

onMounted(async () => {
  if (!perm.loaded) await perm.load()
  await refreshCatalog()
})
</script>

<template>
  <div class="ace-graph-dsl-manager">
    <aside class="left-dock">
      <div class="catalog-header">
        <h3>{{ displayTitle() }}</h3>
        <el-button size="small" @click="refreshCatalog" :loading="loading">{{ t('common.refresh') }}</el-button>
      </div>
      <el-button v-if="perm.can(MENU.GRAPH_CREATE)" type="primary" plain class="create-btn" @click="showCreate = true">
        {{ t('manager.createGraph') }}
      </el-button>

      <div v-loading="loading" class="catalog-scroll">
        <div v-if="summaries.length" class="catalog-list">
          <div v-for="item in summaries" :key="item.graphId" class="catalog-block">
            <div
              class="catalog-item-row"
              :class="{ active: isActive(item.graphId) }"
              @click="toggleGraph(item.graphId)"
            >
              <div class="catalog-item">
                <strong>{{ item.displayName || item.graphId }}</strong>
                <small>{{ item.graphId }} · v{{ item.version }}</small>
              </div>
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
        <el-empty v-else-if="!loading" :description="t('manager.emptyCatalog')" />
      </div>
    </aside>

    <main class="designer-panel">
      <template v-if="showDesigner">
        <GraphDslDesigner
          ref="designerRef"
          :key="`${selectedGraphId}-${locale}`"
          :graph-id="selectedGraphId"
          :title="selectedDisplayName"
          :api-base-url="apiBaseUrl"
          :locale="locale"
          @saved="refreshCatalog"
        />
        <NodePanel class="node-panel-right" @node-drag="onNodeDrag" />
      </template>
      <el-empty v-else :description="t('manager.selectOrCreate')" />
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
  pointer-events: none;
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
.designer-panel {
  flex: 1;
  overflow: hidden;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: row;
}
.node-panel-right {
  width: var(--agd-panel-width-left, 240px);
  flex-shrink: 0;
  border-left: 1px solid var(--agd-color-border, #e4e7ed);
  overflow-y: auto;
  background: var(--agd-color-bg, #fff);
}
.designer-panel > :first-child:not(.el-empty) {
  flex: 1;
  min-width: 0;
  min-height: 0;
}
</style>
