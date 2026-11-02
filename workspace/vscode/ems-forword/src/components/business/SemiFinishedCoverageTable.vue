<script setup>
defineProps({ rows: { type: Array, default: () => [] } });
const qty = (value) =>
  value === null || value === undefined ? '--' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 });
</script>

<template>
  <div class="stage-materials">
    <p><strong>半成品供给对照</strong></p>
    <p class="muted">
      公共合格库存为共享余额，本次可用为其中分给该需求的部分；本单预留、已领及计划补产分别列示，计划补产尚未变成现货。
      订单需求不含追加用料，供给与缺料量按两者合计计算；追加用料是补产等增加的需求，不是实际废料数量。
    </p>
    <el-table :data="rows" border size="small" max-height="280" empty-text="暂无半成品需求">
      <el-table-column label="半成品" min-width="175" show-overflow-tooltip>
        <template #default="{ row }"
          >{{ row.materialName }}<small> {{ row.materialCode }}</small></template
        >
      </el-table-column>
      <el-table-column label="订单需求" width="100" align="right"
        ><template #default="{ row }">{{ qty(row.baseRequiredQuantity) }} {{ row.unit }}</template></el-table-column
      >
      <el-table-column label="追加用料" width="96" align="right"
        ><template #default="{ row }">{{ qty(row.supplementRequiredQuantity) }}</template></el-table-column
      >
      <el-table-column label="库存量" width="100" align="right"
        ><template #default="{ row }">{{ qty(row.qualifiedStockQuantity) }}</template></el-table-column
      >
      <el-table-column label="本次库存可用" width="112" align="right"
        ><template #default="{ row }">{{ qty(row.freeAllocatedQuantity) }}</template></el-table-column
      >
      <el-table-column label="本单预留" width="96" align="right"
        ><template #default="{ row }">{{ qty(row.reservedQuantity) }}</template></el-table-column
      >
      <el-table-column label="净领料" width="80" align="right"
        ><template #default="{ row }">{{ qty(row.issuedQuantity) }}</template></el-table-column
      >
      <el-table-column label="缺料量" width="90" align="right"
        ><template #default="{ row }">{{ qty(row.shortageQuantity) }}</template></el-table-column
      >
      <el-table-column label="计划补产" width="96" align="right"
        ><template #default="{ row }">{{ qty(row.plannedSupplyQuantity) }}</template></el-table-column
      >
      <el-table-column prop="workOrderNo" label="需求来源" min-width="190" show-overflow-tooltip />
    </el-table>
  </div>
</template>
