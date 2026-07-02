import { diffLines } from 'diff'

/**
 * 将两段文本按行拆成左右对齐的 diff 行，用于并排高亮展示。
 *
 * @typedef {{ left: string, right: string, type: 'unchanged'|'modified'|'added'|'removed', leftNum: number|null, rightNum: number|null }} DiffRow
 */

function splitLines(text) {
  if (!text) return []
  const lines = text.split('\n')
  if (lines.length && lines[lines.length - 1] === '') lines.pop()
  return lines
}

function pairModifiedRows(raw) {
  const rows = []
  let i = 0
  while (i < raw.length) {
    const cur = raw[i]
    if (cur.removed) {
      const removed = []
      let j = i
      while (j < raw.length && raw[j].removed) {
        removed.push(raw[j].line)
        j++
      }
      const added = []
      while (j < raw.length && raw[j].added) {
        added.push(raw[j].line)
        j++
      }
      if (added.length) {
        const max = Math.max(removed.length, added.length)
        for (let k = 0; k < max; k++) {
          rows.push({
            left: removed[k] ?? '',
            right: added[k] ?? '',
            type: 'modified',
            leftEmpty: k >= removed.length,
            rightEmpty: k >= added.length
          })
        }
        i = j
        continue
      }
      for (const line of removed) {
        rows.push({ left: line, right: '', type: 'removed', leftEmpty: false, rightEmpty: true })
      }
      i = j
      continue
    }
    if (cur.added) {
      rows.push({ left: '', right: cur.line, type: 'added', leftEmpty: true, rightEmpty: false })
      i++
      continue
    }
    rows.push({ left: cur.line, right: cur.line, type: 'unchanged', leftEmpty: false, rightEmpty: false })
    i++
  }
  return rows
}

/**
 * @param {string} oldText
 * @param {string} newText
 * @returns {DiffRow[]}
 */
export function buildSideBySideLineDiff(oldText, newText) {
  if (oldText === newText) {
    return splitLines(oldText).map((line, idx) => ({
      left: line,
      right: line,
      type: 'unchanged',
      leftEmpty: false,
      rightEmpty: false,
      leftNum: idx + 1,
      rightNum: idx + 1
    }))
  }

  const parts = diffLines(oldText || '', newText || '')
  const raw = []
  for (const part of parts) {
    for (const line of splitLines(part.value)) {
      raw.push({
        line,
        added: !!part.added,
        removed: !!part.removed
      })
    }
  }

  const aligned = pairModifiedRows(raw)
  let leftNum = 1
  let rightNum = 1
  return aligned.map(row => {
    const leftNumValue = row.leftEmpty ? null : leftNum++
    const rightNumValue = row.rightEmpty ? null : rightNum++
    return {
      ...row,
      leftNum: leftNumValue,
      rightNum: rightNumValue
    }
  })
}

export function countLineDiffStats(rows) {
  const stats = { added: 0, removed: 0, modified: 0 }
  for (const row of rows || []) {
    if (row.type === 'added') stats.added++
    else if (row.type === 'removed') stats.removed++
    else if (row.type === 'modified') stats.modified++
  }
  return stats
}
