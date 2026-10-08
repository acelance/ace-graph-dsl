/**
 * SAA_WORKFLOW 节点默认规格与工具（M2：四 pattern）。
 */

/** 已开放的 pattern 列表（与后端 OPEN_PATTERNS 对齐） */
export const SAA_PATTERNS = ['SEQUENTIAL', 'PARALLEL', 'ROUTING', 'LOOP']

/** 默认子 Agent 行 */
export function defaultSubAgent(index = 1) {
  return {
    name: `agent_${index}`,
    impl: 'GENERIC_AGENT',
    ref: '',
    instruction: '{user_query}',
    outputKey: `step${index}`
  }
}

/**
 * 拖入画布时的默认 saaSpec。
 * @returns {object}
 */
export function defaultSaaSpec() {
  return {
    pattern: 'SEQUENTIAL',
    inputKeys: 'user_query',
    outputKey: 'agent_result',
    streamResponseKind: 'BIZ',
    modelConfigKey: '',
    maxIterations: null,
    exitConditionKey: '',
    exitConditionOp: '',
    exitConditionValue: '',
    subAgents: [defaultSubAgent(1)]
  }
}

/**
 * 从子 Agent ref 解析注册中心 nodeId（generic: / agentscope: 前缀均可）。
 * @param {string|null|undefined} ref
 * @returns {string}
 */
export function parseSubAgentRegisteredId(ref) {
  const raw = (ref || '').trim()
  if (!raw) return ''
  if (raw.startsWith('generic:')) return raw.slice('generic:'.length).trim()
  if (raw.startsWith('agentscope:')) return raw.slice('agentscope:'.length).trim()
  return raw
}

/**
 * 浅拷贝并规范化 saaSpec（保证 subAgents 为数组）。
 * @param {object|null|undefined} spec
 * @returns {object|null}
 */
export function normalizeSaaSpec(spec) {
  if (!spec || typeof spec !== 'object') return null
  const subAgents = Array.isArray(spec.subAgents)
    ? spec.subAgents.map((s, i) => ({
        name: s?.name || `agent_${i + 1}`,
        impl: s?.impl || 'GENERIC_AGENT',
        ref: s?.ref || '',
        instruction: s?.instruction || '',
        outputKey: s?.outputKey || `step${i + 1}`
      }))
    : []
  return {
    pattern: spec.pattern || 'SEQUENTIAL',
    inputKeys: spec.inputKeys || '',
    outputKey: spec.outputKey || 'agent_result',
    streamResponseKind: spec.streamResponseKind || '',
    modelConfigKey: spec.modelConfigKey || '',
    maxIterations: spec.maxIterations ?? null,
    exitConditionKey: spec.exitConditionKey || '',
    exitConditionOp: spec.exitConditionOp || '',
    exitConditionValue: spec.exitConditionValue || '',
    subAgents
  }
}
