<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { init, use } from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

// ECharts按需注册，只打包项目实际使用的柱状图、折线图、饼图和必要组件。
use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer]);

// option是标准ECharts配置，height允许不同页面控制图表高度。
const props = defineProps({
  option: { type: Object, required: true },
  height: { type: String, default: '300px' },
});

const chartElement = ref();
// chart和ResizeObserver不是界面状态，无需使用ref；保留实例用于更新和销毁。
let chart;
let resizeObserver;

// 统一图表配色：主色蓝 + 深青 + 琥珀 + 红 + 中性灰，避免ECharts默认的高饱和彩色。
const PALETTE = ['#1f6feb', '#0f766e', '#d98a00', '#d64545', '#64748b', '#8b5cf6'];

// 坐标轴和网格线统一成浅灰细线，数值刻度用次要文字色。
const AXIS_BASE = {
  axisLine: { lineStyle: { color: '#e6eaf0' } },
  axisTick: { show: false },
  axisLabel: { color: '#8a97a8', fontSize: 11 },
  splitLine: { lineStyle: { color: '#f0f3f7' } },
};

function withTheme(option) {
  const next = { ...option, color: option.color || PALETTE };
  if (next.xAxis && !Array.isArray(next.xAxis)) {
    next.xAxis = {
      ...AXIS_BASE,
      ...next.xAxis,
      axisLabel: { ...AXIS_BASE.axisLabel, ...(next.xAxis.axisLabel || {}) },
    };
  }
  if (next.yAxis && !Array.isArray(next.yAxis)) {
    next.yAxis = {
      ...AXIS_BASE,
      ...next.yAxis,
      axisLabel: { ...AXIS_BASE.axisLabel, ...(next.yAxis.axisLabel || {}) },
    };
  }
  if (next.legend && !next.legend.textStyle) {
    next.legend = { ...next.legend, textStyle: { color: '#5b6b82', fontSize: 11 }, itemWidth: 10, itemHeight: 8 };
  }
  return next;
}

// 首次调用时初始化ECharts，后续调用只用新option覆盖图表内容。
function renderChart() {
  if (!chartElement.value) {
    return;
  }
  if (!chart) {
    // DOM挂载后只初始化一次图表实例，之后更新只调用setOption。
    chart = init(chartElement.value);
  }
  chart.setOption(withTheme(props.option), { notMerge: true });
}

// deep:true保证option内部的series、xAxis等嵌套数据变化时也重新绘制。
watch(() => props.option, renderChart, { deep: true });

// 组件挂载后绘图，并开始监听图表容器尺寸。
onMounted(() => {
  renderChart();
  // 容器尺寸改变时让ECharts自适应，不只依赖浏览器window.resize事件。
  resizeObserver = new ResizeObserver(() => chart?.resize());
  resizeObserver.observe(chartElement.value);
});

// 组件销毁前停止尺寸监听并释放ECharts实例。
onBeforeUnmount(() => {
  // 页面离开时释放观察器和Canvas资源，避免多次进入页面后产生内存泄漏。
  resizeObserver?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>

<template>
  <!-- 这个空div是ECharts挂载容器，图表内容由renderChart绘制到Canvas中。 -->
  <div ref="chartElement" class="mes-chart" :style="{ height }" />
</template>

<style scoped>
/* 图表容器始终占满父级宽度，高度由组件prop传入。 */
.mes-chart {
  width: 100%;
  min-width: 0;
}
</style>
