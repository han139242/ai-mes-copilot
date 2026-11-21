<script setup>
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue';
import { useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import { getResourceList } from '@/api/resource';
import { getResourceConfig } from '@/config/resources';
import { isNavigationPathAccessible } from '@/config/navigation';
import { authRevision, getToken } from '@/utils/auth';
import { hasRequestPermission } from '@/utils/permission';
import { loadReferenceRecords } from '@/utils/resource-options';
import { isResourceFocusId } from '@/utils/resource-page-state';

const open = defineModel({ type: Boolean, default: false });
const props = defineProps({ selection: { type: Object, default: null } });
const router = useRouter();
const state = ref('loading');
const data = ref(null);
const names = ref({});
const materialUnits = ref({});
const error = ref('');
const title = ref();
const drilldown = shallowRef(null);
let controller;
let sourceView;
let closing = false;
let referencesReady = false;

const activeSelection = computed(() => drilldown.value ?? props.selection);
const isInputs = computed(() => activeSelection.value?.direction === 'inputs');
const directionLabel = computed(() => isInputs.value ? '按生产批查用料' : '按物料批查去向');
const lines = computed(() => isInputs.value ? data.value?.materials || [] : data.value || []);
const matchedUsage = computed(() => drilldown.value && lines.value.some(isSourceUsage));
const statusOptions = computed(() => getResourceConfig('trace-product-lot').fields.find((field) => field.prop === 'status')?.options || []);
const statusLabel = computed(() => {
  const status = data.value?.status;
  return statusOptions.value.find((item) => item.value === status)?.label ?? status;
});
const verdict = computed(() => {
  const inspection = data.value?.inspection;
  if (!inspection) return '暂无终检记录';
  const fields = getResourceConfig('qms-inspection-order').fields;
  const finalized = inspection.status === 'FINISHED' || !inspection.status;
  const options = fields.find((field) => field.prop === (finalized ? 'inspectionResult' : 'status'))?.options || [];
  const value = finalized ? inspection.inspectionResult : inspection.status;
  return options.find((item) => item.value === value)?.label ?? '未判定';
});
const affectedLots = computed(() => new Set(lines.value.map((line) => line.productLotId).filter(isResourceFocusId).map(String)).size);
const formatNumber = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 });

function numberText(quantity) {
  return quantity != null && Number.isFinite(Number(quantity)) ? formatNumber.format(Number(quantity)) : '—';
}

function quantityText(row) {
  const value = numberText(row.usedQuantity);
  const unit = names.value['mdm-unit']?.[materialUnits.value[row.materialId]];
  return value === '—' || !unit ? value : `${value} ${unit}`;
}

function nameFor(key, id, fallback) {
  return names.value[key]?.[String(id)] || (isResourceFocusId(id) ? `${fallback} #${id}` : '—');
}

function canNavigate(key, id) {
  return isResourceFocusId(id) && isNavigationPathAccessible(getResourceConfig(key).routePath);
}

async function goTo(key, id, query = { focus: id }) {
  if (!canNavigate(key, id)) return;
  const failure = await router.push({ path: getResourceConfig(key).routePath, query });
  if (!failure) open.value = false;
}

function productTraceUrl(productLotId) {
  return `/api/trace/product-lot/${productLotId}/trace`;
}

function isExactTraceId(id) {
  return isResourceFocusId(id) && (typeof id === 'string' || Number.isSafeInteger(id));
}

function canVerifyOutput(row) {
  return [row.productLotId, row.usageId, row.batchId].every(isExactTraceId)
    && hasRequestPermission('get', productTraceUrl(row.productLotId));
}

function isSourceUsage(row) {
  const target = drilldown.value;
  return Boolean(target && isExactTraceId(row.usageId) && isExactTraceId(row.batchId)
    && String(row.usageId) === String(target.usageId) && String(row.batchId) === String(target.batchId));
}

function traceRowClass({ row }) {
  return isSourceUsage(row) ? 'trace-matched-row' : '';
}

function verifyOutput(row) {
  if (closing || state.value !== 'ready' || drilldown.value || !canVerifyOutput(row)) return;
  sourceView = {
    data: data.value,
    names: names.value,
    materialUnits: materialUnits.value,
    referencesReady,
    scrollTop: title.value?.closest('.el-drawer__body')?.scrollTop ?? 0,
  };
  drilldown.value = {
    direction: 'inputs',
    url: productTraceUrl(row.productLotId),
    label: nameFor('trace-product-lot', row.productLotId, '生产批'),
    productLotId: row.productLotId,
    usageId: row.usageId,
    batchId: row.batchId,
  };
  void load();
  scrollToTop();
}

async function returnToOutputs() {
  if (closing || !sourceView || !drilldown.value) return;
  const { usageId } = drilldown.value;
  const source = sourceView;
  controller?.abort();
  sourceView = null;
  drilldown.value = null;
  data.value = source.data;
  names.value = source.names;
  materialUnits.value = source.materialUnits;
  state.value = 'ready';
  error.value = '';
  referencesReady = source.referencesReady;
  if (!referencesReady) {
    const { current } = startRequest(activeSelection.value);
    void loadReferences(source.data, current);
  }
  await nextTick();
  if (closing || !open.value || drilldown.value || data.value !== source.data) return;
  const body = title.value?.closest('.el-drawer__body');
  body?.scrollTo(0, source.scrollTop);
  const buttons = Array.from(body?.querySelectorAll('[data-trace-check]') ?? []);
  const trigger = buttons.find((button) => button.dataset.traceCheck === String(usageId) && button.getClientRects().length);
  (trigger ?? title.value)?.focus({ preventScroll: true });
}

function scrollToTop() {
  nextTick(() => {
    if (closing || !open.value) return;
    title.value?.closest('.el-drawer__body')?.scrollTo(0, 0);
    title.value?.focus({ preventScroll: true });
  });
}

async function hydrate(payload, stillCurrent) {
  const rows = Array.isArray(payload) ? payload : payload.materials;
  const ids = {
    'mdm-material': rows.map((row) => row.materialId),
    'wms-material-batch': rows.map((row) => row.batchId),
    'trace-product-lot': Array.isArray(payload) ? rows.map((row) => row.productLotId) : [],
    'mfg-work-order': Array.isArray(payload) ? rows.map((row) => row.workOrderId) : [payload.workOrderId],
    'mfg-work-report': rows.map((row) => row.reportId),
    'qms-inspection-order': [payload.inspection?.inspectionId],
  };
  const toLoad = Object.entries(ids).filter(([key, values]) =>
    values.some(isResourceFocusId) && isNavigationPathAccessible(getResourceConfig(key).routePath),
  );
  await Promise.allSettled(toLoad.map(async ([key, values]) => {
    const options = await loadReferenceRecords(key, values, false, stillCurrent, { loadOptions: false });
    if (!stillCurrent()) return;
    const requested = new Set(values.map(String));
    const matching = options.filter((item) => requested.has(String(item.value)));
    names.value[key] = Object.fromEntries(
      matching.map((item) => [String(item.value), item.searchLabel || item.label]),
    );
    if (key === 'mdm-material' && isNavigationPathAccessible(getResourceConfig('mdm-unit').routePath)) {
      materialUnits.value = Object.fromEntries(matching.map((item) => [item.value, item.row?.unitId]));
      const units = await loadReferenceRecords('mdm-unit', Object.values(materialUnits.value), false, stillCurrent, {
        loadOptions: false,
      });
      if (stillCurrent()) names.value['mdm-unit'] = Object.fromEntries(units.map((item) => [item.value, item.row?.unitName]));
    }
  }));
}

function startRequest(selection) {
  controller?.abort();
  const request = new AbortController();
  controller = request;
  const rootSelection = props.selection;
  const token = getToken();
  const revision = authRevision.value;
  const current = () => open.value && !closing && !request.signal.aborted && controller === request
    && getToken() === token && authRevision.value === revision
    && props.selection === rootSelection && activeSelection.value === selection;
  return { request, token, current };
}

async function loadReferences(payload, current) {
  await hydrate(payload, current);
  if (current()) referencesReady = true;
}

async function load() {
  const selection = activeSelection.value;
  referencesReady = false;
  data.value = null;
  names.value = {};
  materialUnits.value = {};
  error.value = '';
  if (!open.value || closing || !selection) {
    controller?.abort();
    return;
  }
  state.value = 'loading';
  const { request, token, current } = startRequest(selection);
  try {
    if (!hasRequestPermission('get', selection.url)) throw new Error('当前账号没有此方向的追溯权限。');
    const response = await getResourceList(selection.url, undefined, {
      signal: request.signal,
      expectedAuthToken: token,
      suppressErrorMessage: true,
    });
    if (!current()) return;
    const payload = response?.data;
    if (selection.direction === 'inputs' && !payload) {
      state.value = 'missing';
      return;
    }
    if (selection.direction === 'inputs' ? !Array.isArray(payload.materials) : !Array.isArray(payload)) {
      throw new Error('追溯接口返回了不完整的数据。');
    }
    if (selection.productLotId != null && (!isExactTraceId(payload.productLotId)
      || String(payload.productLotId) !== String(selection.productLotId))) {
      throw new Error('返回的生产批与选中记录不一致，请重新读取。');
    }
    data.value = payload;
    state.value = 'ready';
    void loadReferences(payload, current);
  } catch (failure) {
    if (!current()) return;
    error.value = failure?.displayMessage || failure?.message || '暂时无法读取批次追溯。';
    state.value = 'error';
  }
}

function closeRequest() {
  closing = true;
  controller?.abort();
}

watch([open, () => props.selection, authRevision], ([isOpen], [wasOpen]) => {
  if (isOpen && !wasOpen) closing = false;
  drilldown.value = null;
  sourceView = null;
  void load();
});
onBeforeUnmount(() => controller?.abort());
</script>

<template>
  <el-drawer
    v-model="open"
    class="lot-trace-drawer"
    :title="directionLabel"
    size="min(760px, 100vw)"
    append-to-body
    @close="closeRequest"
    @opened="scrollToTop"
  >
    <div v-if="activeSelection" class="lot-trace" :aria-busy="state === 'loading'">
      <div class="trace-heading">
        <span class="trace-heading-icon"><AppIcon name="route" :size="20" /></span>
        <div>
          <h2 ref="title" tabindex="-1">{{ data?.lotNo || activeSelection.label }}</h2>
          <p>{{ isInputs ? '生产批次 · 直接投入记录' : '物料批次 · 直接使用记录' }}</p>
        </div>
      </div>
      <div v-if="drilldown" class="trace-origin">
        <el-button link type="primary" @click="returnToOutputs">返回物料去向</el-button>
        <span>{{ selection.label }} · 记录 #{{ drilldown.usageId }}</span>
      </div>
      <el-skeleton v-if="state === 'loading'" :rows="5" animated aria-label="正在读取追溯记录" />
      <div v-else-if="state === 'error'" class="trace-feedback" role="alert">
        <p>{{ error }}</p>
        <el-button @click="load">重新读取</el-button>
      </div>
      <p v-else-if="state === 'missing'" class="trace-feedback" role="status">未找到对应的生产批次。</p>
      <template v-else-if="state === 'ready'">
        <dl v-if="isInputs" class="trace-stats">
          <div><dt>批次数量</dt><dd>{{ numberText(data.batchQuantity) }}</dd></div>
          <div><dt>合格数量</dt><dd>{{ numberText(data.qualifiedQuantity) }}</dd></div>
          <div><dt>不良数量</dt><dd>{{ numberText(data.defectQuantity) }}</dd></div>
          <div><dt>批次状态</dt><dd>{{ statusLabel || '—' }}</dd></div>
        </dl>
        <dl v-else class="trace-stats trace-stats-outputs">
          <div><dt>关联生产批次</dt><dd>{{ affectedLots }}</dd></div>
          <div><dt>物料使用记录</dt><dd>{{ lines.length }}</dd></div>
        </dl>
        <div v-if="isInputs" class="trace-metadata">
          <div>
            <span>生产工单</span>
            <el-button v-if="canNavigate('mfg-work-order', data.workOrderId)" link type="primary" @click="goTo('mfg-work-order', data.workOrderId)">
              {{ nameFor('mfg-work-order', data.workOrderId, '工单') }}
            </el-button>
            <strong v-else>{{ nameFor('mfg-work-order', data.workOrderId, '工单') }}</strong>
          </div>
          <div>
            <span>本批终检</span>
            <strong>{{ verdict }}</strong>
            <el-button
              v-if="canNavigate('qms-inspection-order', data.inspection?.inspectionId)"
              link
              type="primary"
              @click="goTo('qms-inspection-order', data.inspection.inspectionId, { focus: data.inspection.inspectionId, productLotId: data.productLotId, inspectionType: 'FINAL' })"
            >{{ nameFor('qms-inspection-order', data.inspection.inspectionId, '检验单') }}</el-button>
          </div>
        </div>
        <section class="trace-records">
          <h3>{{ isInputs ? '投入物料' : '使用该批次的生产记录' }}</h3>
          <p v-if="drilldown && !matchedUsage" class="trace-feedback" role="status">当前生产批中未找到原用料记录。请返回去向重新核对。</p>
          <el-table v-if="lines.length" :data="lines" :row-class-name="traceRowClass" size="small" class="trace-table">
            <el-table-column v-if="isInputs" label="物料" min-width="150">
              <template #default="{ row }">{{ nameFor('mdm-material', row.materialId, '物料') }}</template>
            </el-table-column>
            <el-table-column v-if="isInputs" label="物料批次" min-width="175">
              <template #default="{ row }">
                <el-button v-if="canNavigate('wms-material-batch', row.batchId)" link type="primary" @click="goTo('wms-material-batch', row.batchId)">
                  {{ nameFor('wms-material-batch', row.batchId, '批次') }}
                </el-button>
                <span v-else>{{ nameFor('wms-material-batch', row.batchId, '批次') }}</span>
              </template>
            </el-table-column>
            <el-table-column v-if="!isInputs" label="生产批次" min-width="160">
              <template #default="{ row }">
                <el-button v-if="canNavigate('trace-product-lot', row.productLotId)" link type="primary" @click="goTo('trace-product-lot', row.productLotId)">
                  {{ nameFor('trace-product-lot', row.productLotId, '生产批次') }}
                </el-button>
                <span v-else>{{ nameFor('trace-product-lot', row.productLotId, '生产批次') }}</span>
                <el-button v-if="canVerifyOutput(row)" class="trace-check" link type="primary"
                  :data-trace-check="row.usageId" @click="verifyOutput(row)">
                  核对用料与终检
                </el-button>
              </template>
            </el-table-column>
            <el-table-column v-if="!isInputs" label="生产工单" min-width="148">
              <template #default="{ row }">
                <el-button v-if="canNavigate('mfg-work-order', row.workOrderId)" link type="primary" @click="goTo('mfg-work-order', row.workOrderId)">
                  {{ nameFor('mfg-work-order', row.workOrderId, '工单') }}
                </el-button>
                <span v-else>{{ nameFor('mfg-work-order', row.workOrderId, '工单') }}</span>
              </template>
            </el-table-column>
            <el-table-column label="实际用量" min-width="120" align="right">
              <template #default="{ row }">
                <div>{{ quantityText(row) }}</div>
                <span v-if="isResourceFocusId(row.usageId)" class="trace-usage-key">
                  <strong v-if="isSourceUsage(row)">原用料 · </strong>记录 #{{ row.usageId }}
                </span>
              </template>
            </el-table-column>
            <el-table-column v-if="!isInputs" label="报工单" min-width="148">
              <template #default="{ row }">
                <el-button v-if="canNavigate('mfg-work-report', row.reportId)" link type="primary" @click="goTo('mfg-work-report', row.reportId)">
                  {{ nameFor('mfg-work-report', row.reportId, '报工单') }}
                </el-button>
                <span v-else>{{ nameFor('mfg-work-report', row.reportId, '报工单') }}</span>
              </template>
            </el-table-column>
          </el-table>
          <ul v-if="lines.length" class="trace-record-list">
            <li v-for="(row, index) in lines" :key="row.usageId || index" :class="{ 'trace-matched-row': isSourceUsage(row) }">
              <div class="trace-row-heading">
                <strong>{{ isInputs ? nameFor('mdm-material', row.materialId, '物料') : nameFor('trace-product-lot', row.productLotId, '生产批次') }}</strong>
                <span>{{ quantityText(row) }}</span>
              </div>
              <el-button v-if="isInputs && canNavigate('wms-material-batch', row.batchId)" link type="primary" @click="goTo('wms-material-batch', row.batchId)">
                {{ nameFor('wms-material-batch', row.batchId, '批次') }}
              </el-button>
              <span v-else-if="isInputs">{{ nameFor('wms-material-batch', row.batchId, '批次') }}</span>
              <template v-else>
                <div class="trace-mobile-links">
                  <span>工单</span>
                  <el-button v-if="canNavigate('mfg-work-order', row.workOrderId)" link type="primary" @click="goTo('mfg-work-order', row.workOrderId)">
                    {{ nameFor('mfg-work-order', row.workOrderId, '工单') }}
                  </el-button>
                  <span v-else>{{ nameFor('mfg-work-order', row.workOrderId, '工单') }}</span>
                </div>
                <div class="trace-mobile-links">
                  <span>报工</span>
                  <el-button v-if="canNavigate('mfg-work-report', row.reportId)" link type="primary" @click="goTo('mfg-work-report', row.reportId)">
                    {{ nameFor('mfg-work-report', row.reportId, '报工') }}
                  </el-button>
                  <span v-else>{{ nameFor('mfg-work-report', row.reportId, '报工') }}</span>
                </div>
                <el-button v-if="canNavigate('trace-product-lot', row.productLotId)" link type="primary" @click="goTo('trace-product-lot', row.productLotId)">
                  查看生产批次
                </el-button>
                <el-button v-if="canVerifyOutput(row)" class="trace-check" link type="primary"
                  :data-trace-check="row.usageId" @click="verifyOutput(row)">
                  核对用料与终检
                </el-button>
              </template>
              <span v-if="isResourceFocusId(row.usageId)" class="trace-usage-key">
                <strong v-if="isSourceUsage(row)">原用料 · </strong>记录 #{{ row.usageId }}
              </span>
            </li>
          </ul>
          <p v-else class="trace-feedback" role="status">当前批次暂无{{ isInputs ? '投入物料' : '直接使用' }}记录。</p>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.lot-trace {
  display: grid;
  min-width: 0;
  gap: 16px;
  color: var(--mes-text);
}
.trace-origin {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 14px;
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
}
.trace-origin .el-button,
.trace-check.el-button {
  min-height: 28px;
  margin: 0;
}
.trace-check.el-button {
  display: flex;
}
.trace-heading {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--mes-border);
}
.trace-heading-icon {
  display: inline-flex;
  width: 38px;
  height: 38px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border);
  border-radius: var(--mes-radius-sm);
  background: var(--mes-primary-soft);
}
.trace-heading h2 {
  margin: 0;
  font-size: 20px;
  overflow-wrap: anywhere;
}
.trace-heading p {
  margin: 3px 0 0;
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
}
.trace-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius-sm);
}
.trace-stats-outputs {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.trace-stats > div {
  min-width: 0;
  padding: 11px 12px;
}
.trace-stats > div + div {
  border-left: 1px solid var(--mes-border);
}
.trace-stats dt,
.trace-metadata span {
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
}
.trace-stats dd {
  margin: 3px 0 0;
  font-size: 17px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}
.trace-metadata {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--mes-radius-sm);
  background: var(--mes-surface-muted);
}
.trace-metadata > div,
.trace-feedback {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
}
.trace-metadata strong {
  font-weight: 600;
}
.trace-records {
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--mes-border);
  border-radius: var(--mes-radius-sm);
  background: var(--mes-surface);
}
.trace-records h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.trace-feedback {
  margin: 0;
  padding: 9px 0;
  color: var(--mes-text-secondary);
}
.trace-feedback p {
  margin: 0;
}
.trace-table {
  width: 100%;
}
.trace-table :deep(.trace-matched-row > td),
.trace-record-list .trace-matched-row {
  background: var(--mes-primary-soft);
}
.trace-usage-key strong {
  color: var(--mes-primary);
}
.trace-usage-key {
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
  overflow-wrap: anywhere;
}
.trace-record-list {
  display: none;
}
@media (max-width: 550px) {
  .trace-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .trace-stats > div:nth-child(3) {
    border-left: 0;
  }
  .trace-stats > div:nth-child(n + 3) {
    border-top: 1px solid var(--mes-border);
  }
  .trace-table {
    display: none;
  }
  .trace-record-list {
    display: grid;
    gap: 0;
    margin: 0;
    padding: 0;
    color: var(--mes-text-secondary);
    font-size: var(--mes-font-label);
    list-style: none;
  }
  .trace-record-list li {
    display: grid;
    min-width: 0;
    gap: 6px;
    padding: 10px 0;
  }
  .trace-record-list li + li {
    border-top: 1px solid var(--mes-border);
  }
  .trace-row-heading {
    display: flex;
    justify-content: space-between;
    gap: 6px;
    color: var(--mes-text);
  }
  .trace-row-heading strong {
    overflow-wrap: anywhere;
  }
  .trace-row-heading span {
    flex: none;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
  .trace-record-list :deep(.el-button) {
    min-height: 32px;
    width: max-content;
    max-width: 100%;
    margin: 0;
  }
  .trace-mobile-links {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 5px;
  }
}
</style>
