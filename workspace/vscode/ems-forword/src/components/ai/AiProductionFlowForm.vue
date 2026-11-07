<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { getAgentCandidates, getScheduleOptions, previewStructuredCommand } from '@/api/ai/agent';

/*
 * 排产 / 下达工单表单：助手要求「提供生产订单号」「提供工单号」时，把库里真实可办的单据摊成下拉，
 * 排产再把该订单可用的产线列出来，选完直接出预览卡片。
 * 时段不由用户填：产线选定后仍由排程服务算最早可排窗口，与对话入口结果一致。
 */
const props = defineProps({
  // SCHEDULE_PRODUCTION_ORDER 排产；RELEASE_WORK_ORDER 下达工单。
  action: { type: String, required: true },
  conversationId: { type: [String, Number], default: null },
  disabled: { type: Boolean, default: false },
  // 上下文里已经能确定的单号，例如上一轮刚建好的订单，直接预选，省一次点击。
  defaultOrderNo: { type: String, default: '' },
});

const emit = defineEmits(['previewed', 'error']);

const isSchedule = computed(() => props.action === 'SCHEDULE_PRODUCTION_ORDER');

const loading = ref(false);
const submitting = ref(false);
const errorMessage = ref('');
const orders = ref([]);
const lines = ref([]);
const workOrders = ref([]);
const orderNo = ref('');
const lineCode = ref('');
const workOrderNo = ref('');

const selectedLine = computed(() => lines.value.find((item) => item.lineCode === lineCode.value) || null);
const selectedWorkOrder = computed(
  () => workOrders.value.find((item) => item.bizNo === workOrderNo.value) || null,
);

const canSubmit = computed(() => {
  if (submitting.value || props.disabled) {
    return false;
  }
  return isSchedule.value ? Boolean(orderNo.value) && Boolean(lineCode.value) : Boolean(workOrderNo.value);
});

function windowText(line) {
  if (!line?.plannedStartTime || !line?.plannedEndTime) {
    return '';
  }
  return `${line.plannedStartTime.replace('T', ' ').slice(0, 16)} 起，预计 ${line.plannedEndTime
    .replace('T', ' ')
    .slice(0, 16)} 完工`;
}

async function loadOrders() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await getScheduleOptions();
    const data = response?.data || {};
    orders.value = Array.isArray(data.orders) ? data.orders : [];
    if (!orders.value.length) {
      errorMessage.value = '当前没有待排产的生产订单（只有新建状态且未生成计划的订单可以排产）。';
      return;
    }
    // 上下文给的单号优先，其次唯一候选直接代填；多个候选仍让用户自己选。
    const preferred = props.defaultOrderNo
      ? orders.value.find((item) => item.productionOrderNo === props.defaultOrderNo)
      : null;
    if (preferred) {
      orderNo.value = preferred.productionOrderNo;
    } else if (orders.value.length === 1) {
      orderNo.value = orders.value[0].productionOrderNo;
    }
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '待排产订单加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

async function loadLines() {
  lines.value = [];
  lineCode.value = '';
  if (!orderNo.value) {
    return;
  }
  try {
    const response = await getScheduleOptions(orderNo.value);
    const data = response?.data || {};
    lines.value = Array.isArray(data.lines) ? data.lines : [];
    if (!lines.value.length) {
      errorMessage.value = '这张订单当前没有可用产线与时段，请先检查产能与排程。';
      return;
    }
    lineCode.value = lines.value[0].lineCode;
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '可用产线加载失败，请稍后重试。';
  }
}

async function loadWorkOrders() {
  loading.value = true;
  errorMessage.value = '';
  try {
    const response = await getAgentCandidates({ capability: 'RELEASE_WORK_ORDER' });
    const data = response?.data || {};
    workOrders.value = Array.isArray(data.candidates) ? data.candidates : [];
    if (!workOrders.value.length) {
      errorMessage.value = '当前没有可下达的工单（只有草稿或已审核状态的工单可以下达）。';
      return;
    }
    const usable = workOrders.value.filter((item) => item.canExecute);
    if (usable.length === 1) {
      workOrderNo.value = usable[0].bizNo;
    }
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '工单候选加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

async function submit() {
  if (!canSubmit.value) {
    return;
  }
  submitting.value = true;
  errorMessage.value = '';
  try {
    const payload = {
      action: props.action,
      conversationId: props.conversationId || undefined,
      source: 'WORKBENCH',
    };
    if (isSchedule.value) {
      payload.orderNo = orderNo.value;
      payload.lineCode = lineCode.value;
    } else {
      payload.targetNo = workOrderNo.value;
    }
    const response = await previewStructuredCommand(payload);
    const command = response?.data || {};
    if (!command.confirmationId) {
      errorMessage.value = '预览没有生成确认凭据，请稍后重试。';
      return;
    }
    emit('previewed', command);
  } catch (error) {
    const message = error?.displayMessage || error?.data?.message || '生成预览失败，请稍后重试。';
    errorMessage.value = message;
    emit('error', message);
  } finally {
    submitting.value = false;
  }
}

watch(orderNo, (value, previous) => {
  if (!isSchedule.value) {
    return;
  }
  if (value === previous) {
    return;
  }
  loadLines();
});

onMounted(() => {
  if (isSchedule.value) {
    loadOrders();
  } else {
    loadWorkOrders();
  }
});
</script>

<template>
  <div class="flow-form">
    <header class="form-head">
      <AppIcon name="plus" :size="13" />
      <span>{{ isSchedule ? '选择要排产的生产订单' : '选择要下达的工单' }}</span>
      <span class="form-hint">选好后生成预览，确认才会真正执行</span>
    </header>

    <div v-if="loading" class="form-state">正在加载候选单据…</div>

    <div v-else-if="isSchedule" class="form-grid">
      <label class="form-field">
        <span class="field-label">生产订单</span>
        <el-select v-model="orderNo" size="small" filterable placeholder="选择生产订单" :disabled="submitting">
          <el-option
            v-for="item in orders"
            :key="item.productionOrderNo"
            :label="`${item.productionOrderNo} 数量 ${item.orderQuantity ?? '-'}`"
            :value="item.productionOrderNo"
          />
        </el-select>
      </label>

      <label class="form-field">
        <span class="field-label">产线</span>
        <el-select v-model="lineCode" size="small" filterable placeholder="选择产线" :disabled="submitting || !lines.length">
          <el-option
            v-for="item in lines"
            :key="item.lineCode"
            :label="`${item.lineCode} ${item.lineName || ''}`.trim()"
            :value="item.lineCode"
          />
        </el-select>
      </label>
    </div>

    <div v-else class="form-grid">
      <label class="form-field">
        <span class="field-label">生产工单</span>
        <el-select v-model="workOrderNo" size="small" filterable placeholder="选择工单" :disabled="submitting">
          <el-option
            v-for="item in workOrders"
            :key="item.bizNo"
            :label="`${item.bizNo} ${item.status || ''}`.trim()"
            :value="item.bizNo"
            :disabled="!item.canExecute"
          />
        </el-select>
      </label>
    </div>

    <p v-if="isSchedule && selectedLine" class="form-note">系统建议时段：{{ windowText(selectedLine) }}</p>
    <p v-if="!isSchedule && selectedWorkOrder && !selectedWorkOrder.canExecute" class="form-note is-warn">
      这张工单暂时不能下达：{{ selectedWorkOrder.blockingReason || '前置条件未满足' }}
    </p>
    <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>

    <footer class="form-foot">
      <button class="form-submit" type="button" :disabled="!canSubmit" @click="submit">
        <AppIcon name="play" :size="11" />
        <span>{{ submitting ? '生成中…' : '生成预览' }}</span>
      </button>
      <span class="form-safe">{{ isSchedule ? '确认后生成生产计划并把订单置为已下达，不会自动报工。' : '确认后冻结物料与工艺快照，工单变为可执行。' }}</span>
    </footer>
  </div>
</template>

<style scoped>
.flow-form {
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 8px;
  background: var(--mes-surface, #fff);
}

.form-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--mes-text-primary, var(--mes-text));
  font-size: 12.5px;
  font-weight: 600;
}

.form-hint {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
  font-weight: 400;
}

.form-state {
  padding: 10px 0 4px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
}

.form-grid {
  display: grid;
  margin-top: 8px;
  gap: 8px 10px;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  color: var(--mes-text-secondary, #646a73);
  font-size: 11.5px;
}

.form-note {
  margin: 6px 0 0;
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
}

.form-note.is-warn {
  color: var(--mes-warning, #d2760b);
}

.form-error {
  margin: 8px 0 0;
  color: var(--mes-danger, #d83931);
  font-size: 11.5px;
}

.form-foot {
  display: flex;
  align-items: center;
  margin-top: 10px;
  gap: 10px;
  flex-wrap: wrap;
}

.form-submit {
  display: inline-flex;
  align-items: center;
  padding: 5px 12px;
  color: #fff;
  font-size: 12px;
  border: 1px solid var(--mes-primary, #1d6ef2);
  border-radius: 5px;
  background: var(--mes-primary, #1d6ef2);
  cursor: pointer;
  gap: 5px;
}

.form-submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-safe {
  color: var(--mes-text-muted, var(--mes-text-muted));
  font-size: 11px;
}
</style>
