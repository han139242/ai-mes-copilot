<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import dayjs from 'dayjs';
import { hasPermission } from '@/utils/permission';
import {
  getPreparationProducts,
  getPreparation,
  editProductParameter,
  reviewProductParameter,
  reviewInspectionStandard,
} from '@/api/manufacturing-preparation';

const router = useRouter();
const products = ref([]);
const productId = ref(null);
const asOf = ref(dayjs().format('YYYY-MM-DD'));
const report = ref(null);
const loading = ref(false);
const booting = ref(false);
const saving = ref(false);
const error = ref('');
const dialog = ref(false);
const editError = ref('');
const editing = ref(null);
const tab = ref('parameters');
const form = reactive({ parameterValue: '', sourceReference: '' });
const canEdit = computed(() => hasPermission('mdm:product-parameter:update'));
const canConfirm = computed(() => hasPermission('mdm:product-parameter:confirm'));
const canReviewQuality = computed(() => hasPermission('qms:inspection-standard:confirm'));
const openCount = computed(() => report.value?.checks.filter((row) => row.status !== 'PASS').length || 0);
const readinessStatus = computed(() => {
  if (!report.value) {
    return { type: 'info', label: '待检查' };
  }
  if (!report.value.executionReady) {
    return { type: 'warning', label: '待补齐准备项' };
  }
  const simulation = report.value.parameters.some((row) => /演练|模拟/.test(row.sourceReference || ''));
  return { type: 'success', label: simulation ? '模拟准备就绪' : '具备执行条件' };
});
function productLabel(row) {
  const duplicated = products.value.filter((item) => item.productName === row.productName).length > 1;
  return duplicated ? `${row.productName} · ${row.productCode}` : row.productName;
}
const label = (status) =>
  ({ DRAFT: '草稿', CONFIRMED: '依据已确认', LEGACY: '历史标准', RELEASED: '已发布', DISABLE: '停用' })[status] ||
  status;
const typeLabel = (type) =>
  ({ FINAL: '终检', PROCESS: '过程检', INCOMING: '来料检', OUTGOING: '出货检' })[type] || type;
const message = (failure) => failure.displayMessage || failure.message || '请求失败，请重试';
let controller;
let sequence = 0;
let alive = true;
async function inspect() {
  if (!alive || !productId.value || !asOf.value) {
    return;
  }
  controller?.abort();
  controller = new AbortController();
  const requestId = ++sequence;
  loading.value = true;
  error.value = '';
  report.value = null;
  try {
    const response = await getPreparation(productId.value, asOf.value, controller.signal);
    if (alive && requestId === sequence) {
      report.value = response.data;
    }
  } catch (failure) {
    if (alive && requestId === sequence && failure.code !== 'ERR_CANCELED') {
      error.value = message(failure);
    }
  } finally {
    if (alive && requestId === sequence) {
      loading.value = false;
    }
  }
}
async function initialize() {
  booting.value = true;
  error.value = '';
  try {
    const response = await getPreparationProducts();
    if (!alive) {
      return;
    }
    products.value = response.data || [];
    productId.value ||=
      products.value.find((row) => row.productCode === 'LAMP-L200')?.productId || products.value[0]?.productId || null;
    if (!productId.value) {
      error.value = '尚无受控制造产品，请先完成产品模板初始化';
    }
  } catch (failure) {
    if (alive) {
      error.value = message(failure);
    }
  } finally {
    if (alive) {
      booting.value = false;
    }
  }
}
function edit(row) {
  editing.value = row;
  form.parameterValue = row.parameterValue || '';
  form.sourceReference = row.sourceReference || '';
  editError.value = '';
  dialog.value = true;
}
async function save() {
  saving.value = true;
  editError.value = '';
  try {
    await editProductParameter(editing.value.parameterId, { ...form, version: editing.value.version });
    dialog.value = false;
    ElMessage.success('参数草稿已保存');
    await inspect();
  } catch (failure) {
    editError.value = message(failure);
  } finally {
    saving.value = false;
  }
}
async function review(row, quality = false) {
  const confirmed = (quality ? row.confirmationStatus : row.status) === 'CONFIRMED';
  try {
    await ElMessageBox.confirm(
      confirmed
        ? '退回后可修改，修改后的内容需要重新确认。'
        : '将本条内容确认为当前设计依据。此操作不代表实物已经检验合格。',
      confirmed ? '退回修改' : '确认设计依据',
      { type: 'warning', confirmButtonText: confirmed ? '退回草稿' : '确认依据', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  saving.value = true;
  error.value = '';
  try {
    const action = confirmed ? 'reopen' : 'confirm';
    if (quality) {
      await reviewInspectionStandard(row.standardId, row.version, action);
    } else {
      await reviewProductParameter(row.parameterId, row.version, action);
    }
    ElMessage.success(confirmed ? '已退回草稿' : '设计依据已确认');
    await inspect();
  } catch (failure) {
    error.value = message(failure);
  } finally {
    saving.value = false;
  }
}
watch([productId, asOf], () => {
  dialog.value = false;
  inspect();
});
onMounted(initialize);
onBeforeUnmount(() => {
  alive = false;
  ++sequence;
  controller?.abort();
});
</script>

<template>
  <main class="preparation-page">
    <header class="preparation-heading">
      <div>
        <h1>制造准备检查</h1>
      </div>
      <el-tag :type="readinessStatus.type" effect="plain">{{ readinessStatus.label }}</el-tag>
    </header>
    <el-form class="preparation-controls" label-position="top" @submit.prevent="inspect">
      <el-form-item label="产品 / 组件" class="product-control"
        ><el-select v-model="productId" :disabled="booting || loading || saving" placeholder="选择产品或组件"
          ><el-option
            v-for="row in products"
            :key="row.productId"
            :value="row.productId"
            :label="productLabel(row)"
            :title="row.productCode" /></el-select
      ></el-form-item>
      <el-form-item label="检查日期"
        ><el-date-picker
          v-model="asOf"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          :disabled="loading || saving"
      /></el-form-item>
      <el-form-item label=" "
        ><el-button type="primary" native-type="submit" :loading="loading" :disabled="saving">重新检查</el-button
        ><el-button v-if="!products.length" :disabled="loading" @click="initialize"
          >重新加载产品</el-button
        ></el-form-item
      >
    </el-form>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="preparation-error" />
    <el-empty v-if="!report" :description="loading ? '正在读取制造准备信息' : '选择产品并重新检查，查看准备状态'" />
    <template v-else>
      <section class="preparation-summary">
        <strong>{{ report.productName }}</strong
        ><span>{{ report.asOf }}</span
        ><span>{{ openCount }} 项待完善</span
        ><el-tag :type="report.configurationReady ? 'success' : 'warning'">{{
          report.configurationReady ? '配置检查通过' : '配置待完善'
        }}</el-tag>
      </section>
      <el-table class="preparation-checks" :data="report.checks" border
        ><el-table-column prop="label" label="检查项" width="170" /><el-table-column label="状态" width="105"
          ><template #default="{ row }"
            ><el-tag :type="row.status === 'PASS' ? 'success' : 'warning'">{{
              row.status === 'PASS' ? '已满足' : '待完善'
            }}</el-tag></template
          ></el-table-column
        ><el-table-column prop="message" label="检查结果与处理方向" min-width="340"
      /></el-table>
      <aside v-if="report.executionBlockers.length" class="execution-boundary">
        <strong>待补齐事项</strong>
        <p v-for="reason in report.executionBlockers" :key="reason">{{ reason }}</p>
      </aside>
      <el-tabs v-model="tab">
        <el-tab-pane label="产品参数与依据" name="parameters">
          <el-table :data="report.parameters" border class="preparation-parameters"
            ><el-table-column prop="parameterName" label="参数" min-width="180" /><el-table-column
              label="用途"
              width="110"
              ><template #default="{ row }">{{
                row.requiredFlag === 1 ? '基线必需' : '候选参考'
              }}</template></el-table-column
            ><el-table-column prop="parameterValue" label="初版值 / 当前值" min-width="200" /><el-table-column
              prop="unitLabel"
              label="单位"
              width="75"
            /><el-table-column label="状态" width="125"
              ><template #default="{ row }">{{ label(row.status) }}</template></el-table-column
            ><el-table-column prop="sourceReference" label="依据与版本" min-width="240" /><el-table-column
              v-if="canEdit || canConfirm"
              label="操作"
              width="165"
              fixed="right"
              ><template #default="{ row }"
                ><el-button
                  v-if="canEdit && row.status === 'DRAFT'"
                  link
                  type="primary"
                  :disabled="saving"
                  @click="edit(row)"
                  >维护</el-button
                ><el-button
                  v-if="canConfirm"
                  link
                  :type="row.status === 'CONFIRMED' ? 'warning' : 'primary'"
                  :disabled="saving"
                  @click="review(row)"
                  >{{ row.status === 'CONFIRMED' ? '退回' : '确认依据' }}</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>
        <el-tab-pane label="工艺与资源占用" name="routes">
          <section v-for="route in report.routes" :key="route.routeId" class="route-section">
            <div class="detail-heading">
              <h2 :title="route.routeCode">{{ route.routeName }}</h2>
              <el-tag>{{ label(route.status) }}</el-tag
              ><el-button
                v-if="hasPermission('process:route:view')"
                link
                @click="router.push({ path: '/process/routes', query: { productId } })"
                >维护工艺</el-button
              >
            </div>
            <el-table :data="route.steps" border
              ><el-table-column prop="item.sequenceNo" label="顺序" width="65" /><el-table-column
                prop="operationName"
                label="工序"
                min-width="180" /><el-table-column
                prop="item.standardTime"
                label="人工 min/件"
                width="120" /><el-table-column label="资源方式" width="125"
                ><template #default="{ row }">{{
                  row.item.resourceType === 'BATCH_EQUIPMENT' ? '批量设备' : '人工节拍'
                }}</template></el-table-column
              ><el-table-column prop="item.batchCapacity" label="批容量" width="90" /><el-table-column
                prop="item.batchDurationMinutes"
                label="设备 min/批"
                width="125" /><el-table-column prop="item.timeSourceReference" label="时间依据" min-width="240"
            /></el-table>
          </section>
        </el-tab-pane>
        <el-tab-pane label="检验方案与标准" name="quality">
          <div class="detail-heading">
            <el-button
              v-if="hasPermission('qms:inspection-standard:view')"
              @click="router.push({ path: '/qms/standards', query: { productId } })"
              >维护检验标准</el-button
            >
          </div>
          <el-collapse
            ><el-collapse-item
              v-for="scheme in report.qualitySchemes"
              :key="scheme.schemeId"
              :name="scheme.schemeId"
              :title="`${typeLabel(scheme.inspectionType)} · ${scheme.schemeName} · ${label(scheme.status)}`"
              ><el-table :data="scheme.standards" border
                ><el-table-column prop="itemName" label="检验项目" min-width="180" /><el-table-column
                  prop="standardValue"
                  label="标准值"
                  width="110"
                /><el-table-column label="范围" width="150"
                  ><template #default="{ row }"
                    >{{ row.lowerLimit ?? '不限' }} ～ {{ row.upperLimit ?? '不限' }} {{ row.unitLabel }}</template
                  ></el-table-column
                ><el-table-column prop="judgementRule" label="判定方法" min-width="260" /><el-table-column
                  label="检验方式"
                  width="100"
                  ><template #default="{ row }">{{
                    row.samplingMode === 'ALL' ? '逐件全检' : `固定 ${row.sampleQuantity} 件`
                  }}</template></el-table-column
                ><el-table-column label="依据状态" width="120"
                  ><template #default="{ row }">{{ label(row.confirmationStatus) }}</template></el-table-column
                ><el-table-column v-if="canReviewQuality" label="操作" width="100" fixed="right"
                  ><template #default="{ row }"
                    ><el-button link type="primary" :disabled="saving" @click="review(row, true)">{{
                      row.confirmationStatus === 'CONFIRMED' ? '退回' : '确认依据'
                    }}</el-button></template
                  ></el-table-column
                ></el-table
              ></el-collapse-item
            ></el-collapse
          >
        </el-tab-pane>
      </el-tabs>
    </template>
    <el-dialog
      v-model="dialog"
      title="维护参数草稿"
      width="580px"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
    >
      <el-form label-position="top" @submit.prevent="save"
        ><el-form-item :label="`${editing?.parameterName || ''} ${editing?.unitLabel || ''}`"
          ><el-input v-model="form.parameterValue" maxlength="255" :disabled="saving" /></el-form-item
        ><el-form-item label="依据、版本及来源"
          ><el-input
            v-model="form.sourceReference"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :disabled="saving" /></el-form-item
      ></el-form>
      <el-alert v-if="editError" :title="editError" type="error" :closable="false" show-icon />
      <template #footer
        ><el-button :disabled="saving" @click="dialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template
      >
    </el-dialog>
  </main>
</template>

<style scoped>
.preparation-page {
  padding: 22px;
  color: var(--el-text-color-primary);
}
.preparation-heading,
.detail-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.preparation-heading {
  align-items: flex-start;
  margin-bottom: 22px;
}
.preparation-heading h1 {
  margin: 0;
  font-size: 26px;
  letter-spacing: -0.5px;
}
.preparation-controls {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 16px;
  padding: 18px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
}
.preparation-controls :deep(.el-form-item) {
  margin-bottom: 0;
}
.product-control {
  flex: 1;
  min-width: 250px;
}
.product-control :deep(.el-select) {
  width: 100%;
}
.preparation-controls :deep(.el-date-editor) {
  width: 165px;
}
.preparation-summary {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
  margin: 22px 0 15px;
  font-variant-numeric: tabular-nums;
}
.preparation-summary strong {
  font-size: 17px;
  margin-right: auto;
}
.preparation-error {
  margin: 16px 0;
}
.execution-boundary {
  margin: 18px 0;
  padding: 14px 18px;
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-fill-color-light);
}
.execution-boundary p,
.execution-boundary p {
  margin: 6px 0 0;
}
.route-section {
  margin: 18px 0 28px;
}
.detail-heading h2 {
  margin: 5px 0 12px;
  font-size: 16px;
}
.preparation-page :deep(.el-table .cell) {
  white-space: normal;
  overflow-wrap: anywhere;
  line-height: 1.6;
}
.preparation-page :deep(.el-collapse-item__header) {
  line-height: 1.6;
  height: auto;
  min-height: 48px;
  padding: 10px 0;
}
@media (max-width: 700px) {
  .preparation-page {
    padding: 12px;
  }
  .product-control {
    min-width: 100%;
  }
  .preparation-heading h1 {
    font-size: 23px;
  }
}
</style>
