/**
 * 加载节点业务附加参数解释器下拉选项。
 * 失败时回落默认 string，避免设计器不可用。
 */
import { listNodeBizParamInterpreters } from '../api/graph'

const FALLBACK = [
  { id: 'string', displayName: '纯文本（默认）', order: 0 }
]

/**
 * @param {string} [graphId]
 * @returns {Promise<{id:string,displayName:string,order:number}[]>}
 */
export async function loadBizParamInterpreterOptions(graphId) {
  try {
    const items = await listNodeBizParamInterpreters(graphId)
    if (Array.isArray(items) && items.length) {
      return [...items].sort(
        (a, b) => (a.order ?? 0) - (b.order ?? 0) || String(a.id).localeCompare(String(b.id))
      )
    }
  } catch (e) {
    console.warn('[bizParamInterpreters] 加载失败，使用默认 string', e)
  }
  return FALLBACK
}
