<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { executeAgentCommand, getAgentCommandDetail } from '@/api/ai/agent';
import { getResourceDetail } from '@/api/resource';

// 命令预览卡片：把「AI 想做的写操作」摊开给用户看，前置条件、影响范围和有效期都可见，
// 用户点确认后才会真正调后端执行，避免自然语言直接改动业务单据。
const props = defineProps({
  command: { type: Object, required: true },
  inspectionCompleted: { type: Boolean, default: false },
});
const emit = defineEmits(['resolved', 'restored', 'piece-inspection', 'recheck', 'recover']);
const completedInspectionFromServer = ref(false);
const inspectionDone = computed(() => props.inspectionCompleted || completedInspectionFromServer.value);
const needsPieceInspection = computed(() => !inspectionDone.value
  && props.command.action === 'SUBMIT_INSPECTION' && Boolean(props.command.targetId)
  && props.command.preconditions?.some((item) => item.code === 'INSPECTION_PIECE_RESULTS' && !item.passed));
// 只重新核查无数量/结论输入的动作；报工和检验保留原有输入流程。
const canRecheck = computed(() => !executed.value && !cancelled.value && !executing.value
  && (expired.value || props.command.canExecute === false)
  && Boolean(props.command.targetNo)
  && !['SUBMIT_WORK_REPORT', 'SUBMIT_INSPECTION', 'AUDIT_WORK_REPORT', 'CREATE_PRODUCTION_ORDER'].includes(props.command.action));

const executing = ref(false);
const executed = ref(false);
const result = ref(null);
const resultDetails = computed(() => result.value?.result || result.value || {});
const errorMessage = ref('');
const cancelled = ref(false);
const now = ref(Date.now());
// 卡片状态以后台记录为准：重新进入页面时按 commandId 回读，避免「已执行」又变回可点击。
const restoredStatus = ref('');
let timer;
// 相同预览卡片的网络重试使用同一请求键，避免超时后再次点击被当成新操作。
const executionKey = `ai-${props.command.confirmationId || newIdempotencyKey()}`;

// 后端可能给 ISO 字符串或时间戳，统一转成毫秒；无效时返回 0 表示不显示倒计时。
const expiresAt = computed(() => {
  const raw = props.command?.expiresAt;
  if (!raw) {
    return 0;
  }
  const parsed = typeof raw === 'number' ? raw : new Date(raw).getTime();
  return Number.isFinite(parsed) ? parsed : 0;
});

const remainingSeconds = computed(() =>
  expiresAt.value ? Math.max(0, Math.ceil((expiresAt.value - now.value) / 1000)) : -1,
);
const expired = computed(() => remainingSeconds.value === 0);
const canExecute = computed(
  () => Boolean(props.command?.canExecute) && !expired.value && !executed.value && !cancelled.value,
);
const passedCount = computed(() => (props.command?.preconditions || []).filter((item) => item?.passed).length);
const totalChecks = computed(() => (props.command?.preconditions || []).length);

// 后台已记录的状态文案：只有执行成功 / 已取消 / 已过期三种需要在卡片上回落显示。
const restoredText = computed(() => {
  const map = {
    SUCCEEDED: '该命令已执行成功',
    PARTIAL_FAILED: '该命令部分执行失败',
    FAILED: '该命令执行失败',
    REJECTED: '该命令已取消，未执行任何业务操作',
    EXPIRED: '该命令预览已过期',
  };
  return map[String(restoredStatus.value || '').toUpperCase()] || '';
});

// 参数表只展示有值的字段，并把常见编码翻成中文，避免卡片上出现一堆英文键。
const PARAM_LABELS = {
  workOrderId: '工单ID',
  workOrderNo: '工单号',
  taskId: '工序任务ID',
  taskNo: '工序任务号',
  outboundId: '出库单ID',
  inboundId: '入库单ID',
  inspectionId: '检验单ID',
  reportId: '报工ID',
  dispatchNo: '派工单号',
  reportNo: '报工单号',
  inspectionNo: '检验单号',
  warehouseId: '仓库ID',
  warehouseName: '领料仓库',
  warehouseType: '仓库类型',
  locationId: '库位ID',
  targetLocationId: '目标库位',
  quantity: '数量',
  orderQuantity: '订单数量',
  deliveryDate: '交付日期',
  lineCode: '产线',
  lineName: '产线名称',
  plannedStartTime: '计划开工',
  plannedEndTime: '计划完工',
  planStartTime: '计划开工',
  planEndTime: '计划完工',
  generationPlanNo: '生产计划',
  dispatchTeamName: '派工班组',
  dispatchStart: '预计开始',
  dispatchDue: '预计完成',
  materialCode: '物料编码',
  customerCode: '客户编码',
  supplierCode: '供应商编码',
  sampleQuantity: '抽样数',
  qualifiedQuantity: '合格数',
  defectQuantity: '不良数',
  unqualifiedQuantity: '不合格数',
  result: '判定结果',
  auditResult: '审核结果',
  remark: '备注',
};

// 这些键只是后端把原始意图回传了一遍，标题行已经展示，列在参数表里只会造成重复。
const META_KEYS = new Set(['action', 'text', 'targetNo', 'targetId', 'customerId', 'lineId', 'generationPlanId', 'dispatchTeamId', 'dispatchProblem', 'teamName', 'scheduleProblem', 'conversationId', 'source', 'reportMaterialSnapshot']);
const INTERNAL_DOCUMENT_IDS = new Set([
  'workOrderId', 'taskId', 'inspectionId', 'reportId', 'dispatchId', 'outboundId', 'inboundId',
]);
const targetTypeLabel = computed(() => ({
  PRODUCT: '产品', WORK_ORDER: '工单', OUTBOUND_ORDER: '出库单', INBOUND_ORDER: '入库单',
  PRODUCTION_ORDER: '生产订单',
  INSPECTION_ORDER: '检验单', WORK_REPORT: '报工单',
  TASK: '工序任务', DISPATCH_ORDER: '派工单', PRODUCT_LOT: '产品批次',
})[props.command?.targetType] || '目标');

const paramRows = computed(() => {
  const params = props.command?.params;
  if (!params || typeof params !== 'object') {
    return [];
  }
  return Object.entries(params)
    .filter(([key, value]) => value !== null && value !== undefined && value !== '' && !META_KEYS.has(key))
    .filter(([key, value]) => !INTERNAL_DOCUMENT_IDS.has(key)
      && !(key.endsWith('No') && String(value) === props.command.targetNo))
    .map(([key, value]) => ({ key, label: PARAM_LABELS[key] || key, value: String(value) }));
});

// 影响等级用语义色标签：HIGH 红、MEDIUM 橙、LOW 灰，和系统其他风险标记保持一致。
const IMPACT_META = {
  HIGH: { label: '高影响', className: 'is-high' },
  MEDIUM: { label: '中影响', className: 'is-medium' },
  LOW: { label: '低影响', className: 'is-low' },
};

function impactMeta(level) {
  return IMPACT_META[String(level || '').toUpperCase()] || IMPACT_META.LOW;
}

function formatCountdown(seconds) {
  if (seconds < 0) {
    return '';
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}:${String(rest).padStart(2, '0')}`;
}

// 生成幂等键：同一张卡片重复点击只会产生一次业务动作，后端按 key 重放既有结果。
function newIdempotencyKey() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch {
    // 非安全上下文没有 randomUUID，退回时间戳 + 随机数。
  }
  return `ai-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

async function confirmExecute() {
  if (!canExecute.value || executing.value) {
    return;
  }
  executing.value = true;
  errorMessage.value = '';
  try {
    const response = await executeAgentCommand({
      confirmationId: props.command.confirmationId,
      idempotencyKey: executionKey,
      action: props.command.action,
      remark: 'AI 工作台人工确认执行',
    });
    const payload = response?.data || {};
    const failed = ['FAILED', 'PARTIAL_FAILED', 'REJECTED'].includes(String(payload.status || '').toUpperCase());
    if (failed || payload.errorMessage) {
      errorMessage.value = payload.errorMessage || '执行未成功，请查看执行监控。';
    } else {
      result.value = payload;
      executed.value = true;
    }
    emit('resolved', payload);
  } catch (error) {
    // 接口未就绪（404/500）时也只在这张卡片里提示，不影响对话其他内容。
    errorMessage.value = error?.displayMessage || error?.data?.message || '执行接口暂不可用，请稍后重试。';
  } finally {
    executing.value = false;
  }
}

function cancelCommand() {
  cancelled.value = true;
  emit('resolved', { status: 'CANCELLED' });
}

// 重进页面时按 commandId 回读后台记录：执行过就显示结果，取消/过期就锁按钮，
// 卡片组件自己的内存状态只是当下的交互反馈，不能当作事实来源。
async function restoreFromBackend() {
  const commandId = props.command?.commandId;
  if (!commandId) {
    return;
  }
  try {
    const response = await getAgentCommandDetail(commandId);
    const data = response?.data || {};
    const status = String(data.command?.status || '').toUpperCase();
    restoredStatus.value = status;
    if (status === 'SUCCEEDED' || status === 'PARTIAL_FAILED') {
      executed.value = true;
      const parsed = parseResultJson(data.command?.resultJson);
      result.value = {
        commandId,
        status,
        result: parsed,
        elapsedMs: data.command?.elapsedMs,
      };
      emit('restored', result.value);
    } else if (status === 'REJECTED' || status === 'FAILED' || status === 'EXPIRED') {
      cancelled.value = status === 'REJECTED';
      errorMessage.value = status === 'FAILED' ? data.command?.errorMessage || '该命令执行失败。' : '';
    }
  } catch {
    // 接口不可用时保持卡片的预览态，用户仍可点确认执行。
  }
}

function parseResultJson(raw) {
  if (!raw) {
    return null;
  }
  if (typeof raw !== 'string') {
    return raw;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

onMounted(() => {
  restoreFromBackend();
  if (props.command.action === 'SUBMIT_INSPECTION' && props.command.targetId && !props.inspectionCompleted) {
    getResourceDetail('/api/qms/inspection-order', props.command.targetId).then((response) => {
      completedInspectionFromServer.value = response?.data?.status === 'FINISHED';
    }).catch(() => {
      // 读取失败保留原状态，不把失败当成已完成。
    });
  }
  // 每秒刷新一次剩余时间，倒计时归零后按钮自动禁用。
  if (!expiresAt.value) {
    return;
  }
  timer = setInterval(() => {
    now.value = Date.now();
  }, 1000);
});

onBeforeUnmount(() => {
  clearInterval(timer);
});
</script>

<template>
  <section class="ai-command-card" :class="{ 'is-executed': executed, 'is-expired': expired }">
    <header class="cmd-head">
      <span class="cmd-mark"><AppIcon name="play" :size="13" /></span>
      <div class="cmd-title">
        <strong>{{ command.actionLabel || command.action || '业务命令' }}</strong>
        <span class="cmd-target">
          <template v-if="command.targetNo">{{ targetTypeLabel }} · {{ command.targetNo }}</template>
          <template v-else>目标单据待确认</template>
        </span>
      </div>
      <span v-if="inspectionDone" class="cmd-check is-pass">已提交</span>
      <span v-else-if="totalChecks" class="cmd-check" :class="{ 'is-pass': passedCount === totalChecks }">
        前置检查 {{ passedCount }}/{{ totalChecks }}
      </span>
    </header>

    <!-- 影响范围：让用户在执行前看到这次操作会改动什么。 -->
    <ul v-if="command.impacts?.length" class="cmd-impacts">
      <li v-for="(impact, index) in command.impacts" :key="index">
        <span class="impact-tag" :class="impactMeta(impact.level).className">{{ impactMeta(impact.level).label }}</span>
        <span>{{ impact.text }}</span>
      </li>
    </ul>

    <dl v-if="paramRows.length" class="cmd-params">
      <div v-for="row in paramRows" :key="row.key">
        <dt>{{ row.label }}</dt>
        <dd>{{ row.value }}</dd>
      </div>
    </dl>

    <!-- 前置条件：真实校验结果，未通过的显示后端给出的具体原因。 -->
    <ul v-if="!inspectionDone && command.preconditions?.length" class="cmd-checks">
      <li v-for="item in command.preconditions" :key="item.code" :class="{ 'is-failed': !item.passed }">
        <span class="check-icon"><AppIcon :name="item.passed ? 'check' : 'close'" :size="12" /></span>
        <span class="check-text">
          <span>{{ item.label || item.code }}</span>
          <small v-if="!item.passed && item.detail">{{ item.detail }}</small>
        </span>
      </li>
    </ul>

    <p v-if="!inspectionDone && command.warnings?.length" class="cmd-warn">
      <AppIcon name="alert" :size="13" />
      <span>{{ command.warnings.join('；') }}</span>
    </p>

    <div
      v-if="!executed && !cancelled && !inspectionDone && command.canExecute === false && command.recoverySteps?.length"
      class="cmd-recovery"
    >
      <strong>下一步</strong>
      <p v-for="step in command.recoverySteps" :key="step.label">
        <button class="cmd-button" type="button" @click="emit('recover', step)">{{ step.label }}</button>
        <small>{{ step.detail }}</small>
      </p>
    </div>
    <button
      v-if="needsPieceInspection"
      class="cmd-button is-primary"
      type="button"
      @click="emit('piece-inspection', command)"
    >
      填写逐件检验结果
    </button>
    <p v-if="inspectionDone" class="cmd-warn">逐件检验已提交，请重新查询检验单查看当前状态。</p>

    <!-- 执行结果：成功后给出业务单号和摘要，失败保留可读原因。 -->
    <div v-if="result" class="cmd-result">
      <AppIcon name="check" :size="14" />
      <div>
        <strong>执行成功{{ resultDetails.bizNo ? ` · ${resultDetails.bizNo}` : '' }}</strong>
        <span>{{ resultDetails.summary || '业务单据已按预览内容更新。' }}</span>
        <small v-if="result.commandId"
          >命令ID {{ result.commandId
          }}<template v-if="result.elapsedMs"> · 耗时 {{ result.elapsedMs }}ms</template></small
        >
      </div>
    </div>

    <p v-if="errorMessage" class="cmd-error">
      <AppIcon name="alert" :size="13" />
      <span>{{ errorMessage }}</span>
    </p>

    <footer v-if="!inspectionDone" class="cmd-foot">
      <span class="cmd-expire">
        <template v-if="cancelled">已取消，未执行任何业务操作</template>
        <template v-else-if="executed">{{ restoredText || '命令已执行' }}</template>
        <template v-else-if="expired">预览已过期，请重新检查</template>
        <template v-else-if="restoredText">{{ restoredText }}</template>
        <template v-else-if="remainingSeconds >= 0">预览有效期 {{ formatCountdown(remainingSeconds) }}</template>
        <template v-else>确认后才会执行</template>
      </span>
      <div class="cmd-actions">
        <button v-if="canRecheck" class="cmd-button" type="button" @click="emit('recheck', command)">
          重新检查
        </button>
        <button
          v-if="!executed && !cancelled"
          class="cmd-button"
          type="button"
          :disabled="executing"
          @click="cancelCommand"
        >
          取消
        </button>
        <button
          v-if="!executed && !cancelled"
          class="cmd-button is-primary"
          type="button"
          :disabled="!canExecute || executing"
          :title="canExecute ? '确认后立即执行该业务动作' : '前置条件未通过，无法执行'"
          @click="confirmExecute"
        >
          {{ executing ? '执行中…' : '确认执行' }}
        </button>
        <span v-if="!canExecute && !executed && !cancelled && !executing" class="cmd-blocked">前置条件未通过</span>
      </div>
    </footer>
  </section>
</template>

<style scoped>
/* 命令卡片沿用系统视觉基线：1px 描边、小圆角、单一主色，不用渐变和阴影光效。 */
.ai-command-card {
  --cmd-line: var(--mes-border, var(--mes-border));
  --cmd-line-soft: var(--mes-border-light, #eef1f5);
  --cmd-bg: var(--mes-surface-muted, #f7f8fa);
  --cmd-text: var(--mes-text, var(--mes-text));
  --cmd-text-2: var(--mes-text-secondary, #646a73);
  --cmd-text-3: var(--mes-text-muted, var(--mes-text-muted));
  margin-top: 12px;
  padding: 12px 13px 11px;
  border: 1px solid var(--cmd-line);
  border-radius: 6px;
  background: var(--mes-surface, #fff);
}

.cmd-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cmd-mark {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 5px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.cmd-title {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.cmd-title strong {
  overflow: hidden;
  color: var(--cmd-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmd-target {
  overflow: hidden;
  color: var(--cmd-text-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmd-check {
  flex: none;
  padding: 2px 7px;
  color: var(--mes-warning, #d98a00);
  font-size: 11px;
  border: 1px solid var(--mes-warning, #d98a00);
  border-radius: 20px;
}

.cmd-check.is-pass {
  color: var(--mes-success, #17a673);
  border-color: var(--mes-success, #17a673);
}

.cmd-impacts {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.cmd-impacts li {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  color: var(--cmd-text-2);
  font-size: 12px;
  line-height: 1.6;
}

.impact-tag {
  flex: none;
  margin-top: 1px;
  padding: 0 5px;
  font-size: 10.5px;
  line-height: 16px;
  border: 1px solid var(--cmd-line);
  border-radius: 3px;
  background: var(--cmd-bg);
}

.impact-tag.is-high {
  color: var(--mes-danger, #d64545);
  border-color: var(--mes-danger, #d64545);
  background: var(--mes-danger-soft, #fdeeee);
}

.impact-tag.is-medium {
  color: var(--mes-warning, #d98a00);
  border-color: var(--mes-warning, #d98a00);
  background: var(--mes-warning-soft, #fff5e0);
}

.impact-tag.is-low {
  color: var(--cmd-text-3);
}

.cmd-params {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 6px 14px;
  margin: 10px 0 0;
  padding: 9px 10px;
  border: 1px solid var(--cmd-line-soft);
  border-radius: 4px;
  background: var(--cmd-bg);
}

.cmd-params div {
  display: flex;
  min-width: 0;
  align-items: baseline;
  gap: 6px;
}

.cmd-params dt {
  flex: none;
  color: var(--cmd-text-3);
  font-size: 11px;
}

.cmd-params dd {
  overflow: hidden;
  margin: 0;
  color: var(--cmd-text);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmd-checks {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin: 10px 0 0;
  padding: 0;
  list-style: none;
}

.cmd-checks li {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  line-height: 1.55;
}

.check-icon {
  display: inline-flex;
  width: 15px;
  height: 15px;
  flex: none;
  align-items: center;
  justify-content: center;
  margin-top: 1px;
  color: var(--mes-success, #17a673);
  border: 1px solid var(--mes-success, #17a673);
  border-radius: 50%;
}

.cmd-checks li.is-failed .check-icon {
  color: var(--mes-danger, #d64545);
  border-color: var(--mes-danger, #d64545);
}

.check-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
  color: var(--cmd-text-2);
}

.cmd-checks li.is-failed .check-text {
  color: var(--mes-danger, #d64545);
}

.check-text small {
  color: var(--cmd-text-3);
  font-size: 11px;
}

.cmd-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 9px 0 0;
  color: var(--mes-warning, #d98a00);
  font-size: 11.5px;
  line-height: 1.55;
}

.cmd-warn svg {
  flex: none;
  margin-top: 1px;
}

.cmd-result {
  display: flex;
  gap: 7px;
  margin-top: 10px;
  padding: 8px 10px;
  color: var(--cmd-text-2);
  border: 1px solid var(--mes-success, #17a673);
  border-radius: 4px;
  background: var(--mes-success-soft, #e7f7f0);
}

.cmd-result > svg {
  flex: none;
  margin-top: 1px;
  color: var(--mes-success, #17a673);
}

.cmd-result div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}

.cmd-result strong {
  color: var(--cmd-text);
  font-size: 12px;
  font-weight: 600;
}

.cmd-result span {
  font-size: 11.5px;
  line-height: 1.5;
}
.cmd-result small {
  color: var(--cmd-text-3);
  font-size: 10.5px;
}

.cmd-error {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 9px 0 0;
  padding: 7px 9px;
  color: var(--mes-danger, #d64545);
  font-size: 11.5px;
  line-height: 1.55;
  border: 1px solid var(--mes-danger, #d64545);
  border-radius: 4px;
  background: var(--mes-danger-soft, #fdeeee);
}

.cmd-error svg {
  flex: none;
  margin-top: 1px;
}

.cmd-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px solid var(--cmd-line-soft);
}

.cmd-expire {
  color: var(--cmd-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.cmd-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

.cmd-button {
  padding: 4px 11px;
  color: var(--cmd-text-2);
  font-size: 12px;
  border: 1px solid var(--cmd-line);
  border-radius: 4px;
  background: var(--mes-surface, #fff);
  cursor: pointer;
  transition:
    background-color 140ms ease,
    border-color 140ms ease;
}

.cmd-button:hover:not(:disabled) {
  background: var(--cmd-bg);
}

.cmd-button.is-primary {
  color: #fff;
  border-color: var(--mes-primary);
  background: var(--mes-primary);
}

.cmd-button.is-primary:hover:not(:disabled) {
  background: var(--mes-primary-hover, #1859c4);
}

.cmd-button:disabled {
  color: var(--cmd-text-3);
  border-color: var(--cmd-line);
  background: var(--cmd-bg);
  cursor: not-allowed;
}

.cmd-blocked {
  color: var(--mes-danger, #d64545);
  font-size: 11px;
}

@media (prefers-reduced-motion: reduce) {
  .ai-command-card * {
    transition-duration: 0.01ms !important;
  }
}
</style>
