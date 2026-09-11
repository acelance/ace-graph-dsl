import axios from 'axios'

/**
 * 创建 Graph DSL API 客户端。
 *
 * 支持两种调用方式（向后兼容）：
 *   createGraphApi('/my-base')                       // 传入 baseURL 字符串
 *   createGraphApi({ baseURL, instance, headers, requestInterceptor, responseInterceptor })
 *
 * @param {string|object} [options] baseURL 字符串，或配置对象：
 *   - baseURL {string}            后端 API 根路径，默认 '/'
 *   - instance {AxiosInstance}    宿主已有的 axios 实例（用于复用拦截器 / 鉴权），优先于内部新建
 *   - headers {object}            附加默认请求头（如 Authorization、X-Tenant-Id、traceId）
 *   - requestInterceptor {function|[onFulfilled, onRejected]}  请求拦截器
 *   - responseInterceptor {function|[onFulfilled, onRejected]} 响应拦截器
 */
export function createGraphApi(options = '/') {
  const opts = typeof options === 'string' ? { baseURL: options } : (options || {})
  const {
    baseURL = '/',
    apiPrefix = '/api/graph',
    instance,
    headers,
    requestInterceptor,
    responseInterceptor
  } = opts

  const http = instance || axios.create({ baseURL })
  const p = String(apiPrefix).replace(/\/+$/, '')

  if (headers) {
    Object.assign(http.defaults.headers.common, headers)
  }
  if (requestInterceptor) {
    const [onOk, onErr] = Array.isArray(requestInterceptor) ? requestInterceptor : [requestInterceptor]
    http.interceptors.request.use(onOk, onErr)
  }
  if (responseInterceptor) {
    const [onOk, onErr] = Array.isArray(responseInterceptor) ? responseInterceptor : [responseInterceptor]
    http.interceptors.response.use(onOk, onErr)
  }

  return {
    http,
    listNodes: (origin = 'ALL') => http.get(`${p}/nodes`, { params: { origin } }).then(r => r.data),
    listDispatchers: () => http.get(`${p}/dispatchers`).then(r => r.data),
    listScriptNodeDefinitions: () => http.get(`${p}/nodes/definitions`).then(r => r.data),
    getScriptNodeDefinition: (nodeId) => http.get(`${p}/nodes/definitions/${nodeId}`).then(r => r.data),
    listScriptEngines: () => http.get(`${p}/nodes/engines`).then(r => r.data),
    listReferringGraphs: (nodeId) => http.get(`${p}/nodes/references`, { params: { nodeId } }).then(r => r.data),
    createScriptNode: (body) => http.post(`${p}/nodes`, body).then(r => r.data),
    updateScriptNode: (nodeId, body) => http.put(`${p}/nodes/${nodeId}`, body).then(r => r.data),
    deleteScriptNode: (nodeId) => http.delete(`${p}/nodes/${nodeId}`).then(r => r.data),
    validateScript: (body) => http.post(`${p}/nodes/validate-script`, body).then(r => r.data),
    testRunScriptNode: (nodeId, body) => http.post(`${p}/nodes/${nodeId}/test-run`, body).then(r => r.data),
    testRunDraft: (body) => http.post(`${p}/nodes/test-run`, body).then(r => r.data),

    // ── 通用 Agent 节点（双通道：注册式定义 CRUD + 内联草稿校验/试跑）──
    listAgentDefinitions: () => http.get(`${p}/agents/definitions`).then(r => r.data),
    getAgentDefinition: (nodeId) => http.get(`${p}/agents/definitions/${nodeId}`).then(r => r.data),
    listAgentReferences: (nodeId) => http.get(`${p}/agents/references`, { params: { nodeId } }).then(r => r.data),
    listAgentOrphans: () => http.get(`${p}/agents/orphans`).then(r => r.data),
    createAgentNode: (body) => http.post(`${p}/agents`, body).then(r => r.data),
    updateAgentNode: (nodeId, body) => http.put(`${p}/agents/${nodeId}`, body).then(r => r.data),
    deleteAgentNode: (nodeId) => http.delete(`${p}/agents/${nodeId}`).then(r => r.data),
    validateAgentNode: (body) => http.post(`${p}/agents/validate`, body).then(r => r.data),
    testRunAgentDraft: (body) => http.post(`${p}/agents/test-run`, body).then(r => r.data),
    testRunAgent: (nodeId, body) => http.post(`${p}/agents/${nodeId}/test-run`, body).then(r => r.data),
    listDefinitions: () => http.get(`${p}/definitions`).then(r => r.data),
    listGraphIds: () => http.get(`${p}/catalog/graph-ids`).then(r => r.data),
    listSummaries: () => http.get(`${p}/catalog/summaries`).then(r => r.data),
    getLatestDefinition: (graphId) => http.get(`${p}/definitions/${graphId}`).then(r => r.data),
    listVersions: (graphId) => http.get(`${p}/definitions/${graphId}/versions`).then(r => r.data),
    getVersion: (graphId, version) => http.get(`${p}/definitions/${graphId}/versions/${version}`).then(r => r.data),
    saveDraft: (graphId, def, baseVersion) => http.post(`${p}/definitions/${graphId}/draft`, {
      definition: def,
      baseVersion: baseVersion || def?.version || ''
    }).then(r => r.data),
    validateDefinition: (graphId, def) => http.post(`${p}/definitions/${graphId}/validate`, def).then(r => r.data),
    previewPlantUml: (graphId, def) => http.post(`${p}/definitions/${graphId}/preview/plantuml`, def).then(r => r.data.content),
    previewMermaid: (graphId, def) => http.post(`${p}/definitions/${graphId}/preview/mermaid`, def).then(r => r.data.content),
    publish: (graphId, version, operator = 'designer') => http.post(`${p}/definitions/${graphId}/publish`, { version, operator }).then(r => r.data),
    rollback: (graphId, version, operator = 'designer') => http.post(`${p}/definitions/${graphId}/rollback`, { version, operator }).then(r => r.data),
    getEnabled: (graphId) => http.get(`${p}/definitions/${graphId}/enabled`).then(r => r.data),
    dryRunGraph: (graphId, definition, inputs) => http.post(`${p}/definitions/${graphId}/dry-run`, {
      definition,
      inputs: inputs || {}
    }).then(r => r.data),
    getMenuPermissions: () => http.get(`${p}/permissions/menus`).then(r => r.data),

    // ── 图执行 API（/execution/* 端点，需后端开启 ace.graph.dsl.web.execution.enabled=true）──

    /** 查询顶层图断点状态（HITL 暂停节点 + state 快照）。 */
    getExecutionState: (graphId, threadId) => http.get(`/execution/${graphId}/state/${threadId}`).then(r => r.data),

    /** 查询子图断点状态（G4 子图内 HITL）。 */
    getSubgraphState: (graphId, threadId, nodeId) => http.get(`/execution/${graphId}/state/${threadId}/subgraph/${nodeId}`).then(r => r.data),

    /**
     * 流式执行图（SSE），解析 SSE 事件并回调。
     * @param graphId 图 ID
     * @param inputs 输入 state
     * @param threadId 可选 threadId
     * @param handlers { onEvent, onSubgraphInterrupted, onError, onComplete }
     * @returns AbortController（可调 .abort() 取消）
     */
    streamGraph: (graphId, inputs, threadId, handlers = {}, options = {}) => {
      const body = {
        inputs: inputs || {},
        threadId,
        agentCode: options.agentCode || undefined
      }
      return streamSse(`/execution/${graphId}/stream`, body, handlers, http)
    },

    /**
     * 调试流式执行（SSE）：固定走框架 DebugStreamingChunkFormatter，与生产协议分离。
     * 需 graph:validate 权限。
     */
    debugStreamGraph: (graphId, inputs, threadId, handlers = {}, options = {}) => {
      const body = {
        inputs: inputs || {},
        threadId,
        agentCode: options.agentCode || undefined
      }
      return streamSse(`/execution/${graphId}/debug/stream`, body, handlers, http)
    },

    /**
     * 已实现流式响应类型目录（设计器下拉）。
     * @param graphId 可选，部分业务 Catalog 按图裁剪
     */
    listStreamResponseKinds: (graphId) =>
      http.get('/api/stream-response-kinds', { params: graphId ? { graphId } : {} }).then(r => r.data),

    /**
     * 设计期资源 Catalog（P1.1）。
     * @param {'prompts'|'models'|'tools'|'mcp'|'skills'} kind
     * @param {{ agentCode?: string, graphId?: string, agentDefId?: string }} params
     */
    listAgentResources: (kind, params = {}) =>
      http.get(`/api/agent-resources/${kind}`, { params }).then(r => r.data),

    /**
     * HITL 恢复执行（SSE）。
     * @param graphId 图 ID
     * @param threadId 父图 threadId
     * @param updates 写回 state
     * @param subgraphNodeId 子图节点 ID（子图内 HITL resume 时填写）
     * @param handlers { onEvent, onSubgraphInterrupted, onError, onComplete }
     * @returns AbortController
     */
    resumeGraph: (graphId, threadId, updates, subgraphNodeId, handlers = {}) => {
      const body = { threadId, updates: updates || {}, subgraphNodeId: subgraphNodeId || null }
      return streamSse(`/execution/${graphId}/resume`, body, handlers, http)
    }
  }
}

/** 默认 API 实例（可被 configureGraphApi 重新配置） */
let defaultApi = createGraphApi('/')

/**
 * 重新配置默认 API 实例（影响所有通过命名导出调用的方法）。
 *
 * 宿主可在应用启动时调用一次，以注入自有 axios 实例 / 鉴权头 / 拦截器，
 * 例如：configureGraphApi({ instance: hostAxios, headers: { Authorization: token } })
 *
 * @param {string|object} options 同 createGraphApi
 * @returns {object} 新的默认 API 实例
 */
export function configureGraphApi(options) {
  defaultApi = createGraphApi(options)
  return defaultApi
}

/** 获取当前默认 API 实例 */
export function getGraphApi() {
  return defaultApi
}

export const listNodes = (...args) => defaultApi.listNodes(...args)
export const listDispatchers = (...args) => defaultApi.listDispatchers(...args)
export const createScriptNode = (...args) => defaultApi.createScriptNode(...args)
export const updateScriptNode = (...args) => defaultApi.updateScriptNode(...args)
export const deleteScriptNode = (...args) => defaultApi.deleteScriptNode(...args)
export const listReferringGraphs = (...args) => defaultApi.listReferringGraphs(...args)
export const validateScript = (...args) => defaultApi.validateScript(...args)
export const testRunDraft = (...args) => defaultApi.testRunDraft(...args)
export const testRunScriptNode = (...args) => defaultApi.testRunScriptNode(...args)
export const listAgentDefinitions = (...args) => defaultApi.listAgentDefinitions(...args)
export const getAgentDefinition = (...args) => defaultApi.getAgentDefinition(...args)
export const listAgentReferences = (...args) => defaultApi.listAgentReferences(...args)
export const listAgentOrphans = (...args) => defaultApi.listAgentOrphans(...args)
export const createAgentNode = (...args) => defaultApi.createAgentNode(...args)
export const updateAgentNode = (...args) => defaultApi.updateAgentNode(...args)
export const deleteAgentNode = (...args) => defaultApi.deleteAgentNode(...args)
export const validateAgentNode = (...args) => defaultApi.validateAgentNode(...args)
export const testRunAgentDraft = (...args) => defaultApi.testRunAgentDraft(...args)
export const testRunAgent = (...args) => defaultApi.testRunAgent(...args)
export const getScriptNodeDefinition = (...args) => defaultApi.getScriptNodeDefinition(...args)
export const listScriptNodeDefinitions = (...args) => defaultApi.listScriptNodeDefinitions(...args)
export const listScriptEngines = (...args) => defaultApi.listScriptEngines(...args)
export const listDefinitions = (...args) => defaultApi.listDefinitions(...args)
export const listGraphIds = (...args) => defaultApi.listGraphIds(...args)
export const listSummaries = (...args) => defaultApi.listSummaries(...args)
export const getLatestDefinition = (...args) => defaultApi.getLatestDefinition(...args)
export const listVersions = (...args) => defaultApi.listVersions(...args)
export const getVersion = (...args) => defaultApi.getVersion(...args)
export const saveDraft = (...args) => defaultApi.saveDraft(...args)
export const validateDefinition = (...args) => defaultApi.validateDefinition(...args)
export const previewPlantUml = (...args) => defaultApi.previewPlantUml(...args)
export const previewMermaid = (...args) => defaultApi.previewMermaid(...args)
export const publish = (...args) => defaultApi.publish(...args)
export const rollback = (...args) => defaultApi.rollback(...args)
export const getEnabled = (...args) => defaultApi.getEnabled(...args)
export const dryRunGraph = (...args) => defaultApi.dryRunGraph(...args)
export const getMenuPermissions = (...args) => defaultApi.getMenuPermissions(...args)
export const getExecutionState = (...args) => defaultApi.getExecutionState(...args)
export const getSubgraphState = (...args) => defaultApi.getSubgraphState(...args)
export const streamGraph = (...args) => defaultApi.streamGraph(...args)
export const debugStreamGraph = (...args) => defaultApi.debugStreamGraph(...args)
export const listStreamResponseKinds = (...args) => defaultApi.listStreamResponseKinds(...args)
export const listAgentResources = (...args) => defaultApi.listAgentResources(...args)
export const resumeGraph = (...args) => defaultApi.resumeGraph(...args)

/**
 * POST + SSE 流式解析：用 fetch 发起 POST 请求，解析 text/event-stream 响应。
 *
 * <p>SSE 事件格式：<code>event: xxx\ndata: {...}\n\n</code>。支持 named event
 *（如 <code>subgraph-interrupted</code>）与默认 data 事件。</p>
 *
 * @param url 请求 URL
 * @param body POST body（JSON 序列化）
 * @param handlers { onEvent, onSubgraphInterrupted, onError, onComplete }
 * @param http axios 实例（用于读取 baseURL / headers）
 * @returns AbortController（可调 .abort() 取消）
 */
function streamSse(url, body, handlers, http) {
  const controller = new AbortController()
  const { onEvent, onSubgraphInterrupted, onError, onComplete } = handlers

  // 从 axios 实例提取 baseURL + 鉴权头
  const baseURL = (http?.defaults?.baseURL) || '/'
  const commonHeaders = http?.defaults?.headers?.common || {}
  const fullUrl = baseURL.replace(/\/+$/, '') + url

  fetch(fullUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...commonHeaders },
    body: JSON.stringify(body),
    signal: controller.signal
  }).then(async (response) => {
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: ${response.statusText}`)
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 事件以 \n\n 分隔
      let sepIdx
      while ((sepIdx = buffer.indexOf('\n\n')) >= 0) {
        const rawEvent = buffer.slice(0, sepIdx)
        buffer = buffer.slice(sepIdx + 2)
        const parsed = parseSseEvent(rawEvent)
        if (!parsed) continue

        if (parsed.event === 'subgraph-interrupted') {
          onSubgraphInterrupted?.(parsed.data)
        } else {
          onEvent?.(parsed)
        }
      }
    }
    onComplete?.()
  }).catch((err) => {
    if (err.name === 'AbortError') return
    onError?.(err)
  })

  return controller
}

/** 解析单个 SSE 事件文本为 { event, data } 对象 */
function parseSseEvent(raw) {
  const lines = raw.split('\n')
  let event = 'message'
  let dataStr = ''
  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataStr += line.slice(5).trim()
    }
  }
  if (!dataStr) return null
  try {
    return { event, data: JSON.parse(dataStr) }
  } catch {
    return { event, data: dataStr }
  }
}
