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
    getMenuPermissions: () => http.get(`${p}/permissions/menus`).then(r => r.data)
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
export const getMenuPermissions = (...args) => defaultApi.getMenuPermissions(...args)
