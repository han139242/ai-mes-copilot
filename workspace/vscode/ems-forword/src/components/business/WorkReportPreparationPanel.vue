<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { getResourceList } from '@/api/resource';

const props = defineProps({
  taskId: { type: [Number, String], default: null },
  reportId: { type: [Number, String], default: null },
  qualifiedQuantity: { type: Number, default: 0 },
  defectQuantity: { type: Number, default: 0 },
  editable: Boolean,
  canCreate: Boolean,
  empty: Boolean,
});
const emit = defineEmits(['materials', 'create', 'blocked', 'preparation']);
const data = ref(null);
const error = ref('');
const loading = ref(false);
let requestId = 0;
let timer;
const quantity = computed(() => Number(props.qualifiedQuantity || 0) + Number(props.defectQuantity || 0));

async function load(fill = false) {
  const current = ++requestId;
  emit('preparation', null);
  if (!props.taskId) {
    data.value = null;
    return;
  }
  loading.value = true;
  emit('blocked', true);
  error.value = '';
  try {
    const result = await getResourceList('/api/mfg/work-report/preparation', {
      taskId: props.taskId,
      reportId: props.reportId || undefined,
      quantity: props.editable && quantity.value > 0 ? quantity.value : undefined,
    });
    if (current !== requestId) {
      return;
    }
    data.value = result.data;
    emit('preparation', data.value);
    emit('blocked', false);
    if (fill && quantity.value > 0) {
      emit(
        'materials',
        (data.value.materials || [])
          .filter((m) => Number(m.suggestedQuantity) > 0)
          .map((m) => ({
            materialId: m.materialId,
            batchId: m.batchId,
            totalUsedQuantity: Number(m.suggestedQuantity),
            scrapQuantity: 0,
            remark: '按本工序及本工单已领批次建议，请核对实际消耗',
            _clientId: crypto.randomUUID(),
          })),
      );
    }
  } catch (e) {
    if (current === requestId) {
      error.value = e?.displayMessage || e?.message || '报工准备加载失败，请重试';
    }
  } finally {
    if (current === requestId) {
      loading.value = false;
    }
  }
}

watch(
  () => [props.taskId, props.reportId],
  () => load(false),
  { immediate: true },
);
watch(quantity, () => {
  if (!props.editable) {
    return;
  }
  clearTimeout(timer);
  emit('blocked', true);
  ++requestId;
  if (quantity.value <= 0) {
    emit('materials', []);
  }
  timer = setTimeout(() => load(true), 300);
});
onBeforeUnmount(() => {
  clearTimeout(timer);
  ++requestId;
});
</script>

<template>
  <section v-if="taskId" class="report-preparation" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="data">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="生产任务">{{ data.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="生产工单">{{ data.workOrderNo }}</el-descriptions-item>
        <el-descriptions-item label="生产产品">{{ data.productName }}</el-descriptions-item>
        <el-descriptions-item label="当前工序">{{ data.operationName }}</el-descriptions-item>
        <el-descriptions-item label="任务计划数量">{{ data.plannedQuantity }}</el-descriptions-item>
        <el-descriptions-item label="剩余可报数量">{{ data.remainingQuantity }}</el-descriptions-item>
        <el-descriptions-item label="已报合格 / 不良"
          >{{ data.reportedQualifiedQuantity }} / {{ data.reportedDefectQuantity }}</el-descriptions-item
        >
        <el-descriptions-item label="数量口径">含待审核报工，避免重复提交</el-descriptions-item>
      </el-descriptions>
      <template v-if="editable">
        <p>
          合格数量 +
          不良数量不得超过剩余可报数量。填写产量后自动带出本工序用料，修改产量会重新计算；保存前请核对实际消耗。
        </p>
        <el-button size="small" :disabled="quantity <= 0" @click="load(true)">重新带出本次用料</el-button>
        <el-collapse>
          <el-collapse-item :title="`查看本工序物料与可用领料（${data.materials.length} 个批次）`">
            <el-table :data="data.materials" size="small" border empty-text="本工序没有直接投料">
              <el-table-column prop="materialName" label="本工序物料" min-width="120" />
              <el-table-column prop="batchNo" label="已领批次" min-width="130" />
              <el-table-column prop="availableQuantity" label="可用领料" width="90" />
              <el-table-column
                prop="suggestedQuantity"
                :label="quantity > 0 ? '本次建议' : '剩余产量需用'"
                width="110"
              />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <p v-for="warning in data.warnings" :key="warning">{{ warning }}</p>
      </template>
      <template v-else>
        <p v-if="empty">该任务还没有保存的报工记录。任务下发不会自动生成报工，请点击“新增本任务报工”填写实际产量。</p>
        <el-button v-if="canCreate && data.canReport" type="primary" @click="emit('create', data.taskId)"
          >新增本任务报工</el-button
        >
        <p v-else-if="!data.canReport">
          {{
            data.remainingQuantity <= 0
              ? '该任务计划数量已报足，可查看或审核已有报工。'
              : '任务尚未开工或已结束，请先在生产任务中确认状态。'
          }}
        </p>
      </template>
    </template>
  </section>
</template>

<style scoped>
.report-preparation {
  margin: 16px 0;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.report-preparation p {
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
  margin: 10px 0;
}
.report-preparation .el-table {
  margin-top: 12px;
}
</style>
