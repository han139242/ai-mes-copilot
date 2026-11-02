<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { executeAgentSequence, getAgentSequence } from '@/api/ai/agent';

// 多步命令卡片：一句话里连着办几件事时，把每一步的目标、前置条件、执行结果摊开，
// 某一步失败时其余步骤不回滚，用户可以从失败那一步接着办。
const props = defineProps({
  sequence: { type: Object, required: true },
});
const emit = defineEmits(['resolved']);

const executing = ref(false);
const executed = ref(false);
const cancelled = ref(false);
const errorMessage = ref('');
const result = ref(null);
// 卡片状态以后台记录为准：按 planId 回读，避免「已执行」又变回可点击。
const restored = ref(null);
const now = ref(Date.now());
let timer;

const planId = computed(() => props.sequence?.planId || '');
const steps = computed(() => props.sequence?.steps || []);
const totalSteps = computed(() => props.sequence?.totalSteps || steps.value.length);

const expiresAt = computed(() => {
  const raw = props.sequence?.expiresAt;
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

// 展示用步骤：执行过就以后台回读的结果为准，否则用预览里的计划。
const viewSteps = computed(() => {
  const backendSteps = restored.value?.steps;
  let current = steps.value;
  if (result.value?.steps?.length) {
    current = result.value.steps;
  }
  if (Array.isArray(backendSteps) && backendSteps.length) {
    current = backendSteps;
  }
  return current.map((step) => {
    const original = steps.value.find((item) => item.stepNo === step.stepNo) || {};
    return { ...original, ...step, actionLabel: step.actionLabel || original.actionLabel || step.action,
      targetType: step.targetType || original.targetType };
  });
});

const passedCount = computed(() => steps.value.filter((item) => item?.canExecute).length);
const overallCanExecute = computed(() => Boolean(props.sequence?.overallCanExecute));
const backendStatus = computed(() => String(restored.value?.status || result.value?.status || '').toUpperCase());
const running = computed(() => ['SUCCEEDED', 'PARTIAL_FAILED'].includes(backendStatus.value));
const canExecute = computed(
  () => overallCanExecute.value && !expired.value && !executed.value && !cancelled.value && !executing.value,
);

const statusText = computed(() => {
  const map = {
    SUCCEEDED: '全部步骤已执行成功',
    PARTIAL_FAILED: '部分步骤执行失败，可从失败步骤继续',
    FAILED: '执行失败',
    REJECTED: '已取消，未执行任何业务操作',
    EXPIRED: '预览已过期，请重新提问',
    EXECUTING: '正在执行',
    PREVIEW: '等待确认',
  };
  return map[backendStatus.value] || '';
});

const STATUS_META = {
  SUCCEEDED: { label: '已完成', className: 'is-ok' },
  FAILED: { label: '失败', className: 'is-bad' },
  PARTIAL_FAILED: { label: '部分失败', className: 'is-bad' },
  RUNNING: { label: '执行中', className: 'is-run' },
  PENDING: { label: '待执行', className: 'is-wait' },
};

function stepMeta(step) {
  const raw = String(step?.status || (step?.ok ? 'SUCCEEDED' : 'PENDING')).toUpperCase();
  return STATUS_META[raw] || STATUS_META.PENDING;
}

function formatCountdown(seconds) {
  if (seconds < 0) {
    return '';
  }
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${minutes}:${String(rest).padStart(2, '0')}`;
}

function newIdempotencyKey() {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch {
    // 非安全上下文没有 randomUUID，退回时间戳 + 随机数。
  }
  return `ai-seq-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

// 失败步骤的下标，用于「从这一步继续」。
const firstFailedStepNo = computed(() => {
  const rows = viewSteps.value || [];
  const failed = rows.find((item) => item?.ok === false || String(item?.status || '').toUpperCase() === 'FAILED');
  return failed?.stepNo || null;
});

async function confirmExecute() {
  if (!canExecute.value) {
    return;
  }
  executing.value = true;
  errorMessage.value = '';
  try {
    const response = await executeAgentSequence({
      planId: planId.value,
      idempotencyKey: newIdempotencyKey(),
      remark: 'AI 工作台人工确认执行',
    });
    applyResult(response?.data || {});
    emit('resolved', response?.data || {});
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '多步执行接口暂不可用，请稍后重试。';
  } finally {
    executing.value = false;
  }
}

// 从失败那一步继续：已成功的步骤不会重复执行，后端按 startStepNo 跳过。
async function resumeFromFailure() {
  const startStepNo = firstFailedStepNo.value;
  if (!planId.value || executing.value || !startStepNo) {
    return;
  }
  executing.value = true;
  errorMessage.value = '';
  try {
    const response = await executeAgentSequence({
      planId: planId.value,
      idempotencyKey: newIdempotencyKey(),
      startStepNo,
      remark: 'AI 工作台从失败步骤继续',
    });
    applyResult(response?.data || {});
    emit('resolved', response?.data || {});
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '继续执行失败，请稍后重试。';
  } finally {
    executing.value = false;
  }
}

function applyResult(payload) {
  const status = String(payload.status || '').toUpperCase();
  if (status === 'FAILED' || status === 'REJECTED' || status === 'EXPIRED') {
    errorMessage.value = payload.errorMessage || '多步执行未成功，请查看执行监控。';
    return;
  }
  result.value = payload;
  restored.value = payload;
  executed.value = status === 'SUCCEEDED' || status === 'PARTIAL_FAILED';
  if (status === 'PARTIAL_FAILED') {
    errorMessage.value = payload.errorMessage || '部分步骤未完成，可从失败步骤继续。';
  }
}

function cancelCommand() {
  cancelled.value = true;
  emit('resolved', { status: 'CANCELLED' });
}

// 重进页面时按 planId 回读后台记录：状态与每一步结果都以服务端为准。
async function restoreFromBackend() {
  if (!planId.value) {
    return;
  }
  try {
    const response = await getAgentSequence(planId.value);
    const data = response?.data || {};
    restored.value = data;
    const status = String(data.status || '').toUpperCase();
    if (status === 'SUCCEEDED' || status === 'PARTIAL_FAILED') {
      executed.value = true;
      if (status === 'PARTIAL_FAILED') {
        errorMessage.value = data.errorMessage || '部分步骤未完成，可从失败步骤继续。';
      }
    } else if (status === 'REJECTED' || status === 'FAILED' || status === 'EXPIRED') {
      cancelled.value = status === 'REJECTED';
      errorMessage.value = status === 'FAILED' ? data.errorMessage || '多步执行失败。' : '';
    }
  } catch {
    // 接口不可用时保持预览态，用户仍可点确认执行。
  }
}

onMounted(() => {
  restoreFromBackend();
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
  <section class="ai-sequence-card" :class="{ 'is-executed': executed, 'is-expired': expired }">
    <header class="seq-head">
      <span class="seq-mark"><AppIcon name="workflow" :size="13" /></span>
      <div class="seq-title">
        <strong>连续办理 {{ totalSteps }} 步</strong>
        <span class="seq-sub">{{ sequence.rawText || '按顺序执行业务动作' }}</span>
      </div>
      <span v-if="totalSteps" class="seq-check" :class="{ 'is-pass': passedCount === totalSteps }">
        可执行 {{ passedCount }}/{{ totalSteps }}
      </span>
    </header>

    <ol class="seq-steps">
      <li v-for="step in viewSteps" :key="step.stepNo" :class="stepMeta(step).className">
        <div class="step-line">
          <span class="step-no">{{ step.stepNo }}</span>
          <span class="step-action">{{ step.actionLabel || step.action }}</span>
          <span class="step-status">{{ stepMeta(step).label }}</span>
        </div>
        <div class="step-target">
          <template v-if="step.targetNo">{{ step.targetType || '目标' }} · {{ step.targetNo }}</template>
          <template v-else-if="step.dependsOnPrevious">等待上一步产出单号</template>
          <template v-else>目标单据待确认</template>
        </div>
        <p v-if="step.message" class="step-message">{{ step.message }}</p>
        <p v-else-if="step.blockingReason" class="step-message is-bad">{{ step.blockingReason }}</p>
        <p v-if="step.bizNo" class="step-biz">
          业务单号 {{ step.bizNo }}
          <span v-if="step.bizRef">· 已生成可打开单据</span>
        </p>
      </li>
    </ol>

    <p v-if="errorMessage" class="seq-error">
      <AppIcon name="alert" :size="13" />
      <span>{{ errorMessage }}</span>
    </p>

    <footer class="seq-foot">
      <span class="seq-expire">
        <template v-if="cancelled">已取消，未执行任何业务操作</template>
        <template v-else-if="executed">{{ statusText || '已执行' }}</template>
        <template v-else-if="expired">预览已过期，请重新提问</template>
        <template v-else-if="statusText">{{ statusText }}</template>
        <template v-else-if="remainingSeconds >= 0">预览有效期 {{ formatCountdown(remainingSeconds) }}</template>
        <template v-else>确认后才会执行</template>
      </span>
      <div class="seq-actions">
        <button
          v-if="!executed && !cancelled"
          class="seq-button"
          type="button"
          :disabled="executing"
          @click="cancelCommand"
        >
          取消
        </button>
        <button
          v-if="backendStatus === 'PARTIAL_FAILED' && firstFailedStepNo && !cancelled"
          class="seq-button is-primary"
          type="button"
          :disabled="executing"
          @click="resumeFromFailure"
        >
          {{ executing ? '执行中…' : `从第 ${firstFailedStepNo} 步继续` }}
        </button>
        <button
          v-else-if="!executed && !cancelled"
          class="seq-button is-primary"
          type="button"
          :disabled="!canExecute"
          :title="canExecute ? '确认后按顺序执行每一步' : '有步骤前置条件未通过，无法执行'"
          @click="confirmExecute"
        >
          {{ executing ? '执行中…' : '确认执行' }}
        </button>
        <span v-if="!overallCanExecute && !executed && !cancelled" class="seq-blocked">有步骤前置条件未通过</span>
      </div>
    </footer>
  </section>
</template>

<style scoped>
/* 与单步命令卡片保持同一套视觉基线：1px 描边、小圆角、单一主色。 */
.ai-sequence-card {
  --seq-line: var(--mes-border, #e5e7eb);
  --seq-line-soft: var(--mes-border-light, #eef1f5);
  --seq-bg: var(--mes-surface-muted, #f7f8fa);
  --seq-text: var(--mes-text, #1f2329);
  --seq-text-2: var(--mes-text-secondary, #646a73);
  --seq-text-3: var(--mes-text-muted, #8f959e);
  margin-top: 12px;
  padding: 12px 13px 11px;
  border: 1px solid var(--seq-line);
  border-radius: 6px;
  background: var(--mes-surface, #fff);
}

.seq-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.seq-mark {
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

.seq-title {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
}

.seq-title strong {
  overflow: hidden;
  color: var(--seq-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.seq-sub {
  overflow: hidden;
  color: var(--seq-text-3);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.seq-check {
  flex: none;
  padding: 2px 7px;
  color: var(--mes-warning, #d98a00);
  font-size: 11px;
  border: 1px solid var(--mes-warning, #d98a00);
  border-radius: 20px;
}

.seq-check.is-pass {
  color: var(--mes-success, #17a673);
  border-color: var(--mes-success, #17a673);
}

.seq-steps {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 11px 0 0;
  padding: 0;
  list-style: none;
}

.seq-steps li {
  padding: 8px 10px;
  border: 1px solid var(--seq-line-soft);
  border-radius: 4px;
  background: var(--seq-bg);
}

.step-line {
  display: flex;
  align-items: center;
  gap: 7px;
}

.step-no {
  display: inline-flex;
  width: 16px;
  height: 16px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--seq-text-3);
  font-size: 10.5px;
  border: 1px solid var(--seq-line);
  border-radius: 50%;
  background: var(--mes-surface, #fff);
}

.step-action {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--seq-text);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.step-status {
  flex: none;
  font-size: 11px;
  color: var(--seq-text-3);
}

.seq-steps li.is-ok .step-status {
  color: var(--mes-success, #17a673);
}

.seq-steps li.is-bad .step-status {
  color: var(--mes-danger, #d64545);
}

.seq-steps li.is-run .step-status {
  color: var(--mes-primary);
}

.step-target {
  margin-top: 2px;
  padding-left: 23px;
  color: var(--seq-text-3);
  font-size: 11.5px;
}

.step-message {
  margin: 4px 0 0;
  padding-left: 23px;
  color: var(--seq-text-2);
  font-size: 11.5px;
  line-height: 1.55;
}

.step-message.is-bad {
  color: var(--mes-danger, #d64545);
}

.step-biz {
  margin: 3px 0 0;
  padding-left: 23px;
  color: var(--seq-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.seq-error {
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

.seq-error svg {
  flex: none;
  margin-top: 1px;
}

.seq-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 11px;
  padding-top: 10px;
  border-top: 1px solid var(--seq-line-soft);
}

.seq-expire {
  color: var(--seq-text-3);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.seq-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}

.seq-button {
  padding: 4px 11px;
  color: var(--seq-text-2);
  font-size: 12px;
  border: 1px solid var(--seq-line);
  border-radius: 4px;
  background: var(--mes-surface, #fff);
  cursor: pointer;
  transition:
    background-color 140ms ease,
    border-color 140ms ease;
}

.seq-button:hover:not(:disabled) {
  background: var(--seq-bg);
}

.seq-button.is-primary {
  color: #fff;
  border-color: var(--mes-primary);
  background: var(--mes-primary);
}

.seq-button.is-primary:hover:not(:disabled) {
  background: var(--mes-primary-hover, #1859c4);
}

.seq-button:disabled {
  color: var(--seq-text-3);
  border-color: var(--seq-line);
  background: var(--seq-bg);
  cursor: not-allowed;
}

.seq-blocked {
  color: var(--mes-danger, #d64545);
  font-size: 11px;
}

@media (prefers-reduced-motion: reduce) {
  .ai-sequence-card * {
    transition-duration: 0.01ms !important;
  }
}
</style>
