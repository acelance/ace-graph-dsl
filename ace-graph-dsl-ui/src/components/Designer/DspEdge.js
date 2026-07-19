/**
 * 自定义贝塞尔连线：普通边 / 条件边区分样式
 */
import { BezierEdge, BezierEdgeModel } from '@logicflow/core'

const EDGE_COLORS = {
  light: {
    normal: { stroke: '#8b9cb3', hover: '#5b8def', selected: '#409eff' },
    conditional: { stroke: '#e6a23c', hover: '#f59e0b', selected: '#d48806' },
    error: { stroke: '#ef4444', hover: '#f87171', selected: '#dc2626' },
    invalid: { stroke: '#f56c6c', hover: '#f78989', selected: '#f56c6c' },
    text: { normal: '#606266', conditional: '#b88230', error: '#ef4444', invalid: '#f56c6c' },
    textBg: '#ffffff',
  },
  dark: {
    normal: { stroke: '#6b7d93', hover: '#60a5fa', selected: '#3b82f6' },
    conditional: { stroke: '#fbbf24', hover: '#fcd34d', selected: '#f59e0b' },
    error: { stroke: '#f87171', hover: '#fca5a5', selected: '#ef4444' },
    invalid: { stroke: '#f56c6c', hover: '#f78989', selected: '#f56c6c' },
    text: { normal: '#c0c4cc', conditional: '#fcd34d', error: '#fca5a5', invalid: '#f78989' },
    textBg: '#1d1e1f',
  },
}

function edgePalette(properties) {
  const theme = properties?.theme === 'dark' ? 'dark' : 'light'
  const kind = properties?.type === 'conditional' ? 'conditional' : (properties?.type === 'error' ? 'error' : 'normal')
  return { theme: EDGE_COLORS[theme], kind }
}

class DspBezierModel extends BezierEdgeModel {
  getEdgeStyle() {
    const style = super.getEdgeStyle()
    const { theme, kind } = edgePalette(this.properties)
    const colors = this.properties?.paramInvalid ? theme.invalid : theme[kind]
    return {
      ...style,
      stroke: colors.stroke,
      strokeWidth: this.properties?.paramInvalid ? 2 : (kind === 'conditional' ? 2 : (kind === 'error' ? 2 : 1.5)),
      strokeDasharray: kind === 'error' ? '6 4' : undefined,
      hoverStroke: colors.hover,
      selectedStroke: colors.selected,
    }
  }

  getTextStyle() {
    const style = super.getTextStyle()
    const { theme, kind } = edgePalette(this.properties)
    const textKind = this.properties?.paramInvalid ? 'invalid' : kind
    const strokeKind = this.properties?.paramInvalid ? 'invalid' : kind
    return {
      ...style,
      fontSize: 12,
      color: theme.text[textKind],
      background: {
        fill: theme.textBg,
        stroke: theme[strokeKind].stroke,
        radius: 4,
      },
    }
  }
}

export const DspBezierEdge = { type: 'dsp-bezier', view: BezierEdge, model: DspBezierModel }
