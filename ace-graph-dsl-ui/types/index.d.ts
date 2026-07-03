/**
 * Type declarations for @acelance/graph-dsl-ui
 *
 * @packageDocumentation
 */

import type { DefineComponent, Ref } from 'vue'
import type { AxiosInstance, AxiosInterceptorManager } from 'axios'

// ============================================================================
// API: api/graph.js
// ============================================================================

/** createGraphApi 的配置选项 */
export interface GraphApiOptions {
  /** 后端 API 根路径，默认 '/' */
  baseURL?: string
  /** 设计器 API 前缀，默认 '/api/graph'，对齐后端 ace.graph.dsl.web.base-path */
  apiPrefix?: string
  /** 宿主已有的 axios 实例（复用拦截器/鉴权），优先于内部新建 */
  instance?: AxiosInstance
  /** 附加默认请求头（如 Authorization、X-Tenant-Id、traceId） */
  headers?: Record<string, string>
  /** 请求拦截器 */
  requestInterceptor?:
    | ((config: any) => any)
    | [(config: any) => any, (error: any) => any]
  /** 响应拦截器 */
  responseInterceptor?:
    | ((response: any) => any)
    | [(response: any) => any, (error: any) => any]
}

/** 脚本节点创建/更新请求体 */
export interface CreateScriptNodeBody {
  nodeId: string
  displayName: string
  category?: 'NORMAL' | 'ROUTER'
  description?: string
  inputKeys: string[]
  outputKeys: string[]
  engine: string
  scriptBody: string
  permissionTags?: string[]
  supportsParallel?: boolean
  version?: string
  operator?: string
}

/** 脚本校验请求体 */
export interface ValidateScriptBody {
  engine: string
  scriptBody: string
}

/** 引擎元数据（GET /script/engines 返回） */
export interface EngineMeta {
  id: string
  label: string
}

/** 脚本试跑请求体 */
export interface TestRunBody {
  engine: string
  scriptBody: string
  inputKeys: string[]
  outputKeys: string[]
  mockState: Record<string, any>
  config: Record<string, any>
}

/** 保存草稿请求体 */
export interface SaveDraftPayload {
  definition: Record<string, any>
  baseVersion: string
}

/** 发布/回滚请求体 */
export interface PublishRollbackPayload {
  version: string
  operator?: string
}

/** 菜单权限视图（GET /permissions/menus 返回） */
export interface MenuPermissionView {
  principal: { id: string; name: string; roles: string[] } | null
  menus: { key: string; label: string; group: string; permitted: boolean }[]
  grantedKeys: string[]
}

/** 节点描述符（listNodes 返回的数组元素） */
export interface NodeDescriptor {
  nodeId: string
  displayName?: string
  category?: string
  origin?: 'BUILTIN' | 'SCRIPT'
  inputKeys?: string[]
  outputKeys?: string[]
}

/** 图定义摘要（listSummaries 返回的数组元素） */
export interface GraphSummary {
  graphId: string
  displayName?: string
  latestVersion?: string
  enabledVersion?: string
}

/** createGraphApi 返回的 API 客户端实例 */
export interface GraphApi {
  /** 内部 axios 实例 */
  http: AxiosInstance

  /** 列出可用节点（origin: 'ALL' | 'BUILTIN' | 'SCRIPT'） */
  listNodes: (origin?: string) => Promise<NodeDescriptor[]>
  /** 列出可用条件边 Dispatcher */
  listDispatchers: () => Promise<any[]>
  /** 列出所有脚本节点定义 */
  listScriptNodeDefinitions: () => Promise<any[]>
  /** 获取单个脚本节点定义 */
  getScriptNodeDefinition: (nodeId: string) => Promise<any>
  /** 列出可用脚本引擎 */
  listScriptEngines: () => Promise<EngineMeta[]>
  /** 创建脚本节点 */
  createScriptNode: (body: CreateScriptNodeBody) => Promise<any>
  /** 更新脚本节点 */
  updateScriptNode: (nodeId: string, body: Partial<CreateScriptNodeBody>) => Promise<any>
  /** 删除脚本节点 */
  deleteScriptNode: (nodeId: string) => Promise<any>
  /** 校验脚本语法 */
  validateScript: (body: ValidateScriptBody) => Promise<any>
  /** 试跑已存脚本节点 */
  testRunScriptNode: (nodeId: string, body: TestRunBody) => Promise<{ output: any }>
  /** 试跑草稿脚本 */
  testRunDraft: (body: TestRunBody) => Promise<{ output: any }>

  /** 列出所有图定义 */
  listDefinitions: () => Promise<any[]>
  /** 列出所有图 ID */
  listGraphIds: () => Promise<string[]>
  /** 列出图摘要信息 */
  listSummaries: () => Promise<GraphSummary[]>
  /** 获取最新图定义 */
  getLatestDefinition: (graphId: string) => Promise<any>
  /** 列出图的版本列表 */
  listVersions: (graphId: string) => Promise<{ version: string; status: string; createdAt: string }[]>
  /** 获取指定版本图定义 */
  getVersion: (graphId: string, version: string) => Promise<any>
  /** 保存草稿 */
  saveDraft: (
    graphId: string,
    def: Record<string, any>,
    baseVersion?: string
  ) => Promise<{ success?: boolean; changed?: boolean; definition?: any }>
  /** 校验图定义 */
  validateDefinition: (graphId: string, def: Record<string, any>) => Promise<{ errors?: any[] }>
  /** 预览 PlantUML */
  previewPlantUml: (graphId: string, def: Record<string, any>) => Promise<string>
  /** 预览 Mermaid */
  previewMermaid: (graphId: string, def: Record<string, any>) => Promise<string>
  /** 发布 */
  publish: (graphId: string, version: string, operator?: string) => Promise<{ success: boolean }>
  /** 回滚 */
  rollback: (graphId: string, version: string, operator?: string) => Promise<{ success: boolean }>
  /** 获取当前启用版本 */
  getEnabled: (graphId: string) => Promise<any>
  /** 获取菜单权限视图 */
  getMenuPermissions: () => Promise<MenuPermissionView>
}

/**
 * 创建 Graph DSL API 客户端。
 *
 * @example
 * // 简单用法
 * const api = createGraphApi('/my-base')
 *
 * @example
 * // 高级用法：注入宿主 axios + 鉴权
 * const api = createGraphApi({
 *   instance: hostAxios,
 *   apiPrefix: '/platform/graph-dsl',
 *   headers: { 'X-Tenant-Id': tenantId }
 * })
 */
export function createGraphApi(options?: string | GraphApiOptions): GraphApi

/**
 * 重新配置默认 API 实例（影响所有通过命名导出调用的方法）。
 * 宿主可在应用启动时调用一次，注入自有 axios 实例/鉴权头/拦截器。
 */
export function configureGraphApi(options: string | GraphApiOptions): GraphApi

/** 获取当前默认 API 实例 */
export function getGraphApi(): GraphApi

// 命名导出的便捷方法（委托到默认 API 实例）
export const listNodes: GraphApi['listNodes']
export const listDispatchers: GraphApi['listDispatchers']
export const createScriptNode: GraphApi['createScriptNode']
export const updateScriptNode: GraphApi['updateScriptNode']
export const deleteScriptNode: GraphApi['deleteScriptNode']
export const validateScript: GraphApi['validateScript']
export const testRunDraft: GraphApi['testRunDraft']
export const testRunScriptNode: GraphApi['testRunScriptNode']
export const getScriptNodeDefinition: GraphApi['getScriptNodeDefinition']
export const listScriptNodeDefinitions: GraphApi['listScriptNodeDefinitions']
export const listScriptEngines: GraphApi['listScriptEngines']
export const listDefinitions: GraphApi['listDefinitions']
export const listGraphIds: GraphApi['listGraphIds']
export const listSummaries: GraphApi['listSummaries']
export const getLatestDefinition: GraphApi['getLatestDefinition']
export const listVersions: GraphApi['listVersions']
export const getVersion: GraphApi['getVersion']
export const saveDraft: GraphApi['saveDraft']
export const validateDefinition: GraphApi['validateDefinition']
export const previewPlantUml: GraphApi['previewPlantUml']
export const previewMermaid: GraphApi['previewMermaid']
export const publish: GraphApi['publish']
export const rollback: GraphApi['rollback']
export const getEnabled: GraphApi['getEnabled']
export const getMenuPermissions: GraphApi['getMenuPermissions']

// ============================================================================
// i18n: i18n/index.js
// ============================================================================

/** configureGraphDslI18n 的选项 */
export interface GraphDslI18nOptions {
  /** 语言代码，如 'zh-CN' | 'en-US' */
  locale?: string
  /** 自定义覆盖消息，格式为 { 'zh-CN': { 'toolbar.save': '保存' } } */
  messages?: Record<string, Record<string, string>>
}

/**
 * 配置 Graph DSL UI 国际化。
 * 不强制 peer vue-i18n，内置 zh-CN + en-US 默认包。
 */
export function configureGraphDslI18n(options?: GraphDslI18nOptions): void

/** 获取当前 locale 字符串 */
export function getGraphDslLocale(): string

/**
 * 翻译文案，支持 {param} 占位符。
 * @example t('toolbar.save')   // → "保存草稿"
 * @example t('toolbar.saveFailed', { msg: '网络错误' })  // → "保存失败: 网络错误"
 */
export function t(key: string, params?: Record<string, any>): string

/**
 * Vue 组合式 API：locale 变化时模板自动更新。
 * @returns { locale: Ref<string>, t: (key: string, params?: Record<string, any>) => string }
 */
export function useI18n(): {
  locale: Ref<string>
  t: (key: string, params?: Record<string, any>) => string
}

// ============================================================================
// Stores: stores/permissions.js
// ============================================================================

/** 标准菜单权限 key（与后端 GraphMenuPermissions 对齐） */
export const MENU: {
  readonly GRAPH_VIEW: 'graph:view'
  readonly GRAPH_CREATE: 'graph:create'
  readonly GRAPH_SAVE: 'graph:save'
  readonly GRAPH_VALIDATE: 'graph:validate'
  readonly GRAPH_PREVIEW: 'graph:preview'
  readonly GRAPH_PUBLISH: 'graph:publish'
  readonly GRAPH_ROLLBACK: 'graph:rollback'
  readonly SCRIPT_NODE_VIEW: 'script-node:view'
  readonly SCRIPT_NODE_CREATE: 'script-node:create'
  readonly SCRIPT_NODE_DELETE: 'script-node:delete'
  readonly SCRIPT_NODE_TEST: 'script-node:test'
}

/** 菜单权限 Store */
export function usePermissionStore(): {
  grantedKeys: Ref<Set<string>>
  menus: Ref<MenuPermissionView['menus']>
  principal: Ref<MenuPermissionView['principal']>
  loaded: Ref<boolean>
  failOpen: Ref<boolean>
  load: () => Promise<void>
  can: (key: string) => boolean
  MENU: typeof MENU
}

// ============================================================================
// Stores: stores/graphEditor.js
// ============================================================================

/** 图编辑器 Store */
export function useGraphEditorStore(): {
  // 状态
  graphId: Ref<string>
  version: Ref<string>
  displayName: Ref<string>
  description: Ref<string>
  keyStrategies: Record<string, string>
  nodes: Ref<{ nodeId: string; config?: Record<string, any> }[]>
  edges: Ref<{ from: string; to?: string; type: 'normal' | 'conditional'; dispatcher?: string; mapping?: Record<string, string> }[]>
  interruptBefore: Ref<string[]>
  saver: Ref<string>
  validationErrors: Ref<any[]>
  plantUmlContent: Ref<string>
  versions: Ref<{ version: string; status: string; createdAt: string }[]>
  enabledVersion: Ref<string>
  baselineVersion: Ref<string>
  saving: Ref<boolean>
  publishing: Ref<boolean>
  selectedNode: Ref<{ nodeId: string; config: Record<string, any> } | null>
  selectedLfNodeId: Ref<string | null>

  // LogicFlow 数据 -> Store
  setFromLfData: (lfData: { nodes: any[]; edges: any[] }, nodeDescriptors: NodeDescriptor[]) => void
  // 加载图定义到编辑器
  applyDefinition: (def: Record<string, any>) => Record<string, any>
  normalizeDefinition: (def: Record<string, any>) => Record<string, any>
  // 构建完整 GraphDefinition
  buildDefinition: () => Record<string, any>
  // 保存草稿
  save: () => Promise<{ ok: boolean; unchanged?: boolean; result?: any }>
  // 校验
  validate: () => Promise<{ errors?: any[] }>
  // 生成 PlantUML 预览
  loadPlantUml: () => Promise<void>
  // 发布当前版本
  publishCurrent: (operator?: string) => Promise<{ success: boolean; message?: string }>
  // 版本控制
  hasContentChanged: () => boolean
  needsVersionBump: () => boolean
  suggestNextVersion: () => string
  snapshotBaseline: (def?: Record<string, any>) => void
  loadVersionAsBaseline: (def: Record<string, any>) => Record<string, any>
  maxKnownVersion: () => string | undefined
  versionExists: (target: string) => boolean

  // 数据加载
  loadLatest: () => Promise<Record<string, any> | null>
  loadEnabledVersion: () => Promise<Record<string, any> | null>
  fetchVersions: () => Promise<void>
  selectGraph: (id: string) => void
  initNewGraph: (id: string, meta?: Record<string, any>) => void
  resetEditor: () => void

  // 选中节点
  setSelectedNode: (lfNodeId: string, nodeId: string, config?: Record<string, any>) => void
  clearSelectedNode: () => void
  updateSelectedNodeConfig: (config: Record<string, any>) => void
}

// ============================================================================
// Stores: stores/nodeRegistry.js
// ============================================================================

/** 节点注册中心 Store */
export function useNodeRegistryStore(): {
  nodes: Ref<NodeDescriptor[]>
  dispatchers: Ref<any[]>
  loading: Ref<boolean>
  fetchNodes: () => Promise<void>
  fetchDispatchers: () => Promise<void>
}

// ============================================================================
// Utils: utils/graphDiff.js
// ============================================================================

/** diffByKey 返回的差异对象 */
export interface DiffResult {
  added: { key: string; item: any }[]
  removed: { key: string; item: any }[]
  modified: { key: string; before: any; after: any }[]
}

/** diffGraphStructure 返回的完整结构差异 */
export interface StructureDiff {
  nodes: DiffResult
  edges: DiffResult
  compile: { key: string; before: any; after: any }[]
  meta: { key: string; before: any; after: any }[]
}

/**
 * 按 key 对比两个数组，返回增删改。
 */
export function diffByKey(baseList: any[] | null | undefined, targetList: any[] | null | undefined, keyFn: (item: any) => string): DiffResult

/**
 * 对比两份 GraphDefinition 的结构差异。
 */
export function diffGraphStructure(base: Record<string, any> | null | undefined, target: Record<string, any> | null | undefined): StructureDiff

/**
 * 是否有结构性差异（节点/边/编译配置/元信息任一变更）。
 */
export function hasStructuralDiff(diff: StructureDiff | null | undefined): boolean

/**
 * 格式化 GraphDefinition 为缩进 JSON 字符串。
 */
export function formatDefinitionJson(def: Record<string, any>): string

// ============================================================================
// Vue 组件: components/*.vue
// ============================================================================

/**
 * 设计器组件（画布 + 工具栏 + 节点面板 + 属性面板 + 版本历史）。
 */
export const GraphDslDesigner: DefineComponent<
  {
    locale?: string
    title?: string
  },
  {},
  {}
>

/**
 * 管理中心组件（图目录列表 + 设计器视图）。
 */
export const GraphDslManager: DefineComponent<{}, {}, {}>

/**
 * 工具栏组件（保存/校验/预览/发布/版本历史按钮）。
 */
export const DesignerToolbar: DefineComponent<{}, {}, {}>

/**
 * 节点面板组件（可拖拽节点列表 + 脚本节点管理入口）。
 */
export const DesignerNodePanel: DefineComponent<{}, {}, {}>

/**
 * 画布组件（LogicFlow 渲染容器）。
 */
export const DesignerCanvas: DefineComponent<{}, {}, {}>

/**
 * 属性面板组件（节点配置/图元信息/编译配置/校验结果/PlantUML 视图）。
 */
export const DesignerPropertyPanel: DefineComponent<{}, {}, {}>

/**
 * 版本历史抽屉（已发布/草稿历史 + 回滚操作）。
 */
export const DesignerVersionHistoryDrawer: DefineComponent<{}, {}, {}>

/**
 * 版本差异对比面板（结构 diff + JSON 行级 diff Tab）。
 */
export const DesignerVersionDiffPanel: DefineComponent<{}, {}, {}>

// ============================================================================
// 样式（仅声明，实际由 style.css 提供）
// ============================================================================

/**
 * CSS Token 变量（--agd-color-*, --agd-spacing-*, --agd-font-size-*）。
 * 通过 import '@acelance/graph-dsl-ui/style' 引入。
 */
export {}

// ============================================================================
// 内部 Graphics Context 兼容声明（不对外暴露，仅供内部类型推导）
// ============================================================================

declare module '@acelance/graph-dsl-ui' {
  export {
    createGraphApi,
    configureGraphApi,
    getGraphApi,
    listNodes,
    listDispatchers,
    createScriptNode,
    updateScriptNode,
    deleteScriptNode,
    validateScript,
    testRunDraft,
    testRunScriptNode,
    getScriptNodeDefinition,
    listScriptNodeDefinitions,
    listScriptEngines,
    listDefinitions,
    listGraphIds,
    listSummaries,
    getLatestDefinition,
    listVersions,
    getVersion,
    saveDraft,
    validateDefinition,
    previewPlantUml,
    previewMermaid,
    publish,
    rollback,
    getEnabled,
    getMenuPermissions,
    configureGraphDslI18n,
    getGraphDslLocale,
    t,
    useI18n,
    MENU,
    usePermissionStore,
    useGraphEditorStore,
    useNodeRegistryStore,
    diffByKey,
    diffGraphStructure,
    hasStructuralDiff,
    formatDefinitionJson,
    GraphDslDesigner,
    GraphDslManager,
    DesignerToolbar,
    DesignerNodePanel,
    DesignerCanvas,
    DesignerPropertyPanel,
    DesignerVersionHistoryDrawer,
    DesignerVersionDiffPanel,
  }
}
