import { reactive } from 'vue'

// 跨组件触发「节点面板 → 通用 Agent」编辑器。
// 场景：在属性面板选中一个「注册式（引用型）通用 Agent」节点时，
// 其元数据集中在节点面板管理，属性面板仅展示引用并提供「前往编辑」按钮。
const state = reactive({
  requestId: 0,
  nodeId: null,
  /** 图入口打开时带上的当前图 id */
  graphId: null,
  /** 图入口：embed.agentCode 初值 */
  agentCode: null,
  /** 图入口：静默 otherBizParams（不展示） */
  otherBizParams: null
})

/**
 * 请求节点面板打开指定 nodeId 的通用 Agent 编辑器。
 * @param {string|null} nodeId
 * @param {{ graphId?: string, agentCode?: string, otherBizParams?: string }} [ctx] 图入口上下文
 */
export function requestOpenAgentEditor(nodeId, ctx = {}) {
  state.nodeId = nodeId || null
  state.graphId = ctx.graphId || null
  state.agentCode = ctx.agentCode || null
  state.otherBizParams = ctx.otherBizParams || null
  state.requestId++
}

/** 在节点面板中订阅该请求 */
export function useAgentEditorBus() {
  return state
}
