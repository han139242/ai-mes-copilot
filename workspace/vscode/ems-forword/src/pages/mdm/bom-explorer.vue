<script setup>
import MesPageHeading from '@/components/common/MesPageHeading.vue';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import dayjs from 'dayjs';
import { getBomExplosion } from '@/api/bom';
import { getResourceDetail, getResourcePage } from '@/api/resource';
import { hasPermission } from '@/utils/permission';
import { getCurrentUser } from '@/utils/auth';
import { isResourceFocusId } from '@/utils/resource-page-state';

const route = useRoute();
const routeBomId = isResourceFocusId(route.query.bomId) ? String(route.query.bomId) : '';
const bomId = ref(null);
const quantity = ref(100);
const asOf = ref(dayjs().format('YYYY-MM-DD'));
const useSemiStock = ref(false);
const boms = ref([]);
const selectedBom = ref(null);
const selecting = ref(false);
const loading = ref(false);
const error = ref('');
const result = ref(null);
const tab = ref('grossMaterials');
let pending;
let queryNumber = 0;
let searchNumber = 0;
const canReadStock = computed(
  () =>
    hasPermission('wms:inventory:view') &&
    (getCurrentUser().roles?.includes('SUPER_ADMIN') || getCurrentUser().dataScope === 'ALL'),
);
const rows = computed(() => result.value?.[tab.value] || []);
const kind = { RAW: '原材料', AUXILIARY: '辅料', SEMI_FINISHED: '半成品', FINISHED: '成品' };
function treeRows(nodes, prefix = '') {
  return nodes.map((node, i) => ({
    ...node,
    pathKey: `${prefix}/${i}-${node.itemId}`,
    children: treeRows(node.children, `${prefix}/${i}`),
  }));
}
const tree = computed(() => treeRows(result.value?.tree || []));

async function searchBoms(keyword = 'LAMP-BOM') {
  const number = ++searchNumber;
  selecting.value = true;
  try {
    const response = await getResourcePage('/api/mdm/bom', { current: 1, size: 50, bomCode: keyword }, { suppressErrorMessage: true });
    if (number !== searchNumber) {
      return;
    }
    const found = response.data?.records || [];
    boms.value = selectedBom.value && !found.some((row) => row.bomId === selectedBom.value.bomId)
      ? [selectedBom.value, ...found]
      : found;
    if (!bomId.value) {
      if (routeBomId) return;
      const defaultBom = boms.value.find((b) => b.bomCode === 'LAMP-BOM-L200-V1') || boms.value[0];
      selectedBom.value = defaultBom || null;
      bomId.value = defaultBom?.bomId || null;
    }
  } catch (e) {
    if (number === searchNumber) {
      error.value = e.displayMessage || e.message || '无法加载 BOM，请重试';
    }
  } finally {
    if (number === searchNumber) {
      selecting.value = false;
    }
  }
}

async function analyze() {
  if (!bomId.value || !quantity.value || !asOf.value) {
    error.value = '请选择 BOM、生效日期并填写生产数量';
    return;
  }
  pending?.abort();
  pending = new AbortController();
  const number = ++queryNumber;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    const response = await getBomExplosion(
      bomId.value,
      { quantity: quantity.value, asOf: asOf.value, useSemiStock: useSemiStock.value && canReadStock.value },
      pending.signal,
    );
    if (number === queryNumber) {
      result.value = response.data;
    }
  } catch (e) {
    if (number === queryNumber && e.code !== 'ERR_CANCELED') {
      error.value = e.displayMessage || e.message || '需求分析失败，请检查 BOM 和数量';
    }
  } finally {
    if (number === queryNumber) {
      loading.value = false;
    }
  }
}
watch([bomId, quantity, asOf, useSemiStock], () => {
  pending?.abort();
  ++queryNumber;
  loading.value = false;
  result.value = null;
  error.value = '';
  if (bomId.value) selectedBom.value = boms.value.find((row) => row.bomId === bomId.value) || selectedBom.value;
});
onMounted(async () => {
  await searchBoms(routeBomId ? '' : undefined);
  if (!routeBomId || queryNumber || !hasPermission('mdm:bom:view')) return;
  const search = searchNumber;
  const listed = boms.value.find((row) => String(row.bomId) === routeBomId);
  try {
    const selected = listed || (await getResourceDetail('/api/mdm/bom', routeBomId)).data;
    if (search !== searchNumber || bomId.value) return;
    if (!selected || String(selected.bomId) !== routeBomId) throw new Error('来源 BOM 暂不可用');
    selectedBom.value = selected;
    if (!listed) boms.value = [selected, ...boms.value];
    bomId.value = selected.bomId;
  } catch {
    if (search === searchNumber && !bomId.value) error.value = '未取得来源 BOM 版本，可在列表中手动选择。';
  }
});
onBeforeUnmount(() => {
  pending?.abort();
  ++queryNumber;
  ++searchNumber;
});
</script>

<template>
  <main class="bom-explorer">
    <MesPageHeading title="BOM 需求分析" icon="route" class="bom-heading">
      <template #actions>
        <el-tag type="info" effect="plain">只读分析</el-tag>
      </template>
    </MesPageHeading>
    <el-form class="bom-controls" label-position="top" novalidate @submit.prevent="analyze">
      <el-form-item label="产品物料清单" class="bom-selector">
        <el-select
          v-model="bomId"
          filterable
          remote
          :remote-method="searchBoms"
          :loading="selecting"
          :disabled="loading"
          placeholder="搜索 BOM 编码"
        >
          <el-option
            v-for="bom in boms"
            :key="bom.bomId"
            :value="bom.bomId"
            :label="`${bom.bomCode} · ${bom.bomVersion}`"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="生产数量"
        ><el-input-number v-model="quantity" :min="0.0001" :step="1" :disabled="loading" controls-position="right"
      /></el-form-item>
      <el-form-item label="BOM 生效日期"
        ><el-date-picker v-model="asOf" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="loading"
      /></el-form-item>
      <el-form-item label="半成品抵扣"
        ><el-checkbox v-model="useSemiStock" :disabled="!canReadStock || loading"
          >按当前可用库存</el-checkbox
        ></el-form-item
      >
      <el-form-item label=" "
        ><el-button type="primary" native-type="submit" :loading="loading">展开需求</el-button></el-form-item
      >
    </el-form>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="bom-message" />
    <div v-if="result" class="bom-results" v-loading="loading">
      <div class="bom-summary" aria-live="polite">
        <strong>{{ result.bomCode }} · {{ result.bomVersion }} · {{ result.quantity }} {{ result.unitName }}</strong
        ><span>生效日期 {{ result.asOf }}</span
        ><span>叶子物料 {{ result.distinctMaterialCount }} 种</span><span>原材料 {{ result.rawMaterialCount }} 种</span
        ><span>直接子项 {{ result.directItems.length }} 种</span>
      </div>
      <el-alert :title="result.stockBasis" type="info" :closable="false" show-icon class="bom-message" />
      <div class="bom-workspace">
        <section class="bom-structure">
          <h2>产品与组件结构</h2>
          <el-table :data="tree" row-key="pathKey" default-expand-all border :tree-props="{ children: 'children' }"
            ><el-table-column prop="materialName" label="物料 / 组件" min-width="220" /><el-table-column
              prop="quantity"
              label="理论数量"
              width="110" /><el-table-column prop="unitName" label="单位" width="65" /><el-table-column
              prop="childBomVersion"
              label="组件版本"
              width="100"
          /></el-table>
        </section>
        <section class="bom-demands">
          <h2>投料需求</h2>
          <el-tabs v-model="tab"
            ><el-tab-pane label="全部底层物料" name="grossMaterials" /><el-tab-pane
              label="抵扣组件后需求"
              name="netMaterials" /><el-tab-pane label="当前工单直接子项" name="directItems"
          /></el-tabs>
          <el-table :data="rows" border
            ><el-table-column prop="materialCode" label="物料编码" min-width="130" /><el-table-column
              prop="materialName"
              label="名称"
              min-width="145" /><el-table-column label="类型" width="95"
              ><template #default="{ row }">{{ kind[row.materialType] || row.materialType }}</template></el-table-column
            ><el-table-column prop="theoreticalQuantity" label="理论需求" width="110" /><el-table-column
              prop="executionQuantity"
              label="执行需求"
              width="110" /><el-table-column prop="roundingQuantity" label="取整增加" width="100" /><el-table-column
              prop="unitName"
              label="单位"
              width="65"
          /></el-table>
        </section>
      </div>
      <section class="bom-components">
        <h2>半成品生产缺口</h2>
        <el-table :data="result.components" border
          ><el-table-column prop="materialName" label="组件" min-width="180" /><el-table-column
            prop="bomCode"
            label="指定 BOM"
            min-width="210" /><el-table-column prop="requiredQuantity" label="需求" /><el-table-column
            prop="availableQuantity"
            label="当前可用" /><el-table-column prop="allocatedQuantity" label="本次分析抵扣" /><el-table-column
            prop="productionQuantity"
            label="待生产"
        /></el-table>
      </section>
    </div>
    <el-empty
      v-else-if="!error"
      :description="loading ? '正在分析组件结构与物料需求' : '选择产品和数量，查看组件结构及投料需求'"
    />
  </main>
</template>

<style scoped>
.bom-explorer {
  color: var(--el-text-color-primary);
}
.bom-heading {
  margin-bottom: 16px;
}
.bom-controls {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px 18px;
  padding: 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
}
.bom-controls :deep(.el-form-item) {
  margin-bottom: 0;
}
.bom-controls :deep(.el-date-editor) {
  width: 155px;
}
.bom-selector {
  flex: 1;
  min-width: 260px;
}
.bom-selector :deep(.el-select) {
  width: 100%;
}
.bom-message {
  margin: 16px 0;
}
.bom-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 25px;
  align-items: center;
  padding: 20px 0 5px;
  font-variant-numeric: tabular-nums;
}
.bom-summary strong {
  margin-right: auto;
}
.bom-workspace {
  display: grid;
  grid-template-columns: minmax(330px, 0.8fr) minmax(0, 1.4fr);
  gap: 22px;
}
.bom-workspace section {
  min-width: 0;
}
.bom-explorer h2 {
  font-size: 16px;
  margin: 18px 0 12px;
}
.bom-components {
  margin-top: 20px;
}
@media (max-width: 1100px) {
  .bom-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 600px) {
  .bom-selector {
    min-width: 100%;
  }
}
</style>
