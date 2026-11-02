/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
// 状态标签组件：把业务状态渲染成统一颜色和文字样式。
Component({
  properties: {
    text: {
      type: String,
      value: '-'
    },
    tone: {
      type: String,
      value: 'neutral'
    }
  }
})
