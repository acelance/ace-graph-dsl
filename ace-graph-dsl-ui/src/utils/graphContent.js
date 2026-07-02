/**
 * 提取可执行内容用于前后端一致的变更比对（不含 version / displayName / description）。
 */
export function contentSnapshot(def) {
  if (!def) return null
  return {
    keyStrategies: def.keyStrategies || {},
    nodes: def.nodes || [],
    edges: def.edges || [],
    compile: def.compile || { interruptBefore: [], saver: 'memory' }
  }
}

export function canonicalContent(def) {
  return JSON.stringify(contentSnapshot(def))
}

export function sameContent(a, b) {
  return canonicalContent(a) === canonicalContent(b)
}

/**
 * 简单 patch 版本号递增：1.0.0 -> 1.0.1
 */
export function bumpPatchVersion(version) {
  const parts = String(version || '1.0.0').split('.').map(p => parseInt(p, 10) || 0)
  while (parts.length < 3) parts.push(0)
  parts[2] += 1
  return parts.join('.')
}

export function compareSemver(left, right) {
  const l = parseSemver(left)
  const r = parseSemver(right)
  for (let i = 0; i < 3; i++) {
    if (l[i] !== r[i]) return l[i] - r[i]
  }
  return 0
}

function parseSemver(version) {
  const parts = String(version || '0.0.0').split('.').map(p => parseInt(p, 10) || 0)
  while (parts.length < 3) parts.push(0)
  return parts.slice(0, 3)
}

export function maxSemver(versions) {
  if (!versions?.length) return ''
  return versions.reduce((max, v) => (compareSemver(v, max) > 0 ? v : max), versions[0])
}
