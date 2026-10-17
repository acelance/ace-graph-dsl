/** GenericAgentSpec.memoryWrites 可选 flag（与后端 MemoryWriteFlag 对齐）。 */
export const MEMORY_WRITE_FLAGS = Object.freeze([
  'WRITE_USER',
  'WRITE_ASSISTANT_THINKING',
  'WRITE_ASSISTANT_MAIN_TEXT'
])

/** 显式配置（含空列表）时优先生效；null/undefined 回退 memoryMode。 */
export function isMemoryWritesConfigured(memoryWrites) {
  return Array.isArray(memoryWrites)
}

export function normalizeMemoryWrites(memoryWrites) {
  if (!Array.isArray(memoryWrites)) {
    return []
  }
  const allow = new Set(MEMORY_WRITE_FLAGS)
  return memoryWrites.filter((f) => allow.has(f))
}
