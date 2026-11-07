<script setup>
import { computed, onMounted, ref } from 'vue';
import { getResourcePage } from '@/api/resource';
import AppIcon from '@/components/common/AppIcon.vue';

// 仅展示实际接口记录数；改善耗时、错误率等指标等待现场采样。
const loading = ref(false);
const statistics = ref({});
const errors = ref({});
const definitions = [
  {
    key: 'requirements',
    label: '工单用料需求',
    unit: '条',
    hint: '当前有效需求记录',
    icon: 'clipboard',
    url: '/api/mfg/work-order-material',
  },
  {
    key: 'outbound',
    label: '出库单据',
    unit: '张',
    hint: '当前有效出库单，含不同状态',
    icon: 'package',
    url: '/api/wms/outbound-order',
  },
  {
    key: 'inspection',
    label: '检验单',
    unit: '张',
    hint: '当前有效检验单，含不同状态',
    icon: 'shield-check',
    url: '/api/qms/inspection-order',
  },
  {
    key: 'usage',
    label: '物料使用记录',
    unit: '条',
    hint: '当前有效用料追溯记录',
    icon: 'scan',
    url: '/api/trace/material-usage',
  },
];
// 指标卡提示：读取失败优先，其次读取中，最后回落到定义里的固定说明。
function overviewHint(item) {
  if (errors.value[item.key]) {
    return '读取失败，请刷新重试';
  }
  if (loading.value) {
    return '读取中…';
  }
  return item.hint;
}

const overview = computed(() =>
  definitions.map((item) => ({
    ...item,
    value: statistics.value[item.key] ?? '—',
    hint: overviewHint(item),
  })),
);
const hasReadError = computed(() => Object.keys(errors.value).length > 0);
const latencies = ref({});

async function loadOverview() {
  loading.value = true;
  try {
    const started = definitions.map(() => performance.now());
    const results = await Promise.allSettled(
      definitions.map((item) => getResourcePage(item.url, { current: 1, size: 1 }, { suppressErrorMessage: true })),
    );
    const next = {};
    const failures = {};
    const costs = {};
    results.forEach((result, index) => {
      const { key } = definitions[index];
      const total = result.status === 'fulfilled' ? Number(result.value?.data?.total) : NaN;
      if (Number.isFinite(total) && total >= 0) {
        next[key] = total;
        // 耗时只对真正返回数据的请求计时，失败请求的等待时间不能当成系统响应速度。
        costs[key] = Math.round(performance.now() - started[index]);
      } else {
        failures[key] = true;
      }
    });
    statistics.value = next;
    latencies.value = costs;
    errors.value = failures;
  } finally {
    loading.value = false;
  }
}

// 拿不到数据就显示占位符，不编一个看起来合理的数字。
function record(key) {
  const value = statistics.value[key];
  return Number.isFinite(value) ? value : null;
}

function latency(key) {
  const value = latencies.value[key];
  return Number.isFinite(value) ? value : null;
}

onMounted(loadOverview);

// 流程对比用于说明业务设计。指标里的数字全部来自当前库的实时记录数与本次请求实测耗时，
// 没读到的显示占位符，不用历史快照凑数。
// 三个对比分组各自独立成函数，避免一个 computed 里堆十几层对象字面量。
function pickingGroup() {
  return {
    key: 'picking',
    index: '01',
    icon: 'clipboard',
    title: '领料发料：从手抄清单到一键生成',
    pain: '多层 BOM 需要分别核对整机和组件的直接用料，人工抄写数量、批次和库位容易出现漏项或重复领料。',
    before: {
      tag: '人工 + 纸质领料单',
      steps: ['打印工单 BOM', '逐行手算需求量', '找仓库问批次和库位', '手写领料单并签字', '仓库再录一遍台账'],
      points: [
        'BOM 层级靠人脑展开，组件工单与整灯工单容易重复领料',
        '同一批数据要抄两遍：领料单一遍，仓库台账一遍',
        '需求量算错只能在发料现场发现，工单已经开工',
      ],
    },
    after: {
      tag: '系统自动生成',
      steps: ['工单下达', '系统按 BOM 展开用料需求', '一键生成领料出库单', '仓库确认出库', '库存与流水同时落账'],
      points: [
        '父子工单分别计算本级 BOM 需求，避免把组件内部原料重复计入整机领料',
        '出库单、出库明细、库存余额、库存流水在同一事务内写齐',
        '领料转线边是成对流水：原仓减 q、线边加 q，数量对不上就拒绝提交',
      ],
    },
    metrics: [
      {
        label: '一次领料的单据数',
        before: '按 BOM 行数人工开单',
        after: record('outbound') === null ? '当前读取失败' : `当前有效出库单 ${record('outbound')} 张`,
        unit: '',
      },
      {
        label: '领料单生成耗时',
        before: '人工手写，随 BOM 行数线性增加',
        after: latency('outbound') === null ? '当前读取失败' : `出库单查询实测 ${latency('outbound')} 毫秒`,
        unit: '',
      },
      { label: '重复录入次数', before: '领料单、仓库台账各录一遍', after: '一次提交，单据与流水同时落账', unit: '次' },
      { label: '数量错误发现时机', before: '发料现场', after: '提交前校验拦截', unit: '' },
    ],
  };
}

function inspectionGroup() {
  return {
    key: 'inspection',
    index: '02',
    icon: 'shield-check',
    title: '质量检验：从纸质记录到方案驱动',
    pain: '检验项目、抽样方式和判定标准散落在不同 Excel 里，同一种灯不同批次可能按不同标准判，追溯时查不到当时依据。',
    before: {
      tag: 'Excel + 纸质检验记录',
      steps: ['翻找对应 Excel 标准', '手写检验记录表', '人工判定合格与否', '扫描归档', '不合格再单独走流程'],
      points: [
        '检验标准和产品没有绑定关系，换批次要人工确认用哪一份',
        '检验结果只有纸质记录，做统计要重新录一遍',
        '不合格品处置要另开一张单，和检验单对不上号',
      ],
    },
    after: {
      tag: '检验方案 + 单据联动',
      steps: [
        '检验方案绑定产品与工序',
        '生成检验单并带出检验项目',
        '录入结果自动判定',
        '不合格一键发起处置',
        '结果回写批次与工单',
      ],
      points: [
        '检验方案按产品与工序配置，项目、抽样和标准以实际发布版本为准',
        '按来料、过程、终检和出货类型管理检验，具体记录与批次、工单关联',
        '终检不合格可一键生成不合格品处置单，处置数量、原因、审批链路完整留痕',
      ],
    },
    metrics: [
      {
        label: '检验单数量',
        before: '纸质记录，无法统计',
        after: record('inspection') === null ? '当前读取失败' : `当前有效检验单 ${record('inspection')} 张`,
        unit: '',
      },
      {
        label: '检验单查询耗时',
        before: '翻纸质记录本',
        after: latency('inspection') === null ? '当前读取失败' : `检验单查询实测 ${latency('inspection')} 毫秒`,
        unit: '',
      },
      { label: '结果录入次数', before: '纸质与统计分别填写', after: '电子记录集中维护', unit: '次' },
      { label: '检验单与批次关联', before: '无系统关联', after: '单据直接绑定', unit: '' },
    ],
  };
}

function traceGroup() {
  return {
    key: 'trace',
    index: '03',
    icon: 'scan',
    title: '批次追溯：从翻记录本到一键全链路',
    pain: '客户反馈某台灯异常，要回答「这批用了哪些物料、哪张工单、谁做的检验」，需要逐份核对仓库台账和检验记录，追溯成本较高。',
    before: {
      tag: '人工翻台账',
      steps: ['找到成品批次号', '翻仓库出入库台账', '逐个物料查来料批次', '找检验记录本核对', '手工整理追溯报告'],
      points: [
        '物料使用记录靠仓库事后补登，漏登就无法追溯',
        '工单、批次、检验三类记录分散，只能靠单号人工串联',
        '追溯结论没有系统留痕，事后无法复核当时的判断依据',
      ],
    },
    after: {
      tag: '条码 + 自动留痕',
      steps: ['扫成品条码或输入批次号', '系统展开物料使用记录', '关联工单与报工', '带出全部检验单', '生成全链路视图'],
      points: [
        '以实际业务生成的物料使用记录为准，保留来源工单、报工和物料批次',
        '产品批次直接关联工单、报工、物料批次和检验单，一次查询走完全链',
        '反向追溯支持按物料批次反查受影响成品，召回范围可界定',
      ],
    },
    metrics: [
      {
        label: '一次追溯耗时',
        before: '逐份核对台账与检验记录',
        after: latency('usage') === null ? '当前读取失败' : `用料记录查询实测 ${latency('usage')} 毫秒`,
        unit: '',
      },
      {
        label: '可追溯用料记录',
        before: '靠人工串联，未成记录',
        after: record('usage') === null ? '当前读取失败' : `当前有效用料记录 ${record('usage')} 条`,
        unit: '',
      },
      { label: '可追溯字段', before: '单号 + 数量', after: '批次 / 工单 / 报工 / 检验', unit: '类' },
    ],
  };
}

const groups = computed(() => [pickingGroup(), inspectionGroup(), traceGroup()]);

// 底部汇总表：把三组的关键变化压成一张可截图的表。
const summaryRows = computed(() =>
  groups.value.map((group) => ({
    key: group.key,
    title: group.title.split('：')[0],
    before: group.before.tag,
    after: group.after.tag,
    gain: group.metrics
      .slice(0, 2)
      .map((metric) => `${metric.label} ${metric.before} → ${metric.after}`)
      .join('；'),
  })),
);
</script>

<template>
  <div class="po-page">
    <header class="po-head">
      <div class="po-head-main">
        <span class="po-head-icon"><AppIcon name="route" :size="21" /></span>
        <div>
          <h1>流程优化对比</h1>
        </div>
      </div>
      <el-tag type="info" effect="plain" size="large">改善基线</el-tag>
      <el-button :loading="loading" @click="loadOverview">刷新记录数</el-button>
    </header>

    <el-alert
      v-if="hasReadError"
      title="部分记录数读取失败，请确认权限或刷新重试；未读到的数据不会计为零。"
      type="warning"
      :closable="false"
      show-icon
    />

    <!-- 总览来自当前有效记录的分页总数，不将记录数等同于生产完成量。 -->
    <section class="po-overview">
      <article v-for="item in overview" :key="item.key" class="po-stat">
        <span class="po-stat-icon"><AppIcon :name="item.icon" :size="16" /></span>
        <div class="po-stat-body">
          <span class="po-stat-label">{{ item.label }}</span>
          <p class="po-stat-value">
            {{ item.value }}<em>{{ item.unit }}</em>
          </p>
          <span class="po-stat-hint">{{ item.hint }}</span>
        </div>
      </article>
    </section>

    <!-- 三组对比：左右卡片 + 中间箭头 + 下方收益指标条。 -->
    <section v-for="group in groups" :key="group.key" class="po-group">
      <header class="po-group-head">
        <span class="po-index">{{ group.index }}</span>
        <div class="po-group-title">
          <h2>
            <AppIcon :name="group.icon" :size="17" />
            <span>{{ group.title }}</span>
          </h2>
          <p class="po-pain"><strong>业务痛点</strong>{{ group.pain }}</p>
        </div>
      </header>

      <div class="po-compare">
        <article class="po-card is-before">
          <header class="po-card-head">
            <span class="po-card-tag">优化前</span>
            <span class="po-card-mode">{{ group.before.tag }}</span>
          </header>
          <ol class="po-flow">
            <li v-for="(step, index) in group.before.steps" :key="step">
              <span class="po-step-no">{{ index + 1 }}</span>
              <span class="po-step-text">{{ step }}</span>
            </li>
          </ol>
          <ul class="po-points">
            <li v-for="point in group.before.points" :key="point">{{ point }}</li>
          </ul>
        </article>

        <div class="po-arrow">
          <span class="po-arrow-line"></span>
          <span class="po-arrow-badge"><AppIcon name="arrow" :size="18" /></span>
          <span class="po-arrow-text">改造后</span>
          <span class="po-arrow-line"></span>
        </div>

        <article class="po-card is-after">
          <header class="po-card-head">
            <span class="po-card-tag">优化后</span>
            <span class="po-card-mode">{{ group.after.tag }}</span>
          </header>
          <ol class="po-flow">
            <li v-for="(step, index) in group.after.steps" :key="step">
              <span class="po-step-no">{{ index + 1 }}</span>
              <span class="po-step-text">{{ step }}</span>
            </li>
          </ol>
          <ul class="po-points">
            <li v-for="point in group.after.points" :key="point">{{ point }}</li>
          </ul>
        </article>
      </div>

      <div class="po-metrics">
        <article v-for="metric in group.metrics" :key="metric.label" class="po-metric">
          <span class="po-metric-label">{{ metric.label }}</span>
          <div class="po-metric-values">
            <span class="po-metric-before">{{ metric.before }}</span>
            <AppIcon name="arrow" :size="13" class="po-metric-arrow" />
            <strong class="po-metric-after">{{ metric.after }}</strong>
          </div>
        </article>
      </div>
    </section>

    <section class="po-summary">
      <header class="po-summary-head">
        <h2>改造前后速览</h2>
      </header>
      <el-table :data="summaryRows" stripe border style="width: 100%">
        <el-table-column prop="title" label="业务环节" width="150" />
        <el-table-column prop="before" label="优化前做法" min-width="200" />
        <el-table-column prop="after" label="优化后做法" min-width="200" />
        <el-table-column prop="gain" label="关键变化" min-width="240" />
      </el-table>
    </section>

  </div>
</template>

<style scoped>
/* 页面沿用系统基础令牌：1px 描边、8px 圆角、单一主色，不使用渐变和发光。 */
.po-page {
  display: flex;
  max-width: 1440px;
  flex-direction: column;
  gap: 16px;
  margin: 0 auto;
  color: var(--mes-text, #17243a);
  font-size: 13px;
}

.po-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.po-head-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.po-head-icon {
  display: inline-flex;
  width: 42px;
  height: 42px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary, var(--mes-primary));
  border: 1px solid var(--mes-primary-border, var(--mes-primary-border));
  border-radius: 8px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.po-head h1 {
  margin: 0;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: 0;
}

.po-head p {
  margin: 4px 0 0;
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 12px;
}

/* ---------- 总览指标 ---------- */
.po-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.po-stat {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 14px 16px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

.po-stat-icon {
  display: inline-flex;
  width: 30px;
  height: 30px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary, var(--mes-primary));
  border-radius: 6px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.po-stat-body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.po-stat-label {
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
}

.po-stat-value {
  margin: 0;
  color: var(--mes-text, var(--mes-text));
  font-size: 24px;
  font-weight: 600;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

.po-stat-value em {
  margin-left: 3px;
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 12px;
  font-style: normal;
  font-weight: 400;
}

.po-stat-hint {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
}

/* ---------- 分组 ---------- */
.po-group {
  padding: 16px 18px 18px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

.po-group-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--mes-border-light, #eef1f5);
}

.po-index {
  flex: none;
  color: var(--mes-primary, var(--mes-primary));
  font-size: 22px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  opacity: 0.35;
}

.po-group-title {
  min-width: 0;
  flex: 1;
}

.po-group-title h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
}

.po-group-title h2 :deep(svg) {
  color: var(--mes-primary, var(--mes-primary));
}

.po-pain {
  margin: 6px 0 0;
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
  line-height: 1.65;
}

.po-pain strong {
  margin-right: 7px;
  padding: 1px 6px;
  color: var(--mes-warning, #d98a00);
  font-size: 11px;
  font-weight: 500;
  border: 1px solid var(--mes-warning, #d98a00);
  border-radius: 3px;
  background: var(--mes-warning-soft, #fff5e0);
}

/* ---------- 左右对比 ---------- */
.po-compare {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 78px minmax(0, 1fr);
  align-items: stretch;
  gap: 0;
  margin-top: 14px;
}

.po-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

/* 优化前用中性灰，优化后用主色描边，一眼能看出改造方向。 */
.po-card.is-before {
  background: var(--mes-surface-muted, #f7f8fa);
}

.po-card.is-after {
  border-color: var(--mes-primary-border, var(--mes-primary-border));
  box-shadow: inset 0 0 0 1px rgb(51 112 255 / 6%);
}

.po-card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding-bottom: 10px;
  border-bottom: 1px dashed var(--mes-border, var(--mes-border));
}

.po-card-tag {
  padding: 2px 9px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
  font-weight: 600;
  border-radius: 3px;
  background: var(--mes-info-soft, #eef2f7);
}

.po-card.is-after .po-card-tag {
  color: #fff;
  background: var(--mes-primary, var(--mes-primary));
}

.po-card-mode {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11.5px;
}

/* 流程示意：左侧序号 + 竖线连接，纯 CSS，不依赖图片。 */
.po-flow {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 0;
  padding: 0;
  list-style: none;
}

.po-flow li {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 5px 0;
}

.po-flow li:not(:last-child)::before {
  position: absolute;
  top: 24px;
  bottom: -3px;
  left: 9px;
  width: 1px;
  background: var(--mes-border, var(--mes-border));
  content: '';
}

.po-card.is-after .po-flow li:not(:last-child)::before {
  background: var(--mes-primary-border, var(--mes-primary-border));
}

.po-step-no {
  z-index: 1;
  display: inline-flex;
  width: 19px;
  height: 19px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-text-secondary, #646a73);
  font-size: 10.5px;
  font-weight: 600;
  border: 1px solid var(--mes-border-strong, #cbd6e2);
  border-radius: 50%;
  background: var(--mes-surface, #fff);
  font-variant-numeric: tabular-nums;
}

.po-card.is-after .po-step-no {
  color: var(--mes-primary, var(--mes-primary));
  border-color: var(--mes-primary-border, var(--mes-primary-border));
  background: var(--mes-primary-soft, #eaf2ff);
}

.po-step-text {
  color: var(--mes-text, var(--mes-text));
  font-size: 12.5px;
  line-height: 19px;
}

.po-card.is-before .po-step-text {
  color: var(--mes-text-secondary, #646a73);
}

.po-points {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 10px 0 0;
  border-top: 1px dashed var(--mes-border, var(--mes-border));
  list-style: none;
}

.po-points li {
  position: relative;
  padding-left: 13px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 11.5px;
  line-height: 1.7;
}

.po-points li::before {
  position: absolute;
  top: 8px;
  left: 2px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--mes-border-strong, #cbd6e2);
  content: '';
}

.po-card.is-after .po-points li::before {
  background: var(--mes-primary, var(--mes-primary));
}

/* 中间箭头列 */
.po-arrow {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 8px;
}

.po-arrow-line {
  width: 1px;
  flex: 1;
  min-height: 18px;
  background: var(--mes-border, var(--mes-border));
}

.po-arrow-badge {
  display: inline-flex;
  width: 34px;
  height: 34px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary, var(--mes-primary));
  border: 1px solid var(--mes-primary-border, var(--mes-primary-border));
  border-radius: 50%;
  background: var(--mes-primary-soft, #eaf2ff);
}

.po-arrow-text {
  flex: none;
  color: var(--mes-primary, var(--mes-primary));
  font-size: 10.5px;
  white-space: nowrap;
}

/* ---------- 收益指标条 ---------- */
.po-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  margin-top: 14px;
  overflow: hidden;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-border, var(--mes-border));
}

.po-metric {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
  padding: 11px 14px;
  background: var(--mes-surface, #fff);
}

.po-metric-label {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11.5px;
}

.po-metric-values {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.po-metric-before {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 12px;
  text-decoration: line-through;
  text-decoration-color: var(--mes-border-strong, #cbd6e2);
}

.po-metric-arrow {
  color: var(--mes-border-strong, #cbd6e2);
}

.po-metric-after {
  color: var(--mes-primary, var(--mes-primary));
  font-size: 12.5px;
  font-weight: 600;
}

/* ---------- 汇总表 ---------- */
.po-summary {
  padding: 16px 18px 18px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

.po-summary-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 12px;
}

.po-summary-head h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

/* 窄屏下把左右对比折成上下，箭头转成竖向提示。 */
@media (max-width: 1100px) {
  .po-overview,
  .po-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .po-compare {
    grid-template-columns: minmax(0, 1fr);
  }

  .po-arrow {
    flex-direction: row;
    padding: 8px 0;
  }

  .po-arrow-line {
    width: auto;
    height: 1px;
    flex: 1;
    min-height: 0;
  }
}

@media (max-width: 720px) {
  .po-overview,
  .po-metrics {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
