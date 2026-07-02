import './tokens.css'

export { createGraphApi, configureGraphApi, getGraphApi } from './api/graph'
export * from './api/graph'

export { configureGraphDslI18n, getGraphDslLocale, t, useI18n } from './i18n'

export { useGraphEditorStore } from './stores/graphEditor'
export { useNodeRegistryStore } from './stores/nodeRegistry'
export { usePermissionStore, MENU } from './stores/permissions'

export { default as GraphDslDesigner } from './components/GraphDslDesigner.vue'
export { default as GraphDslManager } from './components/GraphDslManager.vue'

export { default as DesignerToolbar } from './components/Designer/Toolbar.vue'
export { default as DesignerNodePanel } from './components/Designer/NodePanel.vue'
export { default as DesignerCanvas } from './components/Designer/Canvas.vue'
export { default as DesignerPropertyPanel } from './components/Designer/PropertyPanel.vue'
export { default as DesignerVersionHistoryDrawer } from './components/Designer/VersionHistoryDrawer.vue'
export { default as DesignerVersionDiffPanel } from './components/Designer/VersionDiffPanel.vue'

export { diffGraphStructure, hasStructuralDiff, formatDefinitionJson } from './utils/graphDiff'
