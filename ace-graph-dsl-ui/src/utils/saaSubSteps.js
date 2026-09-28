/**
 * SAA 子步骤轨迹工具（与后端 SaaWorkflowKeys 对齐；M4 不改 SSE）。
 */

export const SAA_SUB_STEPS_KEY = 'ace.graph.dsl.saa.subSteps'
export const SAA_SUB_STEPS_META_KEY = 'ace.graph.dsl.saa.subStepsMeta'

/**
 * 从节点 state 提取子步骤列表。
 * @param {object|null|undefined} state
 * @returns {Array<object>}
 */
export function extractSaaSubSteps(state) {
  if (!state || typeof state !== 'object') return []
  const raw = state[SAA_SUB_STEPS_KEY]
  return Array.isArray(raw) ? raw : []
}
