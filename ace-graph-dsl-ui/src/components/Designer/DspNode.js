/**
 * 自定义 SVG 节点：彩色竖条 + SVG 图标 + 分类颜色
 * 基于 LogicFlow v2 的 RectNode / DiamondNode / CircleNode
 */
import { RectNode, RectNodeModel, CircleNode, CircleNodeModel, h } from '@logicflow/core'

const ICON_PATHS = {
  NORMAL: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h5',
  ROUTER: 'M6 3v18 M6 9a3 3 0 1 0 0-6 M6 21a3 3 0 1 0 0-6 M18 9a3 3 0 1 0 0-6 M18 21V9 M6 9h12',
  MERGE:  'M6 3v6 M6 21v-6 M18 9v12 M6 9a3 3 0 1 0 0-6 M6 15a3 3 0 1 0 0-6 M18 9a3 3 0 1 0 0-6 M6 9c0 6 12 0 12 0',
  HITL:   'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z M2 21v-2a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v2 M16 3.13a4 4 0 0 1 0 7.75 M22 21v-2a4 4 0 0 0-3-3.87',
  SUBGRAPH: 'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z M3 12h18 M8 17h6',
  AGENT: 'M12 2l2 7h7l-6 4 2 7-5-5-5 5 2-7-6-4h7z',
}

const CAT_COLORS = {
  light: {
    NORMAL: { stroke: '#5b8def', fill: '#eff6ff', bar: '#5b8def', icon: '#5b8def' },
    ROUTER: { stroke: '#f59e0b', fill: '#fffbeb', bar: '#f59e0b', icon: '#f59e0b', accent: '#fde68a' },
    MERGE:  { stroke: '#22c55e', fill: '#f0fdf4', bar: '#22c55e', icon: '#22c55e' },
    HITL:   { stroke: '#9333ea', fill: '#faf5ff', bar: '#9333ea', icon: '#7c3aed' },
    SUBGRAPH: { stroke: '#6366f1', fill: '#eef2ff', bar: '#6366f1', icon: '#4f46e5' },
    AGENT:  { stroke: '#0d9488', fill: '#ecfeff', bar: '#0d9488', icon: '#0f766e' },
  },
  dark: {
    NORMAL: { stroke: '#60a5fa', fill: '#1e3a5f', bar: '#60a5fa', icon: '#60a5fa' },
    ROUTER: { stroke: '#fbbf24', fill: '#3d2e0a', bar: '#fbbf24', icon: '#fbbf24', accent: '#78350f' },
    MERGE:  { stroke: '#34d399', fill: '#064e3b', bar: '#34d399', icon: '#34d399' },
    HITL:   { stroke: '#a78bfa', fill: '#2e1065', bar: '#a78bfa', icon: '#c4b5fd' },
    SUBGRAPH: { stroke: '#818cf8', fill: '#1e1b4b', bar: '#818cf8', icon: '#a5b4fc' },
    AGENT:  { stroke: '#2dd4bf', fill: '#042f2e', bar: '#2dd4bf', icon: '#5eead4' },
  },
}

function getColors(category, theme) {
  const themes = theme === 'dark' ? CAT_COLORS.dark : CAT_COLORS.light
  return themes[category] || themes.NORMAL
}

/** 流式 / 异步节点标记：右上角脉冲小徽标（≋ 波纹），由 Canvas 的 CSS 驱动动画 */
function streamingBadge(cx, cy) {
  const streamColor = '#06b6d4'
  return [
    h('circle', {
      class: 'dsp-streaming',
      cx, cy, r: 11,
      fill: '#ffffff', stroke: streamColor, 'stroke-width': 2,
    }),
    h('path', {
      class: 'dsp-streaming-wave',
      d: `M ${cx - 6} ${cy} q 2 -5 4 0 q 2 5 4 0 q 2 -5 4 0`,
      fill: 'none', stroke: streamColor, 'stroke-width': 1.8, 'stroke-linecap': 'round',
    }),
    h('title', {}, '流式 / 异步节点'),
  ]
}

/** 水平六边形（扁平顶底），用于路由/分支节点 */
function routerHexPoints(x, y, width, height) {
  const hw = width / 2
  const hh = height / 2
  const shoulder = width * 0.22
  return [
    `${x - hw + shoulder},${y - hh}`,
    `${x + hw - shoulder},${y - hh}`,
    `${x + hw},${y}`,
    `${x + hw - shoulder},${y + hh}`,
    `${x - hw + shoulder},${y + hh}`,
    `${x - hw},${y}`,
  ].join(' ')
}

// ── 业务节点（矩形 + 彩色竖条 + 图标） ──
class DspRectModel extends RectNodeModel {
  setAttributes() {
    super.setAttributes()
    this.width = 180
    this.height = 60
    this.radius = 8
  }

  getTextStyle() {
    const style = super.getTextStyle()
    return {
      ...style,
      fontSize: 13,
      paddingLeft: 30,
      paddingRight: 10,
    }
  }
}

class DspRectView extends RectNode {
  getShape() {
    const { model } = this.props
    const { x, y, width, height, radius } = model
    const cat = model.properties?.category || 'NORMAL'
    const theme = model.properties?.theme || 'light'
    const c = getColors(cat, theme)
    const iconPath = ICON_PATHS[cat] || ICON_PATHS.NORMAL

    return h('g', {}, [
      h('rect', {
        x: x - width / 2, y: y - height / 2,
        width, height, rx: radius, ry: radius,
        fill: c.fill, stroke: c.stroke, strokeWidth: 1.8,
      }),
      h('path', {
        d: `M ${x - width / 2 + radius} ${y - height / 2} L ${x - width / 2 + 5} ${y - height / 2} L ${x - width / 2 + 5} ${y + height / 2} L ${x - width / 2 + radius} ${y + height / 2} A ${radius} ${radius} 0 0 1 ${x - width / 2} ${y + height / 2 - radius} L ${x - width / 2} ${y - height / 2 + radius} A ${radius} ${radius} 0 0 1 ${x - width / 2 + radius} ${y - height / 2} Z`,
        fill: c.bar,
      }),
      h('path', {
        d: iconPath,
        transform: `translate(${x - width / 2 + 12}, ${y - 8}) scale(0.6)`,
        fill: 'none',
        stroke: c.icon,
        'stroke-width': 2,
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
      }),
      ...(model.properties?.config?.streaming ? streamingBadge(x + width / 2 - 14, y - height / 2) : []),
    ])
  }
}

// ── 路由节点（水平六边形 + 分支装饰 + 图标） ──
// 基于 RectNodeModel：DiamondNodeModel 的 width/height 为 computed，不可赋值
class DspRouterModel extends RectNodeModel {
  setAttributes() {
    super.setAttributes()
    this.width = 156
    this.height = 72
    this.radius = 0
  }

  getDefaultAnchor() {
    const { id, x, y, width, height } = this
    const hw = width / 2
    const hh = height / 2
    const shoulder = width * 0.22
    return [
      { x: x + hw - shoulder, y: y - hh, id: `${id}_top`, type: 'top' },
      { x: x + hw, y, id: `${id}_right`, type: 'right' },
      { x: x + hw - shoulder, y: y + hh, id: `${id}_bottom`, type: 'bottom' },
      { x: x - hw, y, id: `${id}_left`, type: 'left' },
    ]
  }

  getTextStyle() {
    const style = super.getTextStyle()
    return {
      ...style,
      fontSize: 12,
      paddingTop: 8,
    }
  }
}

class DspRouterView extends RectNode {
  getShape() {
    const { model } = this.props
    const { x, y, width, height } = model
    const theme = model.properties?.theme || 'light'
    const c = getColors('ROUTER', theme)
    const points = routerHexPoints(x, y, width, height)
    const hw = width / 2
    const hh = height / 2

    return h('g', {}, [
      h('polygon', {
        points,
        fill: c.fill,
        stroke: c.stroke,
        'stroke-width': 2,
        'stroke-linejoin': 'round',
      }),
      h('polygon', {
        points: routerHexPoints(x, y, width - 10, height - 10),
        fill: 'none',
        stroke: c.accent || c.stroke,
        'stroke-width': 1,
        'stroke-opacity': 0.55,
        'stroke-linejoin': 'round',
      }),
      h('circle', { cx: x - hw + 14, cy: y, r: 2.5, fill: c.icon, opacity: 0.7 }),
      h('circle', { cx: x + hw - 14, cy: y, r: 2.5, fill: c.icon, opacity: 0.7 }),
      h('path', {
        d: ICON_PATHS.ROUTER,
        transform: `translate(${x - 9}, ${y - hh + 10}) scale(0.55)`,
        fill: 'none',
        stroke: c.icon,
        'stroke-width': 2,
        'stroke-linecap': 'round',
        'stroke-linejoin': 'round',
      }),
      h('line', {
        x1: x - hw + 22, y1: y + hh - 14,
        x2: x + hw - 22, y2: y + hh - 14,
        stroke: c.accent || c.stroke,
        'stroke-width': 1,
        'stroke-opacity': 0.4,
        'stroke-dasharray': '3 3',
      }),
      ...(model.properties?.config?.streaming ? streamingBadge(x + width / 2 - 14, y - height / 2) : []),
    ])
  }
}

// ── START/END 圆形节点 ──
const SE_COLORS = {
  light: { stroke: '#06b6d4', fill: '#ecfeff', icon: '#06b6d4' },
  dark:  { stroke: '#22d3ee', fill: '#0e2f3a', icon: '#22d3ee' },
}

class DspCircleModel extends CircleNodeModel {
  setAttributes() {
    super.setAttributes()
    this.r = 28
  }

  getTextStyle() {
    const style = super.getTextStyle()
    return { ...style, fontSize: 11, fontWeight: 600 }
  }
}

class DspCircleView extends CircleNode {
  getShape() {
    const { model } = this.props
    const { x, y, r } = model
    const theme = model.properties?.theme || 'light'
    const c = SE_COLORS[theme] || SE_COLORS.light
    const kind = model.properties?.kind || 'START'
    const isStart = kind === 'START'
    const iconPath = isStart
      ? 'M5 3l14 9-14 9V3z'
      : 'M3 3h18v18H3z'

    return h('g', {}, [
      h('circle', {
        cx: x, cy: y, r,
        fill: c.fill, stroke: c.stroke, strokeWidth: 2.2,
      }),
      h('path', {
        d: iconPath,
        transform: `translate(${x - 8}, ${y - 16}) scale(0.6)`,
        fill: isStart ? c.icon : 'none',
        stroke: c.icon,
        'stroke-width': 2,
        'stroke-linejoin': 'round',
      }),
    ])
  }
}

export const DspRectNode = { type: 'dsp-rect', model: DspRectModel, view: DspRectView }
export const DspDiamondNode = { type: 'dsp-diamond', model: DspRouterModel, view: DspRouterView }
export const DspCircleNode = { type: 'dsp-circle', model: DspCircleModel, view: DspCircleView }
// 子图 / Agent 节点复用矩形外观，仅靠 category 区分颜色与图标
export const DspSubgraphNode = { type: 'dsp-subgraph', model: DspRectModel, view: DspRectView }
export const DspAgentNode = { type: 'dsp-agent', model: DspRectModel, view: DspRectView }

// ── 子流程分组容器（虚线圆角矩形 + 标题栏，无连接锚点） ──
const GROUP_COLORS = {
  light: { stroke: '#8b5cf6', fill: 'rgba(139,92,246,0.06)', header: 'rgba(139,92,246,0.12)' },
  dark:  { stroke: '#a78bfa', fill: 'rgba(167,139,250,0.10)', header: 'rgba(167,139,250,0.18)' },
}

class DspGroupModel extends RectNodeModel {
  setAttributes() {
    super.setAttributes()
    const p = this.properties || {}
    this.width = p.width || 240
    this.height = p.height || 140
    this.radius = 10
    this.zIndex = -1
  }

  getDefaultAnchor() {
    // 容器节点不参与连线
    return []
  }

  getNodeStyle() {
    const style = super.getNodeStyle()
    const theme = (this.properties?.theme === 'dark') ? 'dark' : 'light'
    const c = GROUP_COLORS[theme]
    return { ...style, fill: c.fill, stroke: c.stroke, strokeWidth: 1.5, strokeDasharray: '6 4' }
  }

  getTextStyle() {
    const style = super.getTextStyle()
    return { ...style, fontSize: 13, fontWeight: 600, textAlign: 'left', textBaseline: 'top' }
  }
}

class DspGroupView extends RectNode {
  getShape() {
    const { model } = this.props
    const { x, y, width, height, radius } = model
    const theme = (model.properties?.theme === 'dark') ? 'dark' : 'light'
    const c = GROUP_COLORS[theme]
    const headerH = 26
    const collapsed = !!model.properties?.collapsed
    return h('g', {}, [
      h('rect', {
        x: x - width / 2, y: y - height / 2,
        width, height, rx: radius, ry: radius,
        fill: c.fill, stroke: c.stroke, strokeWidth: 1.5, strokeDasharray: '6 4',
      }),
      h('rect', {
        x: x - width / 2, y: y - height / 2,
        width, height: headerH, rx: radius, ry: radius,
        fill: c.header, stroke: 'none',
      }),
      h('path', {
        d: `M ${x - width / 2} ${y - height / 2 + headerH} L ${x + width / 2} ${y - height / 2 + headerH}`,
        stroke: c.stroke, strokeWidth: 1, strokeOpacity: 0.4,
      }),
    ])
  }
}

export const DspGroupNode = { type: 'dsp-group', model: DspGroupModel, view: DspGroupView }

export function resolveNodeType(category, kind) {
  if (kind === 'START' || kind === 'END') return 'dsp-circle'
  if (kind === 'GROUP') return 'dsp-group'
  if (kind === 'ERROR') return 'dsp-circle'
  if (category === 'ROUTER') return 'dsp-diamond'
  if (category === 'SUBGRAPH') return 'dsp-subgraph'
  if (category === 'AGENT') return 'dsp-agent'
  return 'dsp-rect'
}
