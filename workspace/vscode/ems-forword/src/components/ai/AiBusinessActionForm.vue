<script setup>
import { computed, ref, watch } from 'vue';
import request from '@/api/request';
import { getAgentCandidates, previewStructuredCommand } from '@/api/ai/agent';
import { filterWorkflowCandidates, preferredIssueWarehouse, workflowTargetNumbers } from '@/utils/ai-business-flow';

const props = defineProps({
  entry: { type: Object, required: true },
  orderNo: { type: String, default: '' },
  conversationId: { type: [String, Number], default: null },
});
const emit = defineEmits(['previewed', 'question']);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const candidates = ref([]);
const warehouses = ref([]);
const progress = ref(null);
const targetNo = ref('');
const warehouseId = ref(null);
const targetLocationId = ref(null);
const targetLocations = ref([]);
const qualified = ref(null);
const defect = ref(0);
const auditResult = ref('APPROVED');
const remark = ref('');
const action = computed(() => props.entry.code);
const selected = computed(() => candidates.value.find((x) => x.bizNo === targetNo.value));
const workOrder = computed(() => progress.value?.workOrders?.find((x) => x.workOrderNo === targetNo.value));
const isIssue = computed(() => action.value === 'GENERATE_MATERIAL_ISSUE');
const isTransfer = computed(() => action.value === 'TRANSFER_TO_LINE_SIDE');
const isReport = computed(() => action.value === 'SUBMIT_WORK_REPORT');
const canPreview = computed(
  () =>
    !loading.value &&
    !saving.value &&
    Boolean(targetNo.value) &&
    (!isIssue.value || Boolean(warehouseId.value)) &&
    (!isTransfer.value || Boolean(targetLocationId.value)) &&
    (!isReport.value ||
      (qualified.value !== null && qualified.value >= 0 && defect.value >= 0 && qualified.value + defect.value > 0)),
);

function candidateLabel(item) {
  const node = progress.value?.workOrders?.find((x) => x.workOrderNo === item.bizNo);
  const doc = progress.value?.documents?.find((x) => x.docNo === item.bizNo);
  return [node?.productName || doc?.relation, item.bizNo].filter(Boolean).join(' · ');
}

async function load() {
  loading.value = true;
  error.value = '';
  candidates.value = [];
  targetNo.value = '';
  try {
    if (props.orderNo) {
      const list =
        (
          await request.get('/api/mfg/production-order/page', {
            params: { current: 1, size: 100, keyword: props.orderNo },
          })
        ).data?.records || [];
      const order = list.find((x) => x.productionOrderNo === props.orderNo);
      if (!order) {
        throw new Error('当前订单已不存在，请先在对话中明确要办理的订单。');
      }
      progress.value = (await request.get(`/api/mfg/production-order/${order.productionOrderId}/progress`)).data;
    } else {
      progress.value = null;
    }
    if (isIssue.value) {
      warehouses.value = (
        (await request.get('/api/wms/warehouse/page', { params: { current: 1, size: 100 } })).data?.records || []
      ).filter((x) => x.status === 'ENABLE' && ['RAW', 'SEMI_FINISHED'].includes(x.warehouseType));
    }
    const targetNos = workflowTargetNumbers(progress.value);
    const response = await getAgentCandidates({
      capability: action.value,
      params: targetNos === null ? {} : { targetNos },
    });
    candidates.value = filterWorkflowCandidates(response.data?.candidates || [], progress.value, action.value);
    if (!candidates.value.length) {
      error.value = '当前订单没有这一步的待办单据。请核对上一步是否已完成；已完成的操作无需重做。';
      return;
    }
    // 多张单据时让用户明确选择；唯一候选才预选。
    if (candidates.value.length === 1) {
      targetNo.value = candidates.value[0].bizNo;
    }
  } catch (failure) {
    error.value = failure?.displayMessage || failure?.data?.message || failure.message || '读取待办失败，请重试。';
  } finally {
    loading.value = false;
  }
}

watch(() => props.entry.flowKey, load, { immediate: true });
watch(targetNo, async () => {
  if (isTransfer.value && selected.value) {
    targetLocationId.value = null;
    targetLocations.value = [];
    const selectedNo = targetNo.value;
    try {
      const response = await request.get(`/api/wms/outbound-order/${selected.value.bizId}/line-side-preparation`);
      if (targetNo.value !== selectedNo) {
        return;
      }
      targetLocations.value = response.data?.locations || [];
      targetLocationId.value = response.data?.recommendedLocationId || null;
      if (!targetLocations.value.length) {
        error.value = response.data?.explanation || '本产线没有可用线边库位';
      }
    } catch (failure) {
      error.value = failure?.displayMessage || '读取本产线线边库位失败，请重试。';
    }
    return;
  }
  if (!isIssue.value || !selected.value) {
    return;
  }
  try {
    const type =
      workOrder.value?.workOrderType ||
      (await request.get(`/api/mfg/work-order/${selected.value.bizId}`)).data?.workOrderType;
    warehouseId.value = preferredIssueWarehouse(warehouses.value, type);
  } catch {
    warehouseId.value = null;
    error.value = '无法确认工单类型，请重新读取后选择仓库。';
  }
});

async function preview() {
  if (!canPreview.value) {
    return;
  }
  saving.value = true;
  error.value = '';
  try {
    const params = { targetNo: targetNo.value, remark: remark.value || undefined };
    if (isIssue.value) {
      params.warehouseId = warehouseId.value;
    }
    if (isTransfer.value) {
      params.targetLocationId = targetLocationId.value;
    }
    if (isReport.value) {
      params.qualifiedQuantity = qualified.value;
      params.defectQuantity = defect.value;
    }
    if (action.value === 'AUDIT_WORK_REPORT') {
      params.auditResult = auditResult.value;
    }
    const response = await previewStructuredCommand({
      action: action.value,
      ...params,
      conversationId: props.conversationId,
      source: 'WORKBENCH',
    });
    if (!response.data?.confirmationId) {
      throw new Error('没有生成有效预览，请重试。');
    }
    emit('previewed', response.data);
  } catch (failure) {
    error.value = failure?.displayMessage || failure?.data?.message || failure.message || '预览失败，请重试。';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <form class="business-action-form" @submit.prevent="preview">
    <p>{{ entry.prompt }}</p>
    <p v-if="orderNo" class="order-context">当前订单：{{ orderNo }}</p>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <el-form label-position="top">
      <el-form-item label="选择单据" required>
        <el-select
          v-model="targetNo"
          filterable
          :loading="loading"
          placeholder="选择本次要办理的单据"
          style="width: 100%"
        >
          <el-option v-for="item in candidates" :key="item.bizNo" :label="candidateLabel(item)" :value="item.bizNo" />
        </el-select>
      </el-form-item>
      <p v-if="selected?.blockingReason" class="candidate-reason">
        上次核查：{{ selected.blockingReason }}。本次按填写条件重新检查。
      </p>
      <el-form-item v-if="isIssue" label="领料仓库" required>
        <el-select v-model="warehouseId" placeholder="选择领料仓库" style="width: 100%">
          <el-option
            v-for="item in warehouses"
            :key="item.warehouseId"
            :value="item.warehouseId"
            :label="item.warehouseName"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="isTransfer" label="目标线边库位" required>
        <el-select v-model="targetLocationId" placeholder="选择本产线的线边库位" style="width: 100%">
          <el-option
            v-for="item in targetLocations"
            :key="item.locationId"
            :value="item.locationId"
            :label="`${item.locationCode} ${item.locationName}`"
          />
        </el-select>
      </el-form-item>
      <template v-if="isReport">
        <el-form-item label="实际合格数量" required
          ><el-input-number v-model="qualified" :min="0" :precision="0"
        /></el-form-item>
        <el-form-item label="实际不良数量" required
          ><el-input-number v-model="defect" :min="0" :precision="0"
        /></el-form-item>
      </template>
      <el-form-item v-if="action === 'AUDIT_WORK_REPORT'" label="审核结论" required>
        <el-select v-model="auditResult"
          ><el-option label="审核通过" value="APPROVED" /><el-option label="驳回" value="REJECTED"
        /></el-select>
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="remark" placeholder="按实际情况填写" /></el-form-item>
    </el-form>
    <div class="form-buttons">
      <el-button :loading="loading" @click="load">重新读取</el-button>
      <el-button @click="emit('question', `查询 ${orderNo || targetNo || '当前订单'} 的当前进度和下一步`)"
        >问 AI 下一步</el-button
      >
      <el-button type="primary" native-type="submit" :loading="saving" :disabled="!canPreview">生成预览</el-button>
    </div>
  </form>
</template>

<style scoped>
.business-action-form p {
  margin: 0 0 12px;
  line-height: 1.6;
  color: var(--mes-text-secondary);
}
.business-action-form .order-context {
  color: var(--mes-text);
}
.business-action-form .candidate-reason {
  font-size: 12px;
}
.business-action-form .el-form {
  margin-top: 14px;
}
.form-buttons {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
