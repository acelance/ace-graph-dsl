/**
 * 自定义贝塞尔连线：普通边 / 条件边区分样式
 */
import { BezierEdge, BezierEdgeModel } from '@logicflow/core'

const EDGE_COLORS = {
  light: {
    normal: { stroke: '#8b9cb3', hover: '#5b8def', selected: '#409eff' },
    conditional: { stroke: '#e6a23c', hover: '#f59e0b', selected: '#d48806' },
    text: { normal: '#606266', conditional: '#b88230' },
    textBg: '#ffffff',
  },
  dark: {
    normal: { stroke: '#6b7d93', hover: '#60a5fa', selected: '#3b82f6' },
    conditional: { stroke: '#fbbf24', hover: '#fcd34d', selected: '#f59e0b' },
    text: { normal: '#c0c4cc', conditional: '#fcd34d' },
    textBg: '#1d1e1f',
  },
}

function edgePalette(properties) {
  const theme = properties?.theme === 'dark' ? 'dark' : 'light'
  const kind = properties?.type === 'conditional' ? 'conditional' : 'normal'
  return { theme: EDGE_COLORS[theme], kind }
}

class DspBezierModel extends BezierEdgeModel {
  getEdgeStyle() {
    const style = super.getEdgeStyle()
    const { theme, kind } = edgePalette(this.properties)
    const colors = theme[kind]
    return {
      ...style,
      stroke: colors.stroke,
      strokeWidth: kind === 'conditional' ? 2 : 1.5,
      hoverStroke: colors.hover,
      selectedStroke: colors.selected,
    }
  }

  getTextStyle() {
    const style = super.getTextStyle()
    const { theme, kind } = edgePalette(this.properties)
    return {
      ...style,
      fontSize: 12,
      color: theme.text[kind],
      background: {
        fill: theme.textBg,
        stroke: theme[kind].stroke,
        radius: 4,
      },
    }
  }
}

export const DspBezierEdge = { type: 'dsp-bezier', view: BezierEdge, model: DspBezierModel }
