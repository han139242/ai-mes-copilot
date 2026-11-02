<script setup>
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { executeResourceAction, getResourceDetail, getResourceList } from '@/api/resource';
import { piecesForBatchVerdict } from '@/utils/piece-inspection-batch';
import { parsePieceInspectionText } from '@/utils/piece-inspection-text';

const props = defineProps({ modelValue: Boolean, inspection: { type: Object, default: null } });
const emit = defineEmits(['update:modelValue', 'completed']);
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) });
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const items = ref([]);
const pieceCount = ref(1);
const sourceCount = ref(null);
const productionReport = ref(null);
const historicalDefectPieces = ref([]);
const knownDefectCount = computed(() => Number(productionReport.value?.defectQuantity || 0));
const lockedDefectPieces = computed(() => Array.from(
  { length: Math.min(knownDefectCount.value, count.value) },
  (_, index) => count.value - Math.min(knownDefectCount.value, count.value) + index + 1,
));
const isLockedDefect = (number) => lockedDefectPieces.value.includes(number);
function cannotChangeVerdict(number, row) {
  return saving.value || (isLockedDefect(number) && row.result === 'FAIL'
    && rowsFor(number).filter((entry) => entry.result === 'FAIL').length === 1);
}
const failedPieces = computed(() => new Set([
  ...lockedDefectPieces.value,
  ...Object.entries(results)
    .filter(([number, rows]) => Number(number) <= count.value && rows.some((row) => row.result === 'FAIL'))
    .map(([number]) => Number(number)),
]).size);
const currentPage = ref(1);
const pageSize = ref(10);
const pieceScroll = ref(null);
const selectedPieces = ref([]);
const remark = ref('');
const spokenRecords = ref('');
const results = reactive({});
let loadId = 0;
const count = computed(() => Math.max(1, Math.floor(Number(pieceCount.value) || 1)));
const pieceNo = (number) => `${props.inspection?.inspectionNo || 'PIECE'}-${String(number).padStart(4, '0')}`;
const visiblePieces = computed(() => {
  const offset = (currentPage.value - 1) * pageSize.value;
  const start = offset + 1;
  return Array.from(
    { length: Math.max(0, Math.min(pageSize.value, count.value - start + 1)) },
    (_, index) => start + index,
  );
});
const allSelected = computed(() => selectedPieces.value.length === count.value);
const partiallySelected = computed(() => selectedPieces.value.length > 0 && !allSelected.value);
const inspectionType = computed(
  () => props.inspection?.stockReleaseInspection ? '库存半成品领用检验（不生成生产任务）' :
    ({ FINAL: '终检（工单产出放行）', PROCESS: '过程检（生产中的工序检查）', INCOMING: '来料检', OUTGOING: '出货检' })[
      props.inspection?.inspectionType
    ] || '检验',
);

function rowsFor(number) {
  if (!results[number]) {
    results[number] = items.value.map((item) => ({
      itemId: item.itemId,
      measuredValue: '',
      result: '',
      defectDescription: '',
    }));
  }
  return results[number];
}
const completedPieces = computed(
  () =>
    Object.entries(results).filter(
      ([number, rows]) =>
        Number(number) <= count.value && rows.length === items.value.length && rows.every((row) => row.result),
    ).length,
);

async function expectedPieceCount(inspection) {
  if (inspection.reportId) {
    const report = (await getResourceDetail('/api/mfg/work-report', inspection.reportId))?.data;
    return { count: Number(report?.qualifiedQuantity || 0) + Number(report?.defectQuantity || 0), report };
  }
  if (inspection.productLotId) {
    const lot = (await getResourceDetail('/api/trace/product-lot', inspection.productLotId))?.data;
    return { count: Number(lot?.batchQuantity) || Number(inspection.sampleQuantity) || 0 };
  }
  return { count: Number(inspection.sampleQuantity) || 0 };
}

async function load() {
  const id = ++loadId;
  loading.value = true;
  error.value = '';
  items.value = [];
  Object.keys(results).forEach((key) => delete results[key]);
  currentPage.value = 1;
  selectedPieces.value = [];
  remark.value = '';
  spokenRecords.value = '';
  sourceCount.value = null;
  productionReport.value = null;
  historicalDefectPieces.value = [];
  try {
    const { inspection } = props;
    if (!inspection?.schemeId) {
      throw new Error('检验单未绑定方案，请先补齐检验方案');
    }
    const response = await getResourceList(`/api/qms/inspection-scheme/${inspection.schemeId}/items`);
    const configured = response?.data || [];
    if (!configured.length) {
      throw new Error('检验方案没有必检项目，请先维护方案');
    }
    const enriched = await Promise.all(
      configured.map(async (row) => {
        const [item, standard] = await Promise.all([
          getResourceDetail('/api/qms/inspection-item', row.itemId),
          row.standardId ? getResourceDetail('/api/qms/inspection-standard', row.standardId) : Promise.resolve(null),
        ]);
        return { ...row, itemName: item?.data?.itemName || `检验项 ${row.itemId}`, standard: standard?.data };
      }),
    );
    const [expected, savedResponse] = await Promise.all([
      expectedPieceCount(inspection),
      getResourceList(`/api/qms/inspection-order/${inspection.inspectionId}/piece-results`),
    ]);
    if (id !== loadId) {
      return;
    }
    items.value = enriched.filter((row, index, all) => all.findIndex((other) => other.itemId === row.itemId) === index);
    sourceCount.value = expected.count > 0 ? expected.count : null;
    pieceCount.value = expected.count > 0 ? expected.count : 1;
    productionReport.value = expected.report || null;
    restoreDraftRows(savedResponse?.data || []);
  } catch (failure) {
    if (id === loadId) {
      error.value = failure?.message || '无法加载检验项目，请检查权限与检验方案';
    }
  } finally {
    if (id === loadId) {
      loading.value = false;
    }
  }
}

function restoreDraftRows(savedRows) {
  const savedByPiece = new Map();
  for (const row of savedRows) {
    if (!savedByPiece.has(row.pieceNo)) {
      savedByPiece.set(row.pieceNo, new Map());
    }
    savedByPiece.get(row.pieceNo).set(row.itemId, row);
  }
  for (let number = 1; number <= count.value; number += 1) {
    const saved = savedByPiece.get(pieceNo(number));
    if (!saved) {
      continue;
    }
    rowsFor(number).forEach((row) => {
      const stored = saved.get(row.itemId);
      if (stored) {
        Object.assign(row, { result: stored.result || '', measuredValue: stored.measuredValue || '',
          defectDescription: stored.defectDescription || '', remark: stored.remark || '' });
      }
    });
    if (rowsFor(number).some((row) => row.remark?.startsWith('历史报工不良定位：'))) {
      historicalDefectPieces.value.push(number);
    }
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) {
      load();
    } else {
      loadId += 1;
    }
  },
  { immediate: true },
);
watch([currentPage, pageSize], () => pieceScroll.value?.scrollTo({ top: 0 }), { flush: 'post' });
watch(count, (value) => {
  currentPage.value = Math.min(currentPage.value, Math.ceil(value / pageSize.value));
  selectedPieces.value = selectedPieces.value.filter((number) => number <= value);
});

function selectAll(checked) {
  selectedPieces.value = checked ? Array.from({ length: count.value }, (_, index) => index + 1) : [];
}

function selectPiece(number, checked) {
  selectedPieces.value = checked
    ? [...selectedPieces.value, number]
    : selectedPieces.value.filter((value) => value !== number);
}

function markSelected(verdict) {
  const targets = piecesForBatchVerdict(selectedPieces.value, count.value, verdict);
  if (!targets.length) {
    ElMessage.warning('请先勾选需要判为不合格的产品');
    return;
  }
  let skipped = 0;
  for (const number of targets) {
    if (verdict === 'PASS' && (isLockedDefect(number) || historicalDefectPieces.value.includes(number))) {
      skipped += 1;
      continue;
    }
    results[number] = rowsFor(number).map((row) => ({ ...row, result: verdict }));
  }
  const changed = targets.length - skipped;
  if (!changed) {
    ElMessage.warning('这些件已定位为报工不良，不能直接改成合格');
    return;
  }
  ElMessage.success(
    skipped
      ? `已将 ${changed} 件设为合格，报工锁定不良保留，尚未提交`
      : `已将 ${changed} 件全部设为${verdict === 'PASS' ? '合格' : '不合格'}，可逐件修改，尚未提交`,
  );
}

function locateHistoricalDefects() {
  currentPage.value = Math.ceil(lockedDefectPieces.value[0] / pageSize.value);
  selectedPieces.value = [...lockedDefectPieces.value];
}

function applySpokenRecords() {
  try {
    const parsed = parsePieceInspectionText(spokenRecords.value, items.value, count.value);
    if (parsed.some((entry) => entry.result === 'PASS' && historicalDefectPieces.value.includes(entry.number))) {
      throw new Error('已定位的报工不良件不能直接改为合格，请先按质量处置流程处理');
    }
    for (const number of lockedDefectPieces.value) {
      const changes = parsed.filter((entry) => entry.number === number);
      if (!changes.length) {
        continue;
      }
      const hasDefect = rowsFor(number).some((row) =>
        (changes.find((entry) => entry.itemId === row.itemId)?.result || row.result) === 'FAIL',
      );
      if (!hasDefect) {
        throw new Error('报工锁定不良件必须保留实际不合格项目，请核对后重新填写');
      }
    }
    for (const entry of parsed) {
      const row = rowsFor(entry.number).find((item) => item.itemId === entry.itemId);
      Object.assign(row, { result: entry.result, measuredValue: entry.measuredValue });
    }
    ElMessage.success(`已填入 ${parsed.length} 条记录，请核对后提交`);
  } catch (failure) {
    ElMessage.warning(failure?.message || '检验记录无法识别，请核对后重试');
  }
}

async function submit() {
  if (lockedDefectPieces.value.some((number) => !rowsFor(number).some((row) => row.result === 'FAIL'))) {
    ElMessage.warning('请为已锁定的报工不良件填写实际不合格项目；不能将这些件改为合格');
    locateHistoricalDefects();
    return;
  }
  if (failedPieces.value < knownDefectCount.value) {
    ElMessage.warning(`报工已登记 ${knownDefectCount.value} 件不良，请保留至少这些不良件，不能提交全合格`);
    return;
  }
  if (completedPieces.value !== count.value || !items.value.length) {
    ElMessage.warning('请完成每件产品全部必检项目的判定（检查记录可不填）');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认提交 ${count.value} 件产品的逐件检验记录？提交后检验单将完成，数量和结论由系统汇总。`,
      '提交逐件检验',
      { type: 'warning' },
    );
  } catch {
    return;
  }
  saving.value = true;
  try {
    const all = [];
    for (let number = 1; number <= count.value; number += 1) {
      for (const row of results[number]) {
        all.push({
          ...row,
          pieceNo: `${props.inspection.inspectionNo}-${String(number).padStart(4, '0')}`,
        });
      }
    }
    await executeResourceAction({
      method: 'put',
      url: `/api/qms/inspection-order/${props.inspection.inspectionId}/submit-piece`,
      data: { results: all, remark: remark.value },
    });
    ElMessage.success('逐件检验已提交');
    visible.value = false;
    emit('completed');
  } catch (failure) {
    ElMessage.error(failure?.message || '提交失败，记录已保留，请核对后重试');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="逐件检验"
    width="min(1180px, 96vw)"
    top="3vh"
    :close-on-click-modal="false"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    append-to-body
  >
    <div v-loading="loading" class="piece-inspection">
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-else-if="items.length">
        <div class="piece-toolbar">
          <span>{{ inspection.inspectionNo }}</span>
          <el-tag effect="plain">{{ inspectionType }}</el-tag>
          <label
            >检验件数
            <el-input-number v-model="pieceCount" :min="1" :precision="0" :disabled="Boolean(sourceCount) || saving"
          /></label>
          <el-tag :type="completedPieces === count ? 'success' : 'info'"
            >已填完整 {{ completedPieces }} / {{ count }} 件</el-tag
          >
        </div>
        <div class="piece-text-entry">
          <label for="piece-spoken-records">用文字填写检验结果</label>
          <el-input id="piece-spoken-records" v-model="spokenRecords" type="textarea" :rows="2"
            placeholder="每行一条，例如：第1件，手动三档与响应，合格，实测 1.5秒" :disabled="saving" />
          <el-button :disabled="saving" @click="applySpokenRecords">填入记录</el-button>
        </div>
        <el-alert v-if="knownDefectCount > 0" type="warning" :closable="false" show-icon>
          <template #title>生产报工已登记不良 {{ knownDefectCount }} 件；含报工锁定不合格共 {{ failedPieces }} 件</template>
          <p>
            来源：{{ productionReport.reportNo }}。不良情况：{{
              productionReport.defectDescription || '历史报工未填写具体不良情况'
            }}
          </p>
          <p>报工不良固定为不合格，批量合格不能覆盖。具体不合格项目请按实际检查填写，经返工或补产处理后补足。</p>
          <p>
            第 {{ lockedDefectPieces.join('、') }} 件为报工不良的系统定位位置，不代表已记录实物序列号。
            <el-button link type="primary" @click="locateHistoricalDefects">定位这 {{ knownDefectCount }} 件不良</el-button>
          </p>
        </el-alert>
        <section class="piece-batch">
          <div class="piece-batch-toolbar">
            <el-checkbox
              :model-value="allSelected"
              :indeterminate="partiallySelected"
              :disabled="saving"
              @update:model-value="selectAll"
              @change="selectAll"
            >
              全选全部 {{ count }} 件
            </el-checkbox>
            <span>已选 {{ selectedPieces.length }} 件（跨页）</span>
            <el-button type="success" :disabled="saving" @click="markSelected('PASS')">{{
              selectedPieces.length ? '所选全部合格' : '全部合格'
            }}</el-button>
            <el-button type="danger" plain :disabled="saving || !selectedPieces.length" @click="markSelected('FAIL')"
              >所选全部不合格</el-button
            >
            <el-button :disabled="saving || !selectedPieces.length" @click="selectAll(false)">取消全选</el-button>
          </div>
          <p class="piece-help">
            没勾选时，点「全部合格」会把非报工锁定的全部件数判为合格；勾选后只改所选，报工不良仍保留。检查记录选填，保留已有检查记录及异常说明，不会自动提交。
          </p>
        </section>
        <div ref="pieceScroll" class="piece-scroll" tabindex="0" aria-label="逐件检验记录，可使用鼠标滚轮滚动">
          <section v-for="number in visiblePieces" :key="number" :data-piece-number="number" class="piece-card">
            <div class="piece-heading">
              <el-checkbox
                :model-value="selectedPieces.includes(number)"
                :disabled="saving"
                @change="selectPiece(number, $event)"
                ><strong>第 {{ number }} 件</strong></el-checkbox
              >
              <span>{{ pieceNo(number) }}</span>
              <el-tag v-if="isLockedDefect(number)" type="danger">报工不合格 · 已锁定</el-tag>
            </div>
            <el-table :data="rowsFor(number)" border class="piece-results">
              <el-table-column label="检验项目及依据" min-width="230">
                <template #default="{ $index }">
                  <strong>{{ items[$index].itemName }}</strong>
                  <p class="piece-standard">
                    {{ items[$index].standard?.judgementRule || items[$index].standard?.standardValue || '按方案检验' }}
                  </p>
                </template>
              </el-table-column>
              <el-table-column label="判定" width="150">
                <template #default="{ row }"
                  ><el-select v-model="row.result" :disabled="cannotChangeVerdict(number, row)" placeholder="是否符合标准"
                    ><el-option label="合格" value="PASS" /><el-option label="不合格" value="FAIL" /><el-option
                      label="让步接收"
                      value="CONCESSION" /></el-select
                ></template>
              </el-table-column>
              <el-table-column label="实测值 / 人工检查记录（选填）" min-width="230">
                <template #default="{ row }"
                  ><el-input
                    v-model="row.measuredValue"
                    maxlength="64"
                    :disabled="saving"
                    placeholder="选填：实测数值或检查情况"
                /></template>
              </el-table-column>
              <el-table-column label="异常说明" min-width="180">
                <template #default="{ row }"
                  ><el-input
                    v-model="row.defectDescription"
                    :disabled="saving"
                    maxlength="255"
                    placeholder="有异常时填写"
                /></template>
              </el-table-column>
            </el-table>
          </section>
        </div>
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[5, 10, 20]"
          :total="count"
          layout="total, sizes, prev, pager, next, jumper"
          :disabled="saving"
          @size-change="currentPage = 1"
        />
        <el-input
          v-model="remark"
          type="textarea"
          :rows="2"
          maxlength="500"
          :disabled="saving"
          placeholder="本次检验备注（选填）"
        />
      </template>
    </div>
    <template #footer>
      <el-button :disabled="saving" @click="visible = false">关闭</el-button>
      <el-button v-if="error" @click="load">重新加载</el-button>
      <el-button type="primary" :disabled="loading || Boolean(error) || !items.length" :loading="saving" @click="submit"
        >提交逐件结果</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.piece-inspection {
  min-height: 160px;
  max-height: 75vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  font-size: 14px;
  padding-right: 4px;
}
.piece-help {
  color: #60718a;
  font-size: 12px;
  line-height: 1.6;
  margin: 8px 0;
}
.piece-batch {
  flex-shrink: 0;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  padding: 12px;
  margin-bottom: 12px;
}
.piece-batch-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.piece-scroll {
  flex: 1 1 auto;
  height: min(46vh, 520px);
  min-height: 220px;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 0 8px 0 0;
}
.piece-card {
  padding: 2px 0 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.piece-standard {
  color: #60718a;
  line-height: 1.6;
}
.piece-toolbar {
  flex-shrink: 0;
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--el-bg-color);
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin: 0 0 12px;
  padding: 8px 0;
}

.piece-text-entry {
  display: grid;
  gap: 8px;
  margin: 12px 0;
}
.piece-toolbar label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.piece-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 12px;
  margin: 12px 0;
}
.piece-heading strong {
  font-size: 17px;
}
.piece-heading span {
  color: #60718a;
}
.piece-standard {
  margin: 4px 0 0;
  font-size: 12px;
}
.piece-results :deep(.cell) {
  line-height: 1.6;
}
.piece-inspection .el-pagination {
  flex-shrink: 0;
  margin: 18px 0;
  max-width: 100%;
  overflow: auto;
}
</style>
