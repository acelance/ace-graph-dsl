/**
 * 加载流式响应类型下拉选项（P0.4 / §9.4）。
 * 失败时回落默认 BIZ/OUTPUT，避免设计器不可用。
 */
import { listStreamResponseKinds } from '../api/graph'

const FALLBACK = [
  { code: 'BIZ', label: '业务处理', order: 10 },
  { code: 'OUTPUT', label: '结果输出', order: 20 }
]

/**
 * @param {string} [graphId]
 * @returns {Promise<{code:string,label:string,order:number}[]>}
 */
export async function loadStreamKindOptions(graphId) {
  try {
    const items = await listStreamResponseKinds(graphId)
    if (Array.isArray(items) && items.length) {
      return [...items].sort((a, b) => (a.order ?? 0) - (b.order ?? 0) || String(a.code).localeCompare(String(b.code)))
    }
  } catch (e) {
    console.warn('[streamKinds] 加载失败，使用默认 BIZ/OUTPUT', e)
  }
  return FALLBACK
}
