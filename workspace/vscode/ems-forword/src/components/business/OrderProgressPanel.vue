<script setup>
import { computed, h, ref, watch } from 'vue';
import { ElTooltip } from 'element-plus';
import dayjs from 'dayjs';
import MesChart from '@/components/chart/MesChart.vue';
import AppIcon from '@/components/common/AppIcon.vue';
import OrderStageFlow from '@/components/business/OrderStageFlow.vue';
import SemiFinishedCoverageTable from '@/components/business/SemiFinishedCoverageTable.vue';
import OrderDocumentTable from '@/components/business/OrderDocumentTable.vue';

// 订单全过程面板：把 /progress 接口返回的一份快照渲染成
// “做到哪（八阶段）→ 卡在哪（阻塞与证据）→ 接着做什么（可执行动作与单据入口）”。
// 组件只负责展示，不做任何业务推断；所有数量口径都由后端给出。
const props = defineProps({
  progress: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  asOf: { type: String, default: '' },
});
const emit = defineEmits(['close', 'open-document', 'open-work-order', 'run-action', 'refresh', 'update:asOf']);

const materialColumnHelp = {
  订单需求: '草稿取生成时的初始需求，已下达工单按原计划数量和冻结BOM计算，含标准损耗及物料取整，不包含报废补产或返工追加用料。执行工单冻结依据缺失时显示--。',
  追加用料: '报废补产或需要额外投料的质量处置，在原始需求之外增加的物料需求；不是实际废料重量，也不是报废成品台数。',
  净领料: '已确认领给本工单的物料数量减去已确认退料，包含补领；不是成品发给客户的数量。',
  本单预留: '已经为本工单锁定、尚未领走的合格库存，其他工单不能重复占用。',
  库存量: '仓库内合格且未锁定的公共可用库存，可用于后续领料。',
  缺料量: '总需求（订单需求+追加用料）扣除净领料、本单预留和本次可用库存供给后仍不足的数量。有库存可领时可为0，但仍须实际领料后开工。',
};
function renderMaterialHeader({ column }) {
  return h(ElTooltip, { content: materialColumnHelp[column.label], placement: 'top',
    popperStyle: { maxWidth: '360px', whiteSpace: 'normal', lineHeight: '1.6' } },
    { default: () => h('span', { style: { cursor: 'help' } }, `${column.label} ⓘ`) });
}

// 阶段状态与阻塞等级共用一套语义色：灰未开始、蓝进行中、绿已完成、黄风险、红阻塞、白不适用。
const STAGE_META = {
  NOT_STARTED: { color: '#94a3b8', label: '未开始' },
  RUNNING: { color: '#1f6feb', label: '进行中' },
  DONE: { color: '#0f766e', label: '已完成' },
  RISK: { color: '#d98a00', label: '有风险' },
  BLOCKED: { color: '#d64545', label: '已阻塞' },
  NA: { color: '#cbd5e1', label: '不适用' },
};
const LEVEL_META = {
  RED: { color: '#d64545', label: '阻塞', tag: 'danger' },
  YELLOW: { color: '#d98a00', label: '风险', tag: 'warning' },
};
// 时间依据决定用户能否相信“实际时间”，必须显式翻译成中文而不是直接暴露枚举。
const TIME_TYPE_TEXT = {
  LOGGED: '状态日志实录',
  DERIVED: '由报工时间推算',
  MISSING: '无实际时间证据',
  REPORTED_UNVERIFIED: '人工报工，未验证',
};

const activeDetailTab = ref('order');
const activeSupplementTab = ref('materials');
const selectedWorkOrderId = ref('');
// 流程图下方详情区用多标签：点一个阶段加一个标签，原来打开的不会被顶掉，可切换、可单独关闭。
// 具体的打开/关闭逻辑放在数据源 computed 之后，避免在 setup 早期读到还没初始化的 stages。
const openStages = ref([]);
const activeStageCode = ref('');

const order = computed(() => props.progress?.order || null);
const summary = computed(() => props.progress?.summary || null);
const stages = computed(() => props.progress?.stages || []);
const workOrders = computed(() => props.progress?.workOrders || []);
const materials = computed(() => props.progress?.materials || []);
const preparationMaterials = computed(
  () =>
    props.progress?.preparationMaterials ||
    materials.value.filter((row) => !['SEMI', 'SEMI_FINISHED'].includes(row.materialType)),
);
const semiFinishedMaterials = computed(
  () =>
    props.progress?.semiFinishedMaterials ||
    materials.value.filter((row) => ['SEMI', 'SEMI_FINISHED'].includes(row.materialType)),
);
const documents = computed(() => props.progress?.documents || []);
const timeline = computed(() => props.progress?.timeline || []);
const planActual = computed(() => props.progress?.planActual || []);
const dependencies = computed(() => props.progress?.dependencies || []);
const dataQuality = computed(() => props.progress?.dataQuality || null);

// 流程单据沿用同一份订单快照；按明确的工单关联筛选，不用单号或产品名称推测归属。
const processGroups = computed(() => [
  { key: 'order', label: '订单', rows: documents.value.filter((row) => row.type === 'PRODUCTION_ORDER') },
  { key: 'plans', label: '计划', rows: documents.value.filter((row) => row.type === 'PRODUCTION_PLAN') },
  { key: 'workOrders', label: '工单', rows: workOrders.value },
  { key: 'issues', label: '领料单', scoped: true, rows: documents.value.filter((row) => row.type === 'MATERIAL_ISSUE') },
  { key: 'stockInspections', label: '领用检验', scoped: true,
    rows: documents.value.filter((row) => row.type === 'STOCK_INSPECTION') },
  { key: 'tasks', label: '生产任务', scoped: true, rows: documents.value.filter((row) => row.type === 'TASK') },
  { key: 'finalInspections', label: '终检单', scoped: true,
    rows: documents.value.filter((row) => row.type === 'INSPECTION' && /^FINAL(?:\s|$)/.test(row.relation || '')) },
  { key: 'storage', label: '入库与发货', scoped: true,
    rows: documents.value.filter((row) => ['PRODUCTION_INBOUND', 'SALES_OUTBOUND'].includes(row.type)) },
  { key: 'timeline', label: '时间线', rows: timeline.value },
].filter((group) => group.key !== 'stockInspections' || group.rows.length > 0));
const otherDocuments = computed(() => {
  const included = new Set(processGroups.value
    .filter((group) => !['workOrders', 'timeline'].includes(group.key)).flatMap((group) => group.rows));
  return documents.value.filter((row) => row.type !== 'WORK_ORDER' && !included.has(row));
});
const selectedWorkOrder = computed(() => workOrders.value
  .find((row) => String(row.workOrderId) === selectedWorkOrderId.value));
function groupRows(group) {
  if (!group.scoped || !selectedWorkOrderId.value) {
    return group.rows;
  }
  return group.rows.filter((row) => String(row.workOrderId) === selectedWorkOrderId.value);
}
function groupLabel(group) {
  const count = groupRows(group).length;
  return `${group.label} ${count}${group.scoped && selectedWorkOrderId.value ? ` / ${group.rows.length}` : ''}`;
}
function selectWorkOrder(row, tab = 'tasks') {
  selectedWorkOrderId.value = String(row.workOrderId);
  activeDetailTab.value = tab;
}
watch(workOrders, (rows) => {
  if (!rows.some((row) => String(row.workOrderId) === selectedWorkOrderId.value)) {
    selectedWorkOrderId.value = '';
  }
});

// 所有阶段的阻塞（含组件子阶段）集中一处，供图表和提示条共用。
const allBlockers = computed(() => {
  const list = [];
  for (const stage of stages.value) {
    for (const blocker of stage.blockers || []) {
      list.push({ ...blocker, stageName: stage.name });
    }
    function visit(children) {
      for (const child of children || []) {
        for (const blocker of child.blockers || []) {
          list.push({ ...blocker, stageName: `${stage.name} · ${child.name}` });
        }
        visit(child.children);
      }
    }
    visit(stage.children);
  }
  return list.filter(
    (item, index) => list.findIndex((other) => other.code === item.code && other.reason === item.reason) === index,
  );
});
const redBlockers = computed(() => allBlockers.value.filter((item) => item.level === 'RED'));
const yellowBlockers = computed(() => allBlockers.value.filter((item) => item.level === 'YELLOW'));

// 整机根工单决定“可装量”和直接用料范围。
const rootWorkOrders = computed(() => workOrders.value.filter((item) => item.workOrderType === 'ROOT'));
const rootMaterials = computed(() => {
  const ids = new Set(rootWorkOrders.value.map((item) => item.workOrderId));
  return materials.value.filter((item) => ids.has(item.workOrderId));
});

// 备料齐套说的是「共 24 种物料需求」，用户点开这一段就应该能看到具体是哪 24 种，
// 所以按阶段取出对应的用料范围：备料看整单全量，组件子阶段只看自己工单名下的需求。
// 子阶段 code 形如 COMPONENT_65，末位就是工单号，用它和用料行上的 workOrderId 对齐。
function materialsOfStage(item) {
  if (!item?.stage) {
    return [];
  }
  if (item.stage.code === 'MATERIAL_PREP') {
    return preparationMaterials.value;
  }
  if (item.stage.code === 'ASSEMBLY') {
    return rootMaterials.value;
  }
  const matched = /^(?:COMPONENT|ASSEMBLY)_(?<workOrderId>\d+)$/.exec(item.stage.code);
  if (!matched) {
    return [];
  }
  const workOrderId = Number(matched.groups.workOrderId);
  return materials.value.filter((row) => row.workOrderId === workOrderId);
}
// 用料清单顶部的一句话汇总：多少种、齐套多少种、缺多少种，数字和备料阶段的口径一致。
function materialSummary(rows) {
  const kinds = rows.length;
  const covered = rows.filter((row) => Number(row.shortageQuantity) <= 0).length;
  return { kinds, covered, partial: kinds - covered };
}

// 阶段与并行子阶段都可能被点开成详情标签，统一按 code 建索引，点谁开谁。
const stageIndex = computed(() => {
  const map = new Map();
  function visit(rows, parentName = '', parentCode = '') {
    for (const stage of rows) {
      map.set(stage.code, { stage, parentName, parentCode });
      visit(stage.children || [], stage.name, stage.code);
    }
  }
  visit(stages.value);
  return map;
});
const openStageList = computed(() => openStages.value.map((code) => stageIndex.value.get(code)).filter(Boolean));

// 打开或激活一个阶段标签。同一个阶段重复点击只激活，不重复新增。
function selectStage(stage) {
  if (!stage?.code) {
    return;
  }
  if (!openStages.value.includes(stage.code)) {
    openStages.value.push(stage.code);
  }
  activeStageCode.value = stage.code;
}
function closeStage(code) {
  const index = openStages.value.indexOf(code);
  if (index < 0) {
    return;
  }
  openStages.value.splice(index, 1);
  if (activeStageCode.value === code) {
    // 关掉当前标签后落到相邻标签，不要留下一个空白的激活态。
    activeStageCode.value = openStages.value[index] || openStages.value[index - 1] || '';
  }
}
// 进入面板默认打开"当前阶段"，用户不用先点一下才知道现在做到哪。
// 订单全部完成时后端不再给出当前阶段（八段都已完成，没有"正在做"的那一段），
// 这时退到第一个还有动作的阶段；都完成了就落到「订单下发」——
// 它是这张单的起点，显示"已下达 10/10 台"，比停在最后一格「发货」
// 更像一张订单的开头（两者数量相同，停在发货反而像重复了一个阶段）。
watch(
  () => [summary.value?.currentStageCode, stages.value],
  () => {
    if (openStages.value.length) {
      return;
    }
    const list = stages.value || [];
    if (!list.length) {
      return;
    }
    let code = summary.value?.currentStageCode;
    if (!code || !stageIndex.value.has(code)) {
      const active = list.find(({ status }) => ['RUNNING', 'BLOCKED', 'RISK'].includes(status));
      const { code: fallbackCode } = active || list[0];
      code = fallbackCode;
    }
    if (!code) {
      return;
    }
    openStages.value = [code];
    activeStageCode.value = code;
  },
  { immediate: true },
);

watch(
  () => order.value?.productionOrderId,
  (id, previous) => {
    if (!id || id === previous) {
      return;
    }
    openStages.value = [];
    activeStageCode.value = '';
    activeDetailTab.value = 'order';
    activeSupplementTab.value = 'materials';
    selectedWorkOrderId.value = '';
    const current = stageIndex.value.get(summary.value?.currentStageCode) ||
      stageIndex.value.get(stages.value[0]?.code);
    if (current) {
      selectStage(current.stage);
    }
  },
);
watch(stageIndex, (index) => {
  openStages.value = openStages.value.filter((code) => index.has(code));
  if (!openStages.value.includes(activeStageCode.value)) {
    activeStageCode.value = openStages.value[0] || '';
  }
});

function escapeHtml(value) {
  return String(value ?? '').replace(
    /[&<>"']/g,
    (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char],
  );
}
function recordStatus(value) {
  return (
    {
      ROOT: '整机',
      COMPONENT: '组件',
      DRAFT: '草稿',
      RELEASED: '已下达',
      WAIT_START: '待开工',
      RUNNING: '进行中',
      PAUSED: '已暂停',
      FINISHED: '已完成',
      APPROVED: '已确认',
      SUBMITTED: '待审核',
      REJECTED: '已驳回',
      CLOSED: '已关闭',
      PASS: '合格',
      FAIL: '不合格',
      CONCESSION: '让步接收',
      STORED: '已入库',
      WAIT_INSPECTION: '待检验',
      COVERED: '齐套',
      PARTIAL: '部分齐套',
      SHORT: '缺料',
      DONE: '已完成',
      NOT_STARTED: '未开始',
      RISK: '有风险',
      BLOCKED: '已阻塞',
    }[value] ||
    value ||
    '--'
  );
}

function fmtQty(value) {
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '--';
  }
  return Number.isInteger(number) ? String(number) : number.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
}
function fmtTime(value) {
  if (!value) {
    return '--';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('MM-DD HH:mm') : '--';
}
function fmtFullTime(value) {
  if (!value) {
    return '--';
  }
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.format('YYYY-MM-DD HH:mm') : '--';
}
function stageColor(status) {
  return (STAGE_META[status] || STAGE_META.NOT_STARTED).color;
}
// 阶段明细里的状态标签直接用 Element 的语义色，和流程图里的六色保持一致。
const STAGE_TAG_TYPE = {
  DONE: 'success',
  RUNNING: 'primary',
  RISK: 'warning',
  BLOCKED: 'danger',
  NOT_STARTED: 'info',
  NA: 'info',
};
function stageTagType(status) {
  return STAGE_TAG_TYPE[status] || 'info';
}
function stageLabel(status) {
  return (STAGE_META[status] || STAGE_META.NOT_STARTED).label;
}
function timeTypeText(value) {
  return TIME_TYPE_TEXT[value] || '--';
}
// 数量格必须按阶段取口径。只有真正产生报工的阶段才谈"已报工合格/待检验/不合格"；
// 订单下发、排产、备料、入库、发货各有自己的量，硬套同一套只会得到一排 0，
// 让人误以为是数据缺失。这里按阶段给出各自有意义的指标。
function metricsOfStage(item) {
  const stage = item?.stage || {};
  const unit = stage.unit || '';
  const num = (value) => ({ value: fmtQty(value), unit });
  switch (stage.code) {
    case 'ORDER_RELEASE':
      return [
        { label: '订单量', ...num(stage.plannedQuantity) },
        { label: '已下达', ...num(stage.executedQuantity) },
        { label: '未下达', ...num(stage.remainingQuantity) },
      ];
    case 'SCHEDULE':
      return [
        { label: '已排产', ...num(stage.executedQuantity) },
        { label: '未排产', ...num(stage.remainingQuantity) },
        { label: '计划开始', value: fmtTime(stage.plannedStart), isTime: true },
        { label: '计划结束', value: fmtTime(stage.plannedEnd), isTime: true },
      ];
    case 'MATERIAL_PREP':
      // 备料阶段的单位是"种"，后端给的 waitingInspectionQuantity 是跨物料按各自单位累加的
      // 待检数量，混着颗/套/米，标成"种"会误导，所以这里只用需求种数、齐套种数、缺口种数。
      return [
        { label: '需求项数', ...num(stage.plannedQuantity) },
        { label: '齐套项数', ...num(stage.executedQuantity) },
        { label: '缺口项数', ...num(stage.remainingQuantity), danger: Number(stage.remainingQuantity) > 0 },
      ];
    case 'FINAL_INSPECTION':
      return [
        { label: '已完成检验', ...num(stage.executedQuantity) },
        { label: '已放行（含让步）', ...num(stage.releasedQuantity) },
        { label: '待检验', ...num(stage.waitingInspectionQuantity) },
        { label: '不合格', ...num(stage.defectQuantity), danger: Number(stage.defectQuantity) > 0 },
        { label: '尚未放行', ...num(stage.remainingQuantity) },
      ];
    case 'INBOUND':
      return [
        { label: '计划入库', ...num(stage.plannedQuantity) },
        { label: '已入库', ...num(stage.executedQuantity) },
        { label: '剩余', ...num(stage.remainingQuantity) },
      ];
    case 'SHIPMENT':
      return [
        { label: '计划发货', ...num(stage.plannedQuantity) },
        { label: '已发货', ...num(stage.executedQuantity) },
        { label: '待审核', ...num(stage.waitingAuditQuantity) },
        { label: '剩余', ...num(stage.remainingQuantity) },
      ];
    default:
      return [
        { label: '已报工合格', ...num(stage.reportedQualifiedQuantity) },
        { label: '已放行', ...num(stage.releasedQuantity) },
        { label: '待审核', ...num(stage.waitingAuditQuantity) },
        { label: '待检验', ...num(stage.waitingInspectionQuantity) },
        { label: '不合格', ...num(stage.defectQuantity), danger: Number(stage.defectQuantity) > 0 },
        { label: '剩余', ...num(stage.remainingQuantity) },
      ];
  }
}

// 完成率只在计划量为正时有意义，避免 0/0 出现 NaN。
function rate(executed, planned) {
  const plannedNumber = Number(planned);
  if (!Number.isFinite(plannedNumber) || plannedNumber <= 0) {
    return 0;
  }
  return Math.min(100, Math.round(((Number(executed) || 0) / plannedNumber) * 1000) / 10);
}
function openDocument(document) {
  if (!document) {
    return;
  }
  emit('open-document', document);
}
// 阻塞卡片上的建议动作：后端给出 code/label/targetResource/query，具体路由解析交给外层页面，
// 面板只负责把"下一步能做什么"点出来，不在这里判断权限与路径。
function runAction(action) {
  if (!action?.targetResource) {
    return;
  }
  emit('run-action', action);
}

// 图一：时间偏差。八个阶段各一行，横轴是"实际比计划早/晚多少小时"，0 处画基准线。
// 原来画的是绝对时刻甘特图，计划和实际分别在时间轴两端时中间一片空白，看不出差多少；
// 偏差才是决策要看的量，而且不受绝对时间跨度的影响。
const deviationRows = computed(() => {
  const rows = [];
  // 组件生产段自己只有汇总，真正的计划与实际在三条并行子阶段上，展开后才有可比的时间。
  const flat = [];
  for (const stage of stages.value) {
    if (stage.code === 'COMPONENT' && stage.children?.length) {
      flat.push(...stage.children);
    } else {
      flat.push(stage);
    }
  }
  for (const stage of flat) {
    // 订单下发的计划完成时间取的是交期，那是"最晚交付"而不是"这一步计划什么时候做完"，
    // 拿下达时刻去减交期会得到一个几百小时的负数，把其它阶段的偏差全压成看不见的一条线。
    if (stage.code === 'ORDER_RELEASE') {
      continue;
    }
    const planned = stage.plannedEnd ? dayjs(stage.plannedEnd) : null;
    const actual = stage.actualEnd ? dayjs(stage.actualEnd) : null;
    if (!planned?.isValid() || !actual?.isValid()) {
      continue;
    }
    // 正数表示比计划晚（延迟），负数表示提前完成。
    const hours = Math.round((actual.diff(planned, 'minute') / 60) * 10) / 10;
    rows.push({ stage, hours });
  }
  return rows;
});
const deviationOption = computed(() => {
  const rows = deviationRows.value;
  if (!rows.length) {
    return null;
  }
  const values = rows.map((row) => row.hours);
  const bound = Math.max(...values.map((value) => Math.abs(value)), 1);
  return {
    grid: { left: 12, right: 30, top: 24, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const row = rows[params[0]?.dataIndex ?? 0];
        if (!row) {
          return '';
        }
        let judge = '准时';
        if (row.hours < 0) {
          judge = `提前 ${Math.abs(row.hours)} 小时`;
        } else if (row.hours > 0) {
          judge = `延迟 ${row.hours} 小时`;
        }
        return [
          `<strong>${escapeHtml(row.stage.name)}</strong>`,
          `计划完成 ${fmtFullTime(row.stage.plannedEnd)}`,
          `实际完成 ${fmtFullTime(row.stage.actualEnd)}`,
          `偏差 ${judge}`,
          `时间依据 ${timeTypeText(row.stage.actualTimeType)}`,
        ].join('<br/>');
      },
    },
    xAxis: {
      type: 'value',
      min: -bound,
      max: bound,
      axisLabel: { formatter: (value) => `${value}h`, fontSize: 12 },
      splitLine: { lineStyle: { color: '#eef2f7' } },
    },
    yAxis: { type: 'category', inverse: true, data: rows.map((row) => row.stage.name), axisLabel: { fontSize: 12 } },
    series: [
      {
        type: 'bar',
        barMaxWidth: 18,
        // 延迟用橙、提前用绿：颜色本身就表达了好坏，不用再读数字。
        data: rows.map((row) => ({
          value: row.hours,
          itemStyle: {
            color: row.hours > 0 ? '#d98a00' : '#0f766e',
            borderRadius: row.hours > 0 ? [0, 3, 3, 0] : [3, 0, 0, 3],
          },
        })),
        markLine: {
          silent: true,
          symbol: 'none',
          lineStyle: { color: '#94a3b8', width: 1 },
          data: [{ xAxis: 0 }],
          label: { formatter: '按计划', fontSize: 12, color: '#94a3b8' },
        },
      },
    ],
  };
});

// 图三：物料供需。每个条形就是一种物料，单位写在悬浮提示里，
// 因此不同单位的物料可以并列展示而不相加。
const materialOption = computed(() => {
  const rows = [...rootMaterials.value]
    .sort((a, b) => Number(b.shortageQuantity) - Number(a.shortageQuantity))
    .slice(0, 10);
  if (!rows.length) {
    return null;
  }
  const categories = rows.map((row) => row.materialCode);
  const series = [
    { key: 'issuedQuantity', name: '净领料', color: '#1f6feb' },
    { key: 'reservedQuantity', name: '本单预留', color: '#0f766e' },
    { key: 'freeAllocatedQuantity', name: '库存供给', color: '#8b5cf6' },
    { key: 'shortageQuantity', name: '缺料量', color: '#d64545' },
  ];
  return {
    grid: { left: 12, right: 16, top: 30, bottom: 30, containLabel: true },
    legend: { top: 0 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const index = params[0]?.dataIndex ?? 0;
        const row = rows[index];
        if (!row) {
          return '';
        }
        return [
          `<strong>${escapeHtml(row.materialName)}</strong>（${escapeHtml(row.materialCode)}）`,
          `订单需求 ${fmtQty(row.baseRequiredQuantity)} ${row.unit || ''}`,
          `追加用料 ${fmtQty(row.supplementRequiredQuantity)}`,
          `总需求 ${fmtQty(row.requiredQuantity)}`,
          `净领料 ${fmtQty(row.issuedQuantity)}`,
          `本单预留 ${fmtQty(row.reservedQuantity)}`,
          `库存量 ${fmtQty(row.qualifiedStockQuantity)}`,
          `本次库存供给 ${fmtQty(row.freeAllocatedQuantity)}`,
          `缺料量 ${fmtQty(row.shortageQuantity)}`,
          `状态 ${row.status}`,
        ].join('<br/>');
      },
    },
    xAxis: { type: 'category', data: categories, axisLabel: { fontSize: 12, rotate: categories.length > 5 ? 28 : 0 } },
    yAxis: { type: 'value' },
    series: series.map((item) => ({
      name: item.name,
      type: 'bar',
      stack: 'material',
      barMaxWidth: 26,
      itemStyle: { color: item.color },
      data: rows.map((row) => Number(row[item.key]) || 0),
    })),
  };
});

// 图四：阻塞影响。把每条阻塞的受影响数量横向列出，
// 标题带阶段名，让用户一眼看出是哪一段拖住了后面。
const blockerOption = computed(() => {
  const rows = allBlockers.value.filter((item) => Number(item.impact?.blockedQuantity) > 0);
  if (!rows.length) {
    return null;
  }
  return {
    grid: { left: 12, right: 24, top: 20, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params) => {
        const index = params[0]?.dataIndex ?? 0;
        const row = rows[index];
        if (!row) {
          return '';
        }
        const lines = [
          `<strong>${escapeHtml(row.title)}</strong>`,
          `阶段 ${escapeHtml(row.stageName)}`,
          `原因 ${escapeHtml(row.reason || '--')}`,
        ];
        if (row.impact?.explanation) {
          lines.push(escapeHtml(row.impact.explanation));
        }
        return lines.join('<br/>');
      },
    },
    xAxis: { type: 'value' },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map((row) => `${row.stageName}｜${row.title}`),
      axisLabel: { fontSize: 12, width: 220, overflow: 'truncate' },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 18,
        data: rows.map((row) => ({
          value: Number(row.impact?.blockedQuantity) || 0,
          itemStyle: { color: (LEVEL_META[row.level] || LEVEL_META.YELLOW).color, borderRadius: [0, 3, 3, 0] },
        })),
      },
    ],
  };
});

// 单据入口是“从结论跳回原始单据”的关键，类型要翻译成业务名称。
const DOC_TYPE_TEXT = {
  PRODUCTION_ORDER: '生产订单',
  PRODUCTION_PLAN: '生产计划',
  WORK_ORDER: '生产工单',
  DISPATCH: '派工单',
  TASK: '生产任务',
  WORK_REPORT: '报工单',
  PRODUCT_LOT: '产品批次',
  MATERIAL_ISSUE: '领料出库',
  PRODUCTION_INBOUND: '生产入库',
  SALES_OUTBOUND: '销售发货',
  INSPECTION: '检验单',
  STOCK_INSPECTION: '领用检验',
  PURCHASE_ORDER: '采购订单',
  PURCHASE_INBOUND: '到货入库',
  MATERIAL_BATCH: '物料批次',
  OTHER_INBOUND: '来源入库',
  NONCONFORMING: '不合格处置',
  RETURN: '生产退料',
  REPAIR_ORDER: '维修单',
  EQUIPMENT: '设备',
};
function docTypeText(type) {
  return DOC_TYPE_TEXT[type] || type;
}
// 阶段完成率图已删除：流程图已经表达了每个阶段推进到哪，再画一张同样的百分比条形是重复；
// 而且八阶段单位不同（种/套/台），共用一根百分比轴横向排列，容易让人误以为数量可以相比。
const hasAnyChart = computed(() => Boolean(deviationOption.value || materialOption.value || blockerOption.value));
</script>

<template>
  <div class="progress-panel">
    <section v-if="loading" class="panel-state">
      <el-skeleton :rows="8" animated />
    </section>

    <section v-else-if="error" class="panel-state">
      <el-empty :description="error" :image-size="72">
        <el-button type="primary" plain @click="emit('refresh')"
          ><AppIcon name="refresh" :size="15" />重新加载</el-button
        >
      </el-empty>
    </section>

    <template v-else-if="progress">
      <!-- 概要：先回答“这张订单现在到哪一步、还差多少” -->
      <section class="panel-section summary-section">
        <div class="summary-head">
          <div class="summary-title">
            <strong>{{ order?.productionOrderNo }}</strong>
            <el-tag v-if="order?.rehearsal" size="small" type="warning" effect="plain">演练订单</el-tag>
            <el-tag
              size="small"
              :type="
                summary?.overallStatus === 'BLOCKED'
                  ? 'danger'
                  : summary?.overallStatus === 'DONE'
                    ? 'success'
                    : summary?.overallStatus === 'RISK'
                      ? 'warning'
                      : 'info'
              "
            >
              {{ summary?.currentStageName || order?.status }}
            </el-tag>
          </div>
          <div class="summary-sub">
            <span>{{ order?.customerName || '未指定客户' }}</span>
            <span>{{ order?.productName }}（{{ order?.productCode }}）</span>
            <span>订单量 {{ fmtQty(order?.orderQuantity) }} {{ order?.unit }}</span>
            <span>交期 {{ order?.deliveryDate || '--' }}</span>
          </div>
        </div>
        <div class="summary-grid">
          <div class="summary-cell">
            <span>生产完成</span><strong>{{ fmtQty(summary?.productionPercent) }}%</strong>
          </div>
          <div class="summary-cell">
            <span>发货完成</span><strong>{{ fmtQty(summary?.shipmentPercent) }}%</strong>
          </div>
          <div class="summary-cell">
            <span>已放行</span><strong>{{ fmtQty(summary?.releasedQuantity) }}</strong>
          </div>
          <div class="summary-cell">
            <span>已入库</span><strong>{{ fmtQty(summary?.inboundQuantity) }}</strong>
          </div>
          <div class="summary-cell">
            <span>已发货</span><strong>{{ fmtQty(summary?.shippedQuantity) }}</strong>
          </div>
          <div class="summary-cell">
            <span>当前可装</span><strong>{{ fmtQty(summary?.maxAssemblableQuantity) }}</strong>
          </div>
          <div class="summary-cell">
            <span>计划完工</span><strong class="is-time">{{ fmtTime(summary?.plannedFinishTime) }}</strong>
          </div>
          <div class="summary-cell">
            <span>预计完工</span><strong class="is-time">{{ fmtTime(summary?.predictedFinishTime) }}</strong>
          </div>
          <div class="summary-cell">
            <span>阻塞 / 风险</span>
            <strong :class="summary?.blockerCount ? 'is-danger' : summary?.riskCount ? 'is-warning' : ''">
              {{ summary?.blockerCount || 0 }} / {{ summary?.riskCount || 0 }}
            </strong>
          </div>
        </div>
        <p v-if="summary?.predictionBasis" class="summary-basis">预计完工依据：{{ summary.predictionBasis }}</p>
      </section>

      <!-- 阻塞提示条：红色必须能点开看到证据单据 -->
      <section v-if="redBlockers.length || yellowBlockers.length" class="panel-section blocker-strip">
        <article
          v-for="(blocker, index) in [...redBlockers, ...yellowBlockers].slice(0, 6)"
          :key="`${blocker.code}-${index}`"
          class="blocker-card"
          :class="blocker.level === 'RED' ? 'is-red' : 'is-yellow'"
        >
          <header>
            <span class="blocker-level">{{ (LEVEL_META[blocker.level] || LEVEL_META.YELLOW).label }}</span>
            <strong>{{ blocker.title }}</strong>
            <span class="blocker-stage">{{ blocker.stageName }}</span>
          </header>
          <p>{{ blocker.reason }}</p>
          <div v-if="blocker.evidence?.length" class="blocker-evidence">
            <button
              v-for="(evidence, evidenceIndex) in blocker.evidence"
              :key="evidenceIndex"
              type="button"
              class="doc-chip"
              :disabled="!evidence.document"
              @click="openDocument(evidence.document)"
            >
              <AppIcon name="link" :size="12" />
              <span>{{ evidence.label }}：{{ evidence.value }}</span>
            </button>
          </div>
          <p v-if="blocker.impact?.explanation" class="blocker-impact">{{ blocker.impact.explanation }}</p>
          <!-- 光看到原因不够，还要知道下一步能做什么：动作由后端按阻塞类型给出 -->
          <div v-if="blocker.actions?.length" class="blocker-actions">
            <span class="blocker-actions-label">可以这样处理</span>
            <el-button
              v-for="action in blocker.actions"
              :key="action.code"
              size="small"
              type="primary"
              plain
              @click="runAction(action)"
              >{{ action.label }}</el-button
            >
          </div>
        </article>
      </section>

      <!-- 八阶段主流程：画成一条链，组件段分叉再汇合，一眼看出做到哪、卡在哪 -->
      <section class="panel-section">
        <header class="section-head">
          <strong>订单全过程</strong>
        </header>
        <OrderStageFlow
          :stages="stages"
          :current-code="summary?.currentStageCode || ''"
          :selected-code="activeStageCode"
          @select="selectStage"
        />
      </section>

      <!-- 阶段明细：点流程图里的阶段在下边新开一个标签，原来打开的不消失，可切换、可单独关闭 -->
      <section class="panel-section">
        <header class="section-head">
          <strong>阶段明细</strong>
        </header>
        <div v-if="openStageList.length" class="stage-tabs">
          <div class="stage-tab-bar">
            <button
              v-for="item in openStageList"
              :key="item.stage.code"
              type="button"
              class="stage-tab"
              :class="{ 'is-active': item.stage.code === activeStageCode }"
              @click="activeStageCode = item.stage.code"
            >
              <i class="stage-tab-dot" :style="{ background: stageColor(item.stage.status) }" />
              <span class="stage-tab-name"
                >{{ item.parentName ? `${item.parentName} · ` : '' }}{{ item.stage.name }}</span
              >
              <span v-if="(item.stage.blockers?.length || 0) > 0" class="stage-tab-flag">{{
                item.stage.blockers.length
              }}</span>
              <span class="stage-tab-close" role="button" aria-label="关闭" @click.stop="closeStage(item.stage.code)"
                >×</span
              >
            </button>
          </div>

          <div
            v-for="item in openStageList"
            v-show="item.stage.code === activeStageCode"
            :key="`pane-${item.stage.code}`"
            class="stage-pane"
          >
            <div class="stage-pane-head">
              <div class="stage-pane-title">
                <strong>{{ item.parentName ? `${item.parentName} · ` : '' }}{{ item.stage.name }}</strong>
                <el-tag size="small" :type="stageTagType(item.stage.status)" effect="plain">{{
                  stageLabel(item.stage.status)
                }}</el-tag>
                <span class="stage-pane-basis">时间依据：{{ timeTypeText(item.stage.actualTimeType) }}</span>
              </div>
              <div class="stage-pane-qty">
                <span class="stage-pane-num"
                  >{{ fmtQty(item.stage.executedQuantity)
                  }}<small>/ {{ fmtQty(item.stage.plannedQuantity) }} {{ item.stage.unit }}</small></span
                >
                <span class="stage-pane-bar"
                  ><i
                    :style="{
                      width: rate(item.stage.executedQuantity, item.stage.plannedQuantity) + '%',
                      background: stageColor(item.stage.status),
                    }"
                /></span>
                <span class="stage-pane-rate"
                  >{{ rate(item.stage.executedQuantity, item.stage.plannedQuantity) }}%</span
                >
              </div>
            </div>

            <!-- 数量指标与责任人/时间分成左右两块并排：左边六格横铺，右边三项竖排，
                 整行都能用上，不会像原来那样一列堆到底、右边留一大片空白 -->
            <div class="stage-pane-body">
              <div class="stage-metrics">
                <div v-for="metric in metricsOfStage(item)" :key="metric.label" class="metric">
                  <span>{{ metric.label }}</span>
                  <strong :class="{ 'is-danger': metric.danger }">{{ metric.value }}</strong>
                  <em v-if="!metric.isTime">{{ metric.unit }}</em>
                </div>
              </div>
              <div class="stage-meta">
                <div>
                  <span>责任人</span
                  ><strong>{{
                    item.stage.owner?.handlerName || item.stage.owner?.leaderName || item.stage.owner?.teamName || '--'
                  }}</strong>
                </div>
                <div>
                  <span>计划</span
                  ><strong class="is-num"
                    >{{ fmtTime(item.stage.plannedStart) }} → {{ fmtTime(item.stage.plannedEnd) }}</strong
                  >
                </div>
                <div>
                  <span>实际</span
                  ><strong class="is-num"
                    >{{ fmtTime(item.stage.actualStart) }} → {{ fmtTime(item.stage.actualEnd) }}</strong
                  >
                </div>
              </div>
            </div>

            <!-- 备料看基础物料；装配和组件阶段仍保留本级直接用料，包括半成品。 -->
            <div v-if="materialsOfStage(item).length" class="stage-materials">
              <div class="stage-sub-head">
                <strong>{{ item.stage.code === 'MATERIAL_PREP' ? '基础物料明细' : '用料明细' }}</strong>
                <small>
                  共 {{ materialSummary(materialsOfStage(item)).kinds }} 项需求， 齐套
                  {{ materialSummary(materialsOfStage(item)).covered }} 项
                  <span v-if="materialSummary(materialsOfStage(item)).partial > 0" class="is-danger"
                    >，缺 {{ materialSummary(materialsOfStage(item)).partial }} 项</span
                  >
                </small>
              </div>
              <el-table :data="materialsOfStage(item)" border size="small" max-height="260" class="material-table">
                <el-table-column label="物料" min-width="210" show-overflow-tooltip>
                  <template #default="scope">
                    <span class="mat-name">{{ scope.row.materialName }}</span>
                    <small class="muted">{{ scope.row.materialCode }}</small>
                  </template>
                </el-table-column>
                <el-table-column label="订单需求" width="112" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope"
                    >{{ fmtQty(scope.row.baseRequiredQuantity) }}
                    <small class="muted">{{ scope.row.unit }}</small></template
                  >
                </el-table-column>
                <el-table-column label="追加用料" width="112" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope">{{ fmtQty(scope.row.supplementRequiredQuantity) }}</template>
                </el-table-column>
                <el-table-column label="净领料" width="100" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope">{{ fmtQty(scope.row.issuedQuantity) }}</template>
                </el-table-column>
                <el-table-column label="本单预留" width="112" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope">{{ fmtQty(scope.row.reservedQuantity) }}</template>
                </el-table-column>
                <el-table-column label="库存量" width="100" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope">{{ fmtQty(scope.row.qualifiedStockQuantity) }}</template>
                </el-table-column>
                <el-table-column label="缺料量" width="100" align="right" :render-header="renderMaterialHeader">
                  <template #default="scope">
                    <span :class="{ 'is-danger': Number(scope.row.shortageQuantity) > 0 }">{{
                      fmtQty(scope.row.shortageQuantity)
                    }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="状态" width="86">
                  <template #default="scope">
                    <el-tag
                      size="small"
                      effect="plain"
                      :type="
                        Number(scope.row.shortageQuantity) > 0
                          ? Number(scope.row.issuedQuantity) +
                              Number(scope.row.reservedQuantity) +
                              Number(scope.row.freeAllocatedQuantity) >
                            0
                            ? 'warning'
                            : 'danger'
                          : 'success'
                      "
                    >
                      {{
                        Number(scope.row.shortageQuantity) > 0
                          ? Number(scope.row.issuedQuantity) +
                              Number(scope.row.reservedQuantity) +
                              Number(scope.row.freeAllocatedQuantity) >
                            0
                            ? '部分齐套'
                            : '缺口'
                          : '齐套'
                      }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </div>
            <SemiFinishedCoverageTable
              v-if="item.stage.code === 'MATERIAL_PREP' && semiFinishedMaterials.length"
              :rows="semiFinishedMaterials"
            />

            <div v-if="item.stage.children?.length" class="stage-children">
              <button
                type="button"
                v-for="child in item.stage.children"
                :key="child.code"
                class="stage-child"
                @click="selectStage(child)"
                :style="{ '--stage-color': stageColor(child.status) }"
              >
                <span class="child-name">{{ child.name }}</span>
                <span class="child-status">{{ stageLabel(child.status) }}</span>
                <span class="child-qty"
                  >{{ fmtQty(child.executedQuantity) }} / {{ fmtQty(child.plannedQuantity) }} {{ child.unit }}</span
                >
                <span class="child-time">{{ timeTypeText(child.actualTimeType) }}</span>
                <span class="child-open">查看记录</span>
              </button>
            </div>

            <div v-if="item.stage.blockers?.length" class="stage-blockers">
              <p
                v-for="(blocker, index) in item.stage.blockers"
                :key="index"
                :class="blocker.level === 'RED' ? 'is-danger' : 'is-warning'"
              >
                <strong>{{ blocker.title }}</strong
                >：{{ blocker.reason }}
                <span v-if="blocker.actions?.length" class="stage-blocker-actions">
                  <el-button
                    v-for="action in blocker.actions"
                    :key="action.code"
                    link
                    type="primary"
                    size="small"
                    @click="runAction(action)"
                    >{{ action.label }}</el-button
                  >
                </span>
              </p>
            </div>

            <div v-if="item.stage.documents?.length" class="stage-docs">
              <button
                v-for="document in item.stage.documents"
                :key="`${document.type}-${document.docId}`"
                type="button"
                class="doc-chip"
                @click="openDocument(document)"
              >
                <AppIcon name="link" :size="12" />
                <span>{{ docTypeText(document.type) }}：{{ document.docNo }}</span>
              </button>
            </div>

            <ul v-if="item.stage.notes?.length" class="stage-notes">
              <li v-for="(note, index) in item.stage.notes" :key="index">{{ note }}</li>
            </ul>
          </div>
        </div>
        <p v-else class="stage-tabs-empty">点击上方流程图里的任意阶段，这里会显示该阶段的作业明细。</p>
      </section>

      <!-- 关键视图：时间偏差、用料供需、阻塞影响；无数据的图不占版面 -->
      <section class="panel-section">
        <header class="section-head">
          <strong>关键视图</strong>
        </header>
        <div class="chart-grid">
          <div class="chart-card">
            <h4>时间偏差</h4>
            <MesChart v-if="deviationOption" :option="deviationOption" height="260px" />
            <el-empty v-else description="暂无计划与实际时间可对比" :image-size="56" />
          </div>
          <div class="chart-card">
            <h4>整机直接用料供需</h4>
            <MesChart v-if="materialOption" :option="materialOption" height="260px" />
            <el-empty v-else description="暂无用料需求" :image-size="56" />
          </div>
          <div v-if="blockerOption" class="chart-card">
            <h4>阻塞影响数量</h4>
            <MesChart :option="blockerOption" height="260px" />
          </div>
        </div>
        <p v-if="!hasAnyChart" class="chart-hint">该订单还没有计划、用料或阻塞数据，图表暂不展示。</p>
      </section>

      <!-- 沿生产流程查看单据，筛选只作用于有明确工单关联的流程明细。 -->
      <section class="panel-section process-documents">
        <header class="section-head">
          <strong>流程单据</strong>
          <small>按订单到发货的办理顺序查看</small>
        </header>
        <el-tabs v-model="activeDetailTab" class="detail-tabs process-tabs">
          <el-tab-pane v-for="group in processGroups" :key="group.key" :label="groupLabel(group)" :name="group.key">
            <div v-if="group.scoped && workOrders.length" class="work-order-filter">
              <div class="work-order-filter-heading">
                <strong>工单范围</strong>
                <span>按工单查看本页单据，也可切回全部工单。</span>
              </div>
              <div class="work-order-options" role="group" aria-label="工单范围">
                <button type="button" class="work-order-option" :class="{ selected: !selectedWorkOrderId }"
                  :aria-pressed="!selectedWorkOrderId" @click="selectedWorkOrderId = ''">
                  <strong>全部工单</strong><small>整个订单 · {{ workOrders.length }} 张工单</small>
                </button>
                <button v-for="workOrder in workOrders" :key="workOrder.workOrderId" type="button"
                  class="work-order-option" :class="{ selected: selectedWorkOrderId === String(workOrder.workOrderId) }"
                  :aria-pressed="selectedWorkOrderId === String(workOrder.workOrderId)"
                  @click="selectedWorkOrderId = String(workOrder.workOrderId)">
                  <strong>{{ workOrder.productName || workOrder.productCode || '生产工单' }}</strong>
                  <small>{{ workOrder.workOrderNo }}</small>
                </button>
              </div>
            </div>
            <template v-if="group.key === 'workOrders'">
            <p class="muted">点击工单行查看对应生产任务，也可从右侧查看该工单的终检单。</p>
            <el-table :data="workOrders" border size="small" empty-text="暂无工单"
              row-key="workOrderId" :current-row-key="selectedWorkOrder?.workOrderId" highlight-current-row
              class="process-work-orders" @row-click="selectWorkOrder($event)">
              <el-table-column prop="workOrderNo" label="工单号" min-width="190" show-overflow-tooltip />
              <el-table-column label="类型" width="96"
                ><template #default="scope">{{ recordStatus(scope.row.workOrderType) }}</template></el-table-column
              >
              <el-table-column label="产品" min-width="150" show-overflow-tooltip>
                <template #default="scope">{{ scope.row.productName || scope.row.productCode }}</template>
              </el-table-column>
              <el-table-column label="计划 / 报工" width="122" align="right">
                <template #default="scope"
                  >{{ fmtQty(scope.row.plannedQuantity) }} / {{ fmtQty(scope.row.reportedQuantity) }}</template
                >
              </el-table-column>
              <el-table-column label="放行 / 入库" width="122" align="right">
                <template #default="scope"
                  >{{ fmtQty(scope.row.releasedQuantity) }} / {{ fmtQty(scope.row.inboundQuantity) }}</template
                >
              </el-table-column>
              <el-table-column label="状态" width="96"
                ><template #default="scope">{{ recordStatus(scope.row.status) }}</template></el-table-column
              >
              <el-table-column label="产线 / 班组" min-width="170" show-overflow-tooltip>
                <template #default="scope"
                  >{{ scope.row.lineName || '--' }} / {{ scope.row.owner?.teamName || '--' }}</template
                >
              </el-table-column>
              <el-table-column label="实际时间" min-width="200">
                <template #default="scope">
                  <span>{{ fmtTime(scope.row.actualStart) }} → {{ fmtTime(scope.row.actualEnd) }}</span>
                  <small class="muted">（{{ timeTypeText(scope.row.actualTimeType) }}）</small>
                </template>
              </el-table-column>
              <el-table-column label="工艺依据" min-width="150">
                <template #default="scope">
                  <span class="muted"
                    >BOM {{ scope.row.bomVersion || '--' }} / 路线 {{ scope.row.routeVersion || '--' }}</span
                  >
                </template>
              </el-table-column>
              <el-table-column label="操作" width="165" fixed="right" align="center">
                <template #default="scope">
                  <el-button link type="primary" @click.stop="selectWorkOrder(scope.row)">任务</el-button>
                  <el-button link type="primary" @click.stop="selectWorkOrder(scope.row, 'finalInspections')">
                    终检
                  </el-button>
                  <el-button link type="primary" @click.stop="emit('open-work-order', scope.row)">详情</el-button>
                </template>
              </el-table-column>
            </el-table>
            </template>
            <template v-else-if="group.key === 'timeline'">
            <el-timeline v-if="timeline.length" class="timeline-list">
              <el-timeline-item
                v-for="(event, index) in timeline"
                :key="index"
                :timestamp="fmtFullTime(event.time)"
                placement="top"
              >
                <div class="timeline-row">
                  <span>{{ event.text }}</span>
                  <span class="muted">{{ timeTypeText(event.timeType) }}</span>
                  <button v-if="event.document" type="button" class="doc-chip" @click="openDocument(event.document)">
                    <AppIcon name="link" :size="12" /><span>{{ event.document.docNo }}</span>
                  </button>
                </div>
              </el-timeline-item>
            </el-timeline>
            <el-empty v-else description="暂无时间线事件" :image-size="56" />
            </template>
            <template v-else>
              <p class="process-scope-label">
                <template v-if="group.scoped && selectedWorkOrder">
                  当前工单：{{ selectedWorkOrder.productName }} · {{ selectedWorkOrder.workOrderNo }}
                  <el-button link type="primary" @click="selectedWorkOrderId = ''">显示全部工单</el-button>
                </template>
                <template v-else>当前范围：整个订单</template>
                <span> · 共 {{ groupRows(group).length }} 条</span>
                <span v-if="group.key === 'finalInspections'"> · 仅生产终检，含组件和整机；过程检、出货检见补充资料。</span>
              </p>
              <OrderDocumentTable :rows="groupRows(group)" :work-orders="workOrders"
                :empty-text="selectedWorkOrderId && group.scoped ? `该工单暂无${group.label}` : `该订单暂无${group.label}`"
                :type-label="docTypeText" :status-label="recordStatus" :format-quantity="fmtQty"
                @open-document="openDocument" />
            </template>
          </el-tab-pane>
        </el-tabs>
      </section>

      <section class="panel-section supplement-section">
        <el-collapse>
          <el-collapse-item name="supplements" title="补充资料：物料、计划与实际、其他单据及依赖">
            <p class="muted">补充资料始终展示整个订单，不受上方工单范围影响。派工单、报工单和其他检验见“其他单据”。</p>
            <el-tabs v-model="activeSupplementTab" class="detail-tabs supplement-tabs">
          <el-tab-pane :label="`物料 ${preparationMaterials.length}`" name="materials">
            <p class="muted">
              基础物料：生成工单前按有效计划和已发布BOM预览；生成后以实际冻结需求为准。预览不预留库存。
              订单需求不含追加用料，齐套与缺料量按两者合计计算；净领料为领料扣退料。
            </p>
            <el-table :data="preparationMaterials" border size="small" empty-text="暂无基础物料需求">
              <el-table-column prop="materialCode" label="物料" width="150" show-overflow-tooltip />
              <el-table-column prop="materialName" label="名称" min-width="140" show-overflow-tooltip />
              <el-table-column label="需求来源" width="180" show-overflow-tooltip>
                <template #default="scope">{{ scope.row.workOrderNo }}</template>
              </el-table-column>
              <el-table-column label="订单需求" width="112" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">{{ fmtQty(scope.row.baseRequiredQuantity) }} {{ scope.row.unit }}</template>
              </el-table-column>
              <el-table-column label="追加用料" width="112" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">{{ fmtQty(scope.row.supplementRequiredQuantity) }}</template>
              </el-table-column>
              <el-table-column label="净领料" width="100" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">{{ fmtQty(scope.row.issuedQuantity) }}</template>
              </el-table-column>
              <el-table-column label="本单预留" width="112" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">{{ fmtQty(scope.row.reservedQuantity) }}</template>
              </el-table-column>
              <el-table-column label="库存量" width="100" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">{{ fmtQty(scope.row.qualifiedStockQuantity) }}</template>
              </el-table-column>
              <el-table-column label="待检" width="78" align="right">
                <template #default="scope">{{ fmtQty(scope.row.waitingInspectionQuantity) }}</template>
              </el-table-column>
              <el-table-column label="缺料量" width="100" align="right" :render-header="renderMaterialHeader">
                <template #default="scope">
                  <span :class="Number(scope.row.shortageQuantity) > 0 ? 'is-danger' : ''">{{
                    fmtQty(scope.row.shortageQuantity)
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="92">
                <template #default="scope">
                  <el-tag
                    size="small"
                    :type="
                      scope.row.status === 'COVERED' ? 'success' : scope.row.status === 'PARTIAL' ? 'warning' : 'danger'
                    "
                    >{{ recordStatus(scope.row.status) }}</el-tag
                  >
                </template>
              </el-table-column>
              <el-table-column label="来源" min-width="170">
                <template #default="scope">
                  <button
                    v-for="source in scope.row.sources || []"
                    :key="source.docId"
                    type="button"
                    class="doc-chip"
                    @click="openDocument(source)"
                  >
                    <AppIcon name="link" :size="12" /><span>{{ source.docNo }}</span>
                  </button>
                  <span v-if="!scope.row.sources?.length" class="muted">--</span>
                </template>
              </el-table-column>
            </el-table>
            <SemiFinishedCoverageTable v-if="semiFinishedMaterials.length" :rows="semiFinishedMaterials" />
          </el-tab-pane>
          <el-tab-pane :label="`计划与实际 ${planActual.length}`" name="planActual">
            <el-table :data="planActual" border size="small" empty-text="暂无计划">
              <el-table-column prop="label" label="对象" min-width="230" show-overflow-tooltip />
              <el-table-column prop="group" label="分组" width="90" />
              <el-table-column label="计划时间" min-width="200">
                <template #default="scope"
                  >{{ fmtFullTime(scope.row.plannedStart) }} → {{ fmtFullTime(scope.row.plannedEnd) }}</template
                >
              </el-table-column>
              <el-table-column label="实际时间" min-width="200">
                <template #default="scope">
                  <span v-if="scope.row.actualStart"
                    >{{ fmtFullTime(scope.row.actualStart) }} → {{ fmtFullTime(scope.row.actualEnd) }}</span
                  >
                  <span v-else class="muted">暂无证据</span>
                </template>
              </el-table-column>
              <el-table-column label="时间依据" width="140">
                <template #default="scope">{{ timeTypeText(scope.row.actualTimeType) }}</template>
              </el-table-column>
              <el-table-column label="状态" width="96"
                ><template #default="scope">{{ recordStatus(scope.row.status) }}</template></el-table-column
              >
            </el-table>
          </el-tab-pane>
              <el-tab-pane :label="`其他单据 ${otherDocuments.length}`" name="otherDocuments">
                <OrderDocumentTable :rows="otherDocuments" :work-orders="workOrders" empty-text="暂无其他关联单据"
                  :type-label="docTypeText" :status-label="recordStatus" :format-quantity="fmtQty"
                  @open-document="openDocument" />
              </el-tab-pane>
          <el-tab-pane :label="`依赖 ${dependencies.length}`" name="dependencies">
            <el-table :data="dependencies" border size="small" empty-text="暂无依赖关系">
              <el-table-column prop="from" label="上游" min-width="190" show-overflow-tooltip />
              <el-table-column prop="to" label="下游" min-width="190" show-overflow-tooltip />
              <el-table-column prop="label" label="关系" width="140" />
              <el-table-column label="状态" width="96"
                ><template #default="scope">{{ recordStatus(scope.row.status) }}</template></el-table-column
              >
              <el-table-column prop="reason" label="说明" min-width="220" show-overflow-tooltip />
            </el-table>
          </el-tab-pane>
            </el-tabs>
          </el-collapse-item>
        </el-collapse>
      </section>

      <!-- 数据质量：让用户知道哪些结论是靠推断、哪些量表还不能当真 -->
      <section v-if="dataQuality" class="panel-section quality-section">
        <header class="section-head">
          <strong>数据说明</strong>
          <small>{{
            dataQuality.snapshotMode === 'CURRENT_STATE' ? '当前状态快照，不是历史回放' : dataQuality.snapshotMode
          }}</small>
          <el-tag v-if="dataQuality.degraded" size="small" type="warning" effect="plain">部分依据缺失</el-tag>
        </header>
        <ul class="quality-list">
          <li v-for="(note, index) in dataQuality.notes || []" :key="`note-${index}`">{{ note }}</li>
          <li v-for="(item, index) in dataQuality.missing || []" :key="`missing-${index}`" class="is-warning">
            缺失：{{ item }}
          </li>
        </ul>
        <div v-if="dataQuality.agingVerification?.length" class="aging-box">
          <strong>老化验证范围</strong>
          <ul>
            <li v-for="(item, index) in dataQuality.agingVerification" :key="index">{{ item }}</li>
          </ul>
        </div>
        <p class="quality-foot">
          共关联 {{ dataQuality.documentCount }} 张单据；快照时间 {{ fmtFullTime(progress.evaluatedAt) }}。
        </p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.work-order-filter {
  margin: 0 0 12px;
  padding: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #f8fafc;
}
.work-order-filter-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 12px;
  margin-bottom: 10px;
}
.work-order-filter-heading span,
.process-scope-label {
  color: var(--mes-text-muted);
  font-size: 12px;
}
.work-order-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.work-order-option {
  display: flex;
  min-width: 145px;
  max-width: 100%;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: #fff;
  color: var(--mes-text-secondary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: transform .16s ease, box-shadow .16s ease, border-color .16s ease;
}
.work-order-option small {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.work-order-option:hover {
  border-color: #b5c8e1;
  background: #fbfcff;
  transform: translateY(-2px);
  box-shadow: 0 3px 8px rgb(45 75 110 / 7%);
}
.work-order-option.selected,
.work-order-option.selected:hover {
  border-color: #9cb8dc;
  background: #f4f8fe;
}
.work-order-option:active {
  transform: translateY(0) scale(.99);
  box-shadow: none;
}
.work-order-option.selected strong {
  color: #315d91;
}
.work-order-option:focus-visible {
  outline: 2px solid #b5c8e1;
  outline-offset: 2px;
}
@media (prefers-reduced-motion: reduce) {
  .work-order-option {
    transition: none;
    transform: none;
  }
  .work-order-option:hover,
  .work-order-option:active {
    transform: none;
  }
}
.process-work-orders :deep(tbody tr) {
  cursor: pointer;
}
.process-scope-label {
  margin: 0 0 12px;
  line-height: 1.8;
}
.supplement-section {
  padding: 0 14px;
}
.supplement-section :deep(.el-collapse),
.supplement-section :deep(.el-collapse-item__wrap) {
  border: 0;
}
.supplement-section :deep(.el-collapse-item__header) {
  font-weight: 600;
  color: var(--mes-text-secondary);
}
.progress-panel {
  --mes-text-muted: #63748a;
  --mes-text-secondary: #405269;
  font-size: 14px;
  line-height: 1.65;
  background: #f3f6fa;
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 18px;
  padding: 20px 22px 28px;
  overflow: auto;
}
.panel-state {
  padding: 20px 4px;
}
.panel-section {
  flex: 0 0 auto;
  border: 1px solid var(--mes-border);
  border-radius: 10px;
  background: var(--mes-surface);
  overflow: hidden;
}
.section-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--mes-border);
}
.section-head strong {
  font-size: 15px;
}
.section-head :deep(.el-tag) {
  margin-left: auto;
}

.summary-section {
  padding: 20px;
  border-top: 3px solid #2871c9;
}
.summary-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  justify-content: space-between;
}
.summary-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.summary-title strong {
  font-size: 17px;
  font-variant-numeric: tabular-nums;
}
.summary-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--mes-text-secondary);
  font-size: 13px;
}
.summary-grid {
  display: grid;
  margin-top: 12px;
  gap: 8px;
  grid-template-columns: repeat(auto-fit, minmax(112px, 1fr));
}
.summary-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border: 1px solid #eef2f7;
  border-radius: 5px;
  background: #fafcfe;
}
.summary-cell span {
  color: var(--mes-text-muted);
  font-size: 12px;
}
.summary-cell strong {
  font-size: 16px;
  font-variant-numeric: tabular-nums;
}
.summary-cell strong.is-time {
  font-size: 14px;
}
.summary-basis {
  margin-top: 9px;
  color: var(--mes-text-muted);
  font-size: 13px;
  line-height: 1.5;
}
.is-danger {
  color: #d64545;
}
.is-warning {
  color: #d98a00;
}
.muted {
  color: var(--mes-text-muted);
}

.blocker-strip {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
}
.blocker-card {
  padding: 8px 10px;
  border: 1px solid #f0d9a8;
  border-left: 1px solid #d98a00;
  border-radius: 5px;
  background: #fffbf0;
}
.blocker-card.is-red {
  border-color: #f3c4c0;
  border-left-color: #d64545;
  background: #fff5f4;
}
.blocker-card header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.blocker-level {
  padding: 1px 5px;
  color: #fff;
  border-radius: 3px;
  background: #d98a00;
  font-size: 12px;
}
.blocker-card.is-red .blocker-level {
  background: #d64545;
}
.blocker-card header strong {
  font-size: 14px;
}
.blocker-stage {
  margin-left: auto;
  color: var(--mes-text-muted);
  font-size: 12px;
}
.blocker-card p {
  margin-top: 5px;
  color: var(--mes-text-secondary);
  font-size: 13px;
  line-height: 1.55;
}
.blocker-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 5px;
  margin-top: 7px;
  padding-top: 6px;
  border-top: 1px dashed #eadfc8;
}
.blocker-actions-label {
  color: var(--mes-text-muted);
  font-size: 12px;
}
/* 动作单独占一行，否则按钮会接在原因文字末尾，看起来像那句话的一部分。 */
.stage-blocker-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 2px;
}
.blocker-evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 6px;
}
.blocker-impact {
  color: #8f3029 !important;
}

/* 阶段明细标签区：点流程图新开一个标签，原来打开的不消失，可切换对照。 */
.stage-tabs {
  padding: 0 12px 12px;
}
.stage-tab-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 10px;
  border-bottom: 1px solid #eef2f7;
}
.stage-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  color: var(--mes-text-secondary);
  border: 1px solid transparent;
  border-bottom: 2px solid transparent;
  border-radius: 4px 4px 0 0;
  background: transparent;
  font-size: 14px;
  cursor: pointer;
}
.stage-tab:hover {
  background: #f5f8fc;
}
.stage-tab.is-active {
  color: #1552b8;
  border-color: #e8edf4;
  border-bottom-color: #1f6feb;
  background: #fff;
  font-weight: 600;
}
.stage-tab-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.stage-tab-name {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stage-tab-flag {
  padding: 0 5px;
  color: #fff;
  border-radius: 8px;
  background: #d64545;
  font-size: 12px;
}
.stage-tab-close {
  width: 14px;
  height: 14px;
  color: #98a6ba;
  border-radius: 3px;
  font-size: 15px;
  line-height: 12px;
  text-align: center;
}
.stage-tab-close:hover {
  color: #d64545;
  background: #fdeeee;
}

/* 明细面板：标题一行（名称+状态标签+时间依据），右侧数量与进度条；下面指标格子横排。
   原来九项 dt/dd 竖着排两列，一屏就占满了还显得空，改成格子后信息密度翻倍且对齐。 */
.stage-pane {
  padding: 12px 14px 14px;
  border: 1px solid #eef2f7;
  border-radius: 0 5px 5px 5px;
  background: #fff;
}
.stage-pane-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 9px;
  border-bottom: 1px dashed #eef2f7;
}
.stage-pane-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.stage-pane-title strong {
  font-size: 17px;
}
.stage-pane-basis {
  color: var(--mes-text-muted);
  font-size: 13px;
}
.stage-pane-qty {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  color: var(--mes-text-secondary);
  font-variant-numeric: tabular-nums;
}
.stage-pane-num {
  font-size: 18px;
  font-weight: 600;
  color: var(--mes-text);
}
.stage-pane-num small {
  margin-left: 2px;
  color: var(--mes-text-muted);
  font-size: 13px;
  font-weight: 400;
}
.stage-pane-bar {
  display: block;
  width: 132px;
  height: 6px;
  border-radius: 3px;
  background: #eef2f7;
}
.stage-pane-bar i {
  display: block;
  height: 100%;
  border-radius: 3px;
}
.stage-pane-rate {
  color: var(--mes-text-muted);
  font-size: 13px;
}
.stage-tabs-empty {
  padding: 0 14px 14px;
  color: var(--mes-text-muted);
  font-size: 13px;
}

/* 明细主体：左边数量格、右边责任人/计划/实际，左右并排把一整行都用上。
   数量格用 auto-fit 而不是固定三列：不同阶段的指标个数是 3~6 个，
   固定三列会让 4 个指标的那一行末尾空出两格。 */
.stage-pane-body {
  display: grid;
  margin-top: 11px;
  gap: 10px 14px;
  grid-template-columns: minmax(0, 1fr) 320px;
  align-items: start;
}
.stage-metrics {
  display: grid;
  gap: 7px;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 10px 12px;
  border: 1px solid #eef2f7;
  border-radius: 5px;
  background: #fafcfe;
}
.metric span {
  color: var(--mes-text-muted);
  font-size: 12px;
}
.metric strong {
  font-size: 17px;
  line-height: 1.15;
  font-variant-numeric: tabular-nums;
}
.metric em {
  color: var(--mes-text-muted);
  font-size: 12px;
  font-style: normal;
}

/* 责任人/计划/实际：右侧竖排三项，标签与值左对齐，时间用等宽数字 */
.stage-meta {
  display: grid;
  gap: 6px;
}
.stage-meta > div {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  padding-bottom: 5px;
  border-bottom: 1px dashed #f1f5fa;
}
.stage-meta > div:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}
.stage-meta span {
  flex: 0 0 44px;
  color: var(--mes-text-muted);
  font-size: 12px;
}
.stage-meta strong {
  min-width: 0;
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stage-meta strong.is-num {
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

@media (max-width: 1080px) {
  .stage-pane-body {
    grid-template-columns: minmax(0, 1fr);
  }
  .stage-metrics {
    grid-template-columns: repeat(auto-fit, minmax(104px, 1fr));
  }
}

/* 用料明细：备料与组件阶段把需求逐种列出来，弥补"共 N 种"看不到明细的问题 */
.stage-materials {
  margin-top: 12px;
}
.stage-sub-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 6px;
}
.stage-sub-head strong {
  font-size: 14px;
}
.stage-sub-head small {
  color: var(--mes-text-muted);
  font-size: 13px;
}
.mat-name {
  margin-right: 6px;
}
.material-table :deep(.el-table__cell) {
  padding: 4px 0;
}
.material-table :deep(.el-table__header .el-table__cell) {
  padding: 5px 0;
}

.stage-children {
  display: grid;
  gap: 5px;
  margin-top: 10px;
}
.stage-child {
  width: 100%;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 7px;
  border-left: 2px solid var(--stage-color);
  border-radius: 3px;
  background: #fafcfe;
  font-size: 13px;
}
.child-name {
  font-weight: 600;
}
.child-status {
  color: var(--stage-color);
  font-size: 12px;
}
.child-qty {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}
.child-time {
  color: var(--mes-text-muted);
  font-size: 12px;
}
.stage-blockers {
  margin-top: 8px;
}
.stage-blockers p {
  font-size: 13px;
  line-height: 1.5;
}
.stage-docs {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}
.stage-notes {
  margin-top: 8px;
  padding-left: 14px;
  color: var(--mes-text-muted);
  font-size: 12px;
  line-height: 1.6;
  list-style: disc;
}

.doc-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 1px 6px;
  color: #1f6feb;
  border: 1px solid #d6e4f7;
  border-radius: 3px;
  background: #f5f9ff;
  font-size: 12px;
  cursor: pointer;
}
.doc-chip:hover {
  background: #eaf3ff;
}
.doc-chip:disabled {
  color: var(--mes-text-muted);
  border-color: #e8edf4;
  background: #f7f9fc;
  cursor: default;
}

.chart-grid {
  display: grid;
  gap: 10px;
  padding: 12px;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 430px), 1fr));
}
.chart-card {
  padding: 9px 10px 4px;
  border: 1px solid #eef2f7;
  border-radius: 5px;
  background: #fff;
}
.chart-card h4 {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 4px;
  color: var(--mes-text-secondary);
  font-size: 13px;
  font-weight: 600;
}
.chart-hint {
  padding: 0 14px 12px;
  color: var(--mes-text-muted);
  font-size: 13px;
}

.detail-tabs {
  padding: 4px 12px 12px;
}
.timeline-list {
  padding: 6px 2px 0;
}
.timeline-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.quality-section {
  padding: 12px 14px;
}
.quality-list {
  margin-top: 8px;
  padding-left: 15px;
  color: var(--mes-text-secondary);
  font-size: 13px;
  line-height: 1.7;
  list-style: disc;
}
.aging-box {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px dashed #d6e4f7;
  border-radius: 4px;
  background: #f7fbff;
}
.aging-box strong {
  font-size: 13px;
}
.aging-box ul {
  margin-top: 4px;
  padding-left: 15px;
  color: var(--mes-text-secondary);
  font-size: 13px;
  line-height: 1.6;
  list-style: disc;
}
.quality-foot {
  margin-top: 9px;
  color: var(--mes-text-muted);
  font-size: 12px;
}
.progress-panel :deep(.el-table) {
  font-size: 13px;
  --el-table-header-bg-color: #f5f8fc;
}
.progress-panel :deep(.el-table .cell) {
  line-height: 1.65;
}
.progress-panel :deep(.el-table__cell) {
  padding: 8px 0;
}
.progress-panel :deep(.el-tabs__item) {
  font-size: 14px;
  height: 46px;
}
.progress-panel :deep(.el-button--small),
.progress-panel :deep(.el-tag--small) {
  font-size: 12px;
}
.child-open {
  color: #1f6feb;
  margin-left: 8px;
  white-space: nowrap;
}
.stage-child:hover {
  background: #eef5ff;
}
.stage-child:focus-visible,
.stage-tab:focus-visible,
.doc-chip:focus-visible {
  outline: 2px solid #1f6feb;
  outline-offset: 2px;
}
@media (max-width: 720px) {
  .progress-panel {
    padding: 12px;
    gap: 12px;
  }
  .summary-title {
    flex-wrap: wrap;
  }
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .stage-pane {
    padding: 12px;
  }
  .stage-pane-qty {
    flex-wrap: wrap;
  }
  .stage-child {
    flex-wrap: wrap;
  }
  .summary-sub {
    gap: 6px 12px;
  }
}
</style>
