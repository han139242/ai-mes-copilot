<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import dayjs from 'dayjs';
import AppIcon from '@/components/common/AppIcon.vue';
import MesChart from '@/components/chart/MesChart.vue';
import AiTodoPanel from '@/components/ai/AiTodoPanel.vue';
import { getDashboardSummary, getEquipmentStatus, getProductionTrend, getQualityStatistics } from '@/api/dashboard';
import { refreshTodos } from '@/stores/aiTodo';
import { createDashboardPreview } from '@/config/dashboard-preview';
import { getCurrentUser, isFrameworkPreview } from '@/utils/auth';
import { openAiAssistant } from '@/utils/app-events';
import { activeTheme } from '@/utils/theme';
import { hasPermission } from '@/utils/permission';

// 首页仪表盘将生产、质量、设备等多个后端统计接口汇总成指标条和ECharts图表。
const router = useRouter();
// 日期范围改变后重新查询；四个ref分别保存各块接口返回的数据。
const loading = ref(false);
const loadError = ref('');
const previewMode = isFrameworkPreview();
const dateRange = ref([dayjs().subtract(6, 'day').toDate(), dayjs().toDate()]);
const summary = ref({});
const trend = ref([]);
const quality = ref({});
const equipment = ref({});
const currentUser = getCurrentUser();
// 根据权限控制首页AI助手入口是否显示。
const canUseAi = computed(() => hasPermission('ai:assistant:use'));
// 根据权限控制生产大屏入口是否显示。
const canOpenBigScreen = computed(() => hasPermission('dashboard:big-screen:view'));

// 框架预览模式使用固定演示数据，不访问后端。
const previewPayload = createDashboardPreview();

// 生成首页标题行显示的当前日期和星期。
const dateSubtitle = computed(() => {
  const week = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][dayjs().day()];
  return `${dayjs().format('YYYY年MM月DD日')} ${week}`;
});

// 按真实姓名、昵称、用户名的顺序选择欢迎语中的用户名称。
const displayName = computed(() => currentUser.realName || currentUser.nickname || currentUser.username || 'MES 用户');
// 根据当前小时生成夜间值守、上午好、下午好或晚上好。
const greeting = computed(() => {
  const hour = dayjs().hour();
  if (hour < 6) {
    return '夜间值守';
  }
  if (hour < 12) {
    return '上午好';
  }
  if (hour < 18) {
    return '下午好';
  }
  return '晚上好';
});
// 当前主题变化时同步取得该主题的图表配色。
const chartPalette = computed(() => activeTheme.value.chart);

// 指标条：一行六个统计值，标签在上、数值在下，用分隔线而不是卡片分隔。
const numberStats = computed(() => [
  {
    label: '区间生产订单',
    value: formatNumber(summary.value.productionOrderCount),
    unit: '单',
    icon: 'clipboard',
  },
  {
    label: '运行中任务',
    value: formatNumber(summary.value.runningTaskCount),
    unit: '项',
    icon: 'activity',
  },
  {
    label: '区间合格产量',
    value: formatNumber(summary.value.todayQualifiedQuantity),
    unit: '件',
    icon: 'check',
  },
  {
    label: '检验合格率',
    value: Number(quality.value.inspectionCount || 0) ? Number(quality.value.qualifiedRate || 0).toFixed(2) : '--',
    unit: Number(quality.value.inspectionCount || 0) ? '%' : '',
    icon: 'shield-check',
  },
  {
    label: '区间生产工单',
    value: formatNumber(summary.value.workOrderCount),
    unit: '单',
    icon: 'package',
  },
  {
    label: '区间不良',
    value: formatNumber(summary.value.todayDefectQuantity),
    unit: '件',
    icon: 'alert',
  },
]);

// 首页常用业务入口，点击后直接跳到相应资源页面。
const quickCommands = [
  { label: '生产任务', description: '开工与完工', icon: 'activity', path: '/mfg/tasks' },
  { label: '生产报工', description: '提交产量与用料', icon: 'clipboard', path: '/mfg/work-reports' },
  { label: '质量检验', description: '检验与判定', icon: 'shield-check', path: '/qms/inspection-orders' },
  { label: '入库作业', description: '成品与物料入库', icon: 'warehouse', path: '/wms/inbound-orders' },
  { label: '设备维修', description: '派工与关闭', icon: 'wrench', path: '/eam/repair-orders' },
  { label: '批次追溯', description: '查询产品去向', icon: 'scan', path: '/trace/product-lots' },
];

// 只展示需要管理人员关注的库存、设备和质量风险，并携带对应处理页面路径。
const attentionItems = computed(() => [
  {
    label: '库存预警',
    value: formatNumber(summary.value.inventoryWarningCount),
    unit: '项',
    tone: Number(summary.value.inventoryWarningCount || 0) > 0 ? 'warning' : 'normal',
    path: '/wms/inventory',
  },
  {
    label: '故障设备',
    value: formatNumber(summary.value.equipmentFaultCount),
    unit: '台',
    tone: Number(summary.value.equipmentFaultCount || 0) > 0 ? 'danger' : 'normal',
    path: '/eam/equipment',
  },
  {
    label: '区间不良',
    value: formatNumber(summary.value.todayDefectQuantity),
    unit: '件',
    tone: Number(summary.value.todayDefectQuantity || 0) > 0 ? 'danger' : 'normal',
    path: '/qms/inspection-orders',
  },
]);

// 以下三个computed生成ECharts option，数据或主题变化时MesChart会自动重新绘制。
const trendOption = computed(() => ({
  animationDuration: 400,
  color: [chartPalette.value.data, '#c44842'],
  tooltip: {
    trigger: 'axis',
    backgroundColor: '#171d25',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 },
  },
  legend: {
    top: 0,
    right: 0,
    itemWidth: 12,
    itemHeight: 3,
    textStyle: { color: '#8f959e', fontSize: 11 },
  },
  grid: { top: 34, right: 8, bottom: 20, left: 40 },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: trend.value.map((item) => dayjs(item.date).format('MM/DD')),
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisTick: { show: false },
    axisLabel: { color: '#8f959e', fontSize: 11 },
  },
  yAxis: {
    type: 'value',
    splitNumber: 3,
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#8f959e', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f0f1f3' } },
  },
  series: [
    {
      name: '合格产量',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      lineStyle: { width: 2 },
      areaStyle: { color: `${chartPalette.value.data}14` },
      data: trend.value.map((item) => Number(item.qualifiedQuantity || 0)),
    },
    {
      name: '不良产量',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 4,
      lineStyle: { width: 1.6 },
      data: trend.value.map((item) => Number(item.defectQuantity || 0)),
    },
  ],
}));

// 根据质量统计生成合格/不合格环形饼图配置，无数据时显示灰色占位。
const qualityOption = computed(() => {
  const qualified = Number(quality.value.qualifiedCount || 0);
  const unqualified = Number(quality.value.unqualifiedCount || 0);
  const hasData = qualified + unqualified > 0;
  return {
    animationDuration: 400,
    color: hasData ? [chartPalette.value.primary, '#c44842'] : ['#e5e7eb'],
    tooltip: {
      trigger: 'item',
      backgroundColor: '#171d25',
      borderWidth: 0,
      textStyle: { color: '#fff', fontSize: 12 },
    },
    legend: {
      bottom: 0,
      left: 'center',
      itemWidth: 7,
      itemHeight: 7,
      textStyle: { color: '#8f959e', fontSize: 11 },
    },
    series: [
      {
        type: 'pie',
        radius: ['54%', '72%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: false,
        label: {
          show: true,
          position: 'center',
          formatter: `${Number(quality.value.qualifiedRate || 0).toFixed(1)}%`,
          color: '#1f2329',
          fontSize: 22,
          fontWeight: 600,
        },
        labelLine: { show: false },
        data: hasData
          ? [
              { value: qualified, name: '合格' },
              { value: unqualified, name: '不合格' },
            ]
          : [{ value: 1, name: '暂无检验' }],
      },
    ],
  };
});

// 根据设备状态数量生成横向柱状图配置。
const equipmentOption = computed(() => ({
  animationDuration: 400,
  color: [chartPalette.value.primary],
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    backgroundColor: '#171d25',
    borderWidth: 0,
    textStyle: { color: '#fff', fontSize: 12 },
  },
  grid: { top: 8, right: 16, bottom: 14, left: 44 },
  xAxis: {
    type: 'value',
    axisLabel: { color: '#8f959e', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f0f1f3' } },
  },
  yAxis: {
    type: 'category',
    data: ['运行', '空闲', '停机', '保养', '故障', '待复用'],
    axisTick: { show: false },
    axisLine: { show: false },
    axisLabel: { color: '#646a73', fontSize: 11.5 },
  },
  series: [
    {
      type: 'bar',
      barWidth: 10,
      itemStyle: { borderRadius: 2 },
      data: [
        { value: Number(equipment.value.running || 0), itemStyle: { color: chartPalette.value.primary } },
        { value: Number(equipment.value.idle || 0), itemStyle: { color: '#9db8e0' } },
        { value: Number(equipment.value.stopped || 0), itemStyle: { color: '#a5abb4' } },
        { value: Number(equipment.value.maintenance || 0), itemStyle: { color: '#d98a00' } },
        { value: Number(equipment.value.fault || 0), itemStyle: { color: '#c44842' } },
        { value: Number(equipment.value.repaired || 0), itemStyle: { color: '#7fa88f' } },
      ],
    },
  ],
}));

// 生产执行主流程入口，帮助用户按订单到追溯的顺序进入各模块。
const processSteps = [
  { label: '订单', icon: 'clipboard', path: '/mfg/production-orders' },
  { label: '工单', icon: 'package', path: '/mfg/work-orders' },
  { label: '派工', icon: 'users', path: '/mfg/dispatch-orders' },
  { label: '报工', icon: 'activity', path: '/mfg/work-reports' },
  { label: '质检', icon: 'shield-check', path: '/qms/inspection-orders' },
  { label: '入库', icon: 'warehouse', path: '/wms/inbound-orders' },
  { label: '追溯', icon: 'scan', path: '/trace/product-lots' },
];

// 设备总数，标题右侧直接给出规模。
const equipmentTotal = computed(
  () =>
    Number(equipment.value.running || 0) +
    Number(equipment.value.idle || 0) +
    Number(equipment.value.stopped || 0) +
    Number(equipment.value.fault || 0) +
    Number(equipment.value.maintenance || 0) +
    Number(equipment.value.repaired || 0),
);

// 统一使用中文千分位格式展示数量，避免模板中重复格式化逻辑。
function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value || 0));
}

// 将日期选择器的Date对象转换为后端需要的beginDate和endDate字符串。
function queryParams() {
  // Element Plus日期范围保存Date对象，调用后端前转换为YYYY-MM-DD查询参数。
  return {
    beginDate: dayjs(dateRange.value[0]).format('YYYY-MM-DD'),
    endDate: dayjs(dateRange.value[1]).format('YYYY-MM-DD'),
  };
}

// 预览模式读取本地数据；真实模式并行请求四个统计接口并更新图表。
// silent 用于定时自动刷新：不显示整页 loading，避免页面反复闪烁。
async function loadDashboard(silent = false) {
  // 预览模式直接赋值；真实模式并行请求四块统计数据，全部完成后响应式更新界面。
  if (previewMode) {
    summary.value = previewPayload.summary;
    trend.value = previewPayload.trend;
    quality.value = previewPayload.quality;
    equipment.value = previewPayload.equipment;
    return;
  }

  if (!silent) {
    loading.value = true;
  }
  loadError.value = '';
  try {
    const params = queryParams();
    const [summaryResult, trendResult, qualityResult, equipmentResult] = await Promise.all([
      getDashboardSummary(params),
      getProductionTrend(params),
      getQualityStatistics(params),
      getEquipmentStatus(),
    ]);
    summary.value = summaryResult.data || {};
    trend.value = trendResult.data || [];
    quality.value = qualityResult.data || {};
    equipment.value = equipmentResult.data || {};
    lastRefreshAt.value = dayjs().format('HH:mm:ss');
  } catch (error) {
    // 自动刷新失败时保留上一次成功的数据，只记错误，不清空看板。
    if (!silent) {
      loadError.value = error.displayMessage || error.message || '看板数据加载失败';
    }
  } finally {
    if (!silent) {
      loading.value = false;
    }
  }
}

// 看板数据需要反映车间实时变化：每 60 秒静默刷新一次，离开页面时清除定时器。
// 待办面板共用同一个定时器，不再单独建第二个 setInterval。
const lastRefreshAt = ref('');
let refreshTimer = null;

onMounted(() => {
  loadDashboard();
  refreshTimer = window.setInterval(() => {
    loadDashboard(true);
    refreshTodos(true);
  }, 60000);
});

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  }
});
</script>

<template>
  <!-- 页面结构：标题行 + 指标条 + 常用入口 + 业务链路/趋势图与关注项/质量/设备两栏。 -->
  <div class="mes-page dashboard-page" v-loading="loading">
    <header class="page-head">
      <div class="head-copy">
        <h1>首页</h1>
        <p>{{ greeting }}，{{ displayName }} · {{ dateSubtitle }}</p>
      </div>
      <div class="head-actions">
        <el-tag v-if="previewMode" size="small" effect="plain">预览数据</el-tag>
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          unlink-panels
          :clearable="false"
          size="default"
          @change="loadDashboard"
        />
        <el-tooltip content="刷新数据" placement="bottom">
          <el-button class="mes-icon-button" aria-label="刷新数据" @click="loadDashboard()">
            <AppIcon name="refresh" :size="15" />
          </el-button>
        </el-tooltip>
        <el-button v-if="canUseAi" class="ai-entry" @click="openAiAssistant">
          <AppIcon name="bot" :size="15" />
          AI 解读
        </el-button>
        <el-button v-if="canOpenBigScreen" type="primary" plain @click="router.push('/big-screen/production')">
          <AppIcon name="monitor" :size="15" />
          生产大屏
        </el-button>
      </div>
    </header>

    <el-alert v-if="loadError" class="dashboard-alert" :title="loadError" type="error" show-icon :closable="false">
      <template #default>
        <el-button link type="danger" @click="loadDashboard()">重新加载</el-button>
      </template>
    </el-alert>

    <!-- 指标条：一整块面板内六等分，用竖线分隔，比六张卡片更紧凑。 -->
    <section class="metric-strip" aria-label="运营指标">
      <article v-for="item in numberStats" :key="item.label" class="metric-cell">
        <span class="metric-label">
          <AppIcon :name="item.icon" :size="13" />
          {{ item.label }}
        </span>
        <strong
          >{{ item.value }}<em v-if="item.unit">{{ item.unit }}</em></strong
        >
      </article>
    </section>

    <!-- 常用入口：一行内联，替代原来的六张大卡。 -->
    <section class="shortcut-bar" aria-label="快捷作业">
      <span class="shortcut-label">常用入口</span>
      <button
        v-for="item in quickCommands"
        :key="item.path"
        type="button"
        class="shortcut-item"
        :title="item.description"
        @click="router.push(item.path)"
      >
        <AppIcon :name="item.icon" :size="14" />
        {{ item.label }}
      </button>
      <span class="refresh-hint" :class="{ 'is-current': !previewMode && lastRefreshAt }">
        <i /><template v-if="previewMode">界面预览</template><template v-else-if="lastRefreshAt">数据更新于 {{ lastRefreshAt }}</template><template v-else>尚未同步数据</template>
      </span>
    </section>

    <div class="board-grid">
      <article class="mes-panel chain-panel">
        <div class="mes-panel-header">
          <div>
            <h2 class="mes-panel-title">生产业务链路</h2>
          </div>
        </div>
        <div class="process-flow">
          <template v-for="(step, index) in processSteps" :key="step.label">
            <button class="process-step" type="button" @click="router.push(step.path)">
              <span><AppIcon :name="step.icon" :size="16" /></span>
              <strong>{{ step.label }}</strong>
            </button>
            <span v-if="index < processSteps.length - 1" class="process-link" aria-hidden="true" />
          </template>
        </div>
      </article>

      <article class="mes-panel attention-panel">
        <div class="mes-panel-header">
          <div>
            <h2 class="mes-panel-title">运营关注</h2>
          </div>
        </div>
        <div class="attention-list">
          <button
            v-for="item in attentionItems"
            :key="item.label"
            type="button"
            :class="`tone-${item.tone}`"
            @click="router.push(item.path)"
          >
            <span>{{ item.label }}</span>
            <strong
              >{{ item.value }}<small>{{ item.unit }}</small></strong
            >
            <AppIcon name="right" :size="13" />
          </button>
        </div>
      </article>
    </div>

    <!-- 我的待办：紧挨运营关注，把九类待办和可直接办理的按钮摊开，不用再点进各业务页找。 -->
    <AiTodoPanel class="dashboard-todo" />

    <div class="chart-grid">
      <article class="mes-panel trend-panel">
        <div class="mes-panel-header">
          <div>
            <h2 class="mes-panel-title">近七日生产趋势</h2>
          </div>
          <el-tag size="small" effect="plain">按日</el-tag>
        </div>
        <div class="chart-body"><MesChart :option="trendOption" height="200px" /></div>
      </article>

      <article class="mes-panel quality-panel">
        <div class="mes-panel-header">
          <div>
            <h2 class="mes-panel-title">质量概况</h2>
          </div>
          <span class="header-value">{{ quality.inspectionCount || 0 }} 单</span>
        </div>
        <div class="chart-body"><MesChart :option="qualityOption" height="200px" /></div>
      </article>

      <article class="mes-panel equipment-panel">
        <div class="mes-panel-header">
          <div>
            <h2 class="mes-panel-title">设备状态</h2>
          </div>
          <span class="header-value">{{ equipmentTotal }} 台</span>
        </div>
        <div class="chart-body"><MesChart :option="equipmentOption" height="200px" /></div>
      </article>
    </div>
  </div>
</template>

<style scoped>
/* 首页样式：中性灰阶 + 单一主色，靠 1px 描边、细分隔线和 12/13px 字号建立信息层级。 */
.dashboard-page {
  min-height: 100%;
  padding-bottom: 8px;
}

/* ---------- 标题行 ---------- */
.page-head {
  display: flex;
  min-height: 46px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.head-copy {
  min-width: 0;
}

.head-copy h1 {
  margin: 0;
  color: var(--mes-text);
  font-size: 24px;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.2px;
}

.head-copy p {
  margin: 3px 0 0;
  color: var(--mes-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.head-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.head-actions :deep(.el-date-editor) {
  width: 240px;
}
.head-actions :deep(.el-button) {
  gap: 6px;
  margin: 0;
}
.head-actions :deep(.el-tag) {
  border-radius: 3px;
}

.head-actions .ai-entry.el-button {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border);
  background: var(--mes-primary-soft);
}

.head-actions .ai-entry.el-button:hover {
  color: #fff;
  border-color: var(--mes-primary);
  background: var(--mes-primary);
}

.head-actions :deep(.el-button--primary.is-plain) {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border);
  background: #fff;
}

.head-actions :deep(.el-button--primary.is-plain:hover) {
  color: #fff;
  border-color: var(--mes-primary);
  background: var(--mes-primary-hover);
}

.dashboard-alert {
  margin-bottom: 10px;
}

/* ---------- 指标条 ---------- */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius);
  background: #fff;
}

.metric-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 20px;
  border-left: 1px solid var(--mes-border);
}

.metric-cell:first-child {
  border-left: 0;
}

.metric-label {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--mes-text-muted);
  font-size: 12px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-label :deep(svg) {
  flex: none;
  color: var(--mes-text-muted);
}

.metric-cell > strong {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: var(--mes-text);
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.4px;
  line-height: 1.1;
  white-space: nowrap;
}

.metric-cell em {
  color: var(--mes-text-muted);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
  letter-spacing: 0;
}

/* ---------- 常用入口 ---------- */
.shortcut-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  margin-top: 10px;
  padding: 0 2px;
}

.shortcut-label {
  margin-right: 6px;
  color: var(--mes-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.shortcut-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 9px;
  color: #4b5563;
  font-size: 12px;
  border: 1px solid transparent;
  border-radius: 5px;
  background: transparent;
  cursor: pointer;
  transition:
    color 0.13s ease,
    background 0.13s ease,
    border-color 0.13s ease;
}

.shortcut-item :deep(svg) {
  color: var(--mes-text-muted);
}

.shortcut-item:hover {
  color: var(--mes-primary);
  border-color: var(--mes-border);
  background: #fff;
}

.shortcut-item:hover :deep(svg) {
  color: var(--mes-primary);
}

.refresh-hint {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  color: var(--mes-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.refresh-hint i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--mes-text-muted);
}
.refresh-hint.is-current i { background: var(--mes-success); }

/* ---------- 主体：链路 + 关注 一行，三张图一行 ---------- */
.board-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1.85fr) minmax(300px, 1fr);
  align-items: start;
  gap: 12px;
  margin-top: 12px;
}

.chart-grid {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1.5fr) minmax(240px, 1fr) minmax(240px, 1fr);
  align-items: start;
  gap: 12px;
  margin-top: 12px;
}

.dashboard-todo {
  margin-top: 12px;
}

/* ---------- 面板通用 ---------- */
.mes-panel {
  min-width: 0;
}

.mes-panel-header {
  min-height: 44px;
  padding: 0 14px;
}

.mes-panel-header > div {
  min-width: 0;
}

.header-value {
  flex: none;
  color: #4b5563;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.chart-body {
  min-width: 0;
  overflow: hidden;
  padding: 8px 12px 6px;
}

/* ---------- 业务链路 ---------- */
.process-flow {
  display: flex;
  min-height: 84px;
  align-items: center;
  gap: 4px;
  overflow-x: auto;
  padding: 14px 16px;
}

.process-step {
  display: flex;
  min-width: 52px;
  flex: none;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #4b5563;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.process-step > span {
  display: inline-flex;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  color: #646a73;
  border: 1px solid var(--mes-border);
  border-radius: 6px;
  background: #f7f8fa;
  transition:
    color 0.13s ease,
    border-color 0.13s ease,
    background 0.13s ease;
}

.process-step:hover > span {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border);
  background: var(--mes-primary-soft);
}

.process-step strong {
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
}

.process-step:hover strong {
  color: var(--mes-primary);
}

.process-link {
  min-width: 12px;
  height: 1px;
  flex: 1;
  background: var(--mes-border);
}

/* ---------- 运营关注 ---------- */
.attention-list {
  display: grid;
  padding: 4px 10px 8px;
}

.attention-list button {
  display: grid;
  min-width: 0;
  min-height: 36px;
  grid-template-columns: minmax(0, 1fr) auto 14px;
  align-items: center;
  gap: 8px;
  padding: 6px 6px;
  color: #4b5563;
  border: 0;
  border-radius: 5px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.attention-list button + button {
  border-top: 1px solid var(--mes-border);
  border-radius: 0;
}
.attention-list button:hover {
  background: #f7f8fa;
}
.attention-list button > span {
  overflow: hidden;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.attention-list strong {
  color: var(--mes-text);
  font-size: 15px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
}
.attention-list button.tone-warning strong {
  color: #d98a00;
}
.attention-list button.tone-danger strong {
  color: #d64545;
}
.attention-list small {
  margin-left: 2px;
  color: var(--mes-text-muted);
  font-size: 9.5px;
  font-weight: 400;
}
.attention-list :deep(svg) {
  color: #c9ced6;
}

/* ---------- 响应式 ---------- */
@media (max-width: 1280px) {
  .metric-strip {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .metric-cell:nth-child(4) {
    border-left: 0;
  }
  .metric-cell:nth-child(n + 4) {
    border-top: 1px solid var(--mes-border);
  }
}

@media (max-width: 1120px) {
  .board-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .chart-grid {
    grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr);
  }
  .chart-grid > .equipment-panel {
    grid-column: 1 / -1;
  }
}

@media (max-width: 900px) {
  .page-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .head-actions {
    width: 100%;
    justify-content: flex-start;
  }
  .chart-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .chart-grid > .equipment-panel {
    grid-column: auto;
  }
  .refresh-hint {
    margin-left: 0;
  }
}

@media (max-width: 640px) {
  .metric-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric-cell:nth-child(odd) {
    border-left: 0;
  }
  .metric-cell:nth-child(n + 3) {
    border-top: 1px solid var(--mes-border);
  }
  .head-actions :deep(.el-date-editor) {
    width: 100%;
  }
  .process-flow {
    justify-content: flex-start;
  }
}
</style>
