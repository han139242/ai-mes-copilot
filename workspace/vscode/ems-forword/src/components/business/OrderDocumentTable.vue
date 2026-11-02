<script setup>
import dayjs from 'dayjs';

defineProps({
  rows: { type: Array, default: () => [] },
  workOrders: { type: Array, default: () => [] },
  emptyText: { type: String, default: '暂无关联单据' },
  typeLabel: { type: Function, required: true },
  statusLabel: { type: Function, required: true },
  formatQuantity: { type: Function, required: true },
});
const emit = defineEmits(['open-document']);

function workOrderLabel(row, workOrders) {
  const workOrder = workOrders.find((item) => String(item.workOrderId) === String(row.workOrderId));
  if (workOrder) {
    return `${workOrder.productName || ''} · ${workOrder.workOrderNo}`;
  }
  return row.workOrderId ? `工单 #${row.workOrderId}` : '--';
}
function relationLabel(value) {
  return (
    {
      'INCOMING 检验': '来料检验',
      'PROCESS 检验': '过程检验',
      'FINAL 检验': '生产终检',
      'OUTGOING 检验': '出货检验',
    }[value] ||
    value ||
    '--'
  );
}
</script>

<template>
  <el-table :data="rows" border size="small" :empty-text="emptyText" class="process-document-table">
    <el-table-column label="类型" width="112">
      <template #default="scope">{{ typeLabel(scope.row.type) }}</template>
    </el-table-column>
    <el-table-column label="单据号" min-width="205" show-overflow-tooltip>
      <template #default="scope">
        <el-button v-if="scope.row.targetResource" link type="primary" @click="emit('open-document', scope.row)">
          {{ scope.row.docNo || '--' }}
        </el-button>
        <span v-else>{{ scope.row.docNo || '--' }}</span>
      </template>
    </el-table-column>
    <el-table-column label="对应工单" min-width="240" show-overflow-tooltip>
      <template #default="scope">{{ workOrderLabel(scope.row, workOrders) }}</template>
    </el-table-column>
    <el-table-column label="状态" width="108">
      <template #default="scope">{{ statusLabel(scope.row.status) }}</template>
    </el-table-column>
    <el-table-column label="数量" width="110" align="right">
      <template #default="scope"> {{ formatQuantity(scope.row.quantity) }} {{ scope.row.unit || '' }} </template>
    </el-table-column>
    <el-table-column label="时间" width="152">
      <template #default="scope">
        {{ scope.row.time && dayjs(scope.row.time).isValid() ? dayjs(scope.row.time).format('MM-DD HH:mm') : '--' }}
      </template>
    </el-table-column>
    <el-table-column label="工序 / 关联说明" min-width="180" show-overflow-tooltip>
      <template #default="scope">{{ relationLabel(scope.row.relation) }}</template>
    </el-table-column>
    <el-table-column prop="handlerName" label="经办人 / 工位" width="120" show-overflow-tooltip />
  </el-table>
</template>
