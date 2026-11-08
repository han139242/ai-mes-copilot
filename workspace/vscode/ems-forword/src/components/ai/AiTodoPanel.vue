<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import AiCommandCard from '@/components/ai/AiCommandCard.vue';
import { previewAgentCommand } from '@/api/ai/agent';
import {
  refreshTodos,
  todoCounts,
  todoError,
  todoGeneratedAt,
  todoItemsSorted,
  todoLoading,
  todoLoaded,
  todoTotal,
} from '@/stores/aiTodo';
import { DATA_CHANGED_EVENT, notifyDataChanged } from '@/utils/app-events';
import { isFrameworkPreview } from '@/utils/auth';

/*
 * 我的待办面板：把九类业务待办汇总成一张卡片，每条右侧直接给可办理的动作按钮。
 * 动作走的是与工作台相同的预览接口，先看前置条件再确认，确认后才会真正改动业务单据。
 */
const router = useRouter();
const previewMode = isFrameworkPreview();

// 九类待办的中文名，键与后端 AiTodoServiceImpl 的 type 常量一一对应。
const TYPE_LABELS = {
  PENDING_MATERIAL_ISSUE: '待确认领料单',
  PENDING_INBOUND: '待确认入库单',
  PENDING_WORK_REPORT: '待审核报工',
  PENDING_INSPECTION: '待执行检验',
  MATERIAL_SHORTAGE: '缺料',
  WORK_ORDER_DELAY: '工单延期',
  EQUIPMENT_FAULT: '设备故障',
  AUTOMATION_FAILED: '自动化失败',
  EXCEPTION_PENDING: '异常待处理',
};

// 严重程度：高红、中橙、低灰，和系统其他风险标记同一套语义色。
const SEVERITY_META = {
  HIGH: { label: '紧急', className: 'is-high' },
  MEDIUM: { label: '关注', className: 'is-medium' },
  LOW: { label: '提示', className: 'is-low' },
};

// 顶部汇总只显示有数据的类别，全是 0 时不占位置。
const countChips = computed(() =>
  Object.entries(TYPE_LABELS)
    .map(([type, label]) => ({ type, label, count: Number(todoCounts.value?.[type] || 0) }))
    .filter((item) => item.count > 0),
);

const preview = ref(null);
const previewVisible = ref(false);
const previewingKey = ref('');
const expanded = ref(false);
const currentPage = ref(1);
const pageSize = 10;
const pageCount = computed(() => Math.max(1, Math.ceil(todoItemsSorted.value.length / pageSize)));
const visibleTodos = computed(() =>
  expanded.value
    ? todoItemsSorted.value.slice((currentPage.value - 1) * pageSize, currentPage.value * pageSize)
    : todoItemsSorted.value.slice(0, 6),
);
watch(pageCount, (count) => {
  currentPage.value = Math.min(currentPage.value, count);
});

function severityMeta(severity) {
  return SEVERITY_META[String(severity || '').toUpperCase()] || SEVERITY_META.LOW;
}

// 等待时长按量级换单位：一分钟内说分钟，一天内说小时，更久说天。
function waitingText(minutes) {
  const value = Number(minutes || 0);
  if (!value || value <= 0) {
    return '';
  }
  if (value < 60) {
    return `已等待 ${value} 分钟`;
  }
  if (value < 1440) {
    const hours = value / 60;
    return `已等待 ${hours >= 10 ? Math.round(hours) : hours.toFixed(1)} 小时`;
  }
  return `已等待 ${Math.floor(value / 1440)} 天`;
}

// 后端待办动作有两种：kind='view' 的只跳页面（设备故障、异常闭环、自动化失败这类没有写动作），
// 其余按办理类走去生成预览卡片。
function actionKind(action) {
  return action?.kind === 'view' ? 'view' : 'command';
}

function actionKey(item, action, index) {
  return `${item?.bizNo || item?.title || ''}-${action?.code || index}`;
}

// 预览接口只收自然语言，所以把「动作名 + 单号」拼成一句话，例如「确认出库 MI-2026…」。
function previewText(item, action) {
  const label = String(action?.label || '').trim();
  const bizNo = String(item?.bizNo || '').trim();
  return bizNo ? `${label} ${bizNo}` : label;
}

async function handleAction(item, action, index) {
  if (actionKind(action) === 'view') {
    if (action.route) {
      // 跳转类动作可以带定位参数，例如 focus 指向具体单据，目标页会选中那一行。
      const query = Object.fromEntries(
        Object.entries(action.query || {}).filter(([, value]) => value !== null && value !== undefined && value !== ''),
      );
      router.push(Object.keys(query).length ? { path: action.route, query } : action.route);
    }
    return;
  }
  if (previewingKey.value) {
    return;
  }
  previewingKey.value = actionKey(item, action, index);
  try {
    const response = await previewAgentCommand({
      text: previewText(item, action),
      source: 'TODO',
      context: { capability: action?.capability || action?.code || '' },
    });
    const data = response?.data || {};
    if (!data.confirmationId) {
      ElMessage.warning(data.message || '没能生成办理预览，请到对应页面手工办理。');
      return;
    }
    preview.value = data;
    previewVisible.value = true;
  } catch (error) {
    ElMessage.error(error?.displayMessage || error?.data?.message || '办理预览暂不可用，请稍后重试。');
  } finally {
    previewingKey.value = '';
  }
}

// 卡片确认执行后回调：成功后刷新待办并广播数据变更，让业务页重新拉一次。
function handleResolved(payload) {
  const status = String(payload?.status || '').toUpperCase();
  if (status === 'SUCCEEDED' || status === 'PARTIAL_FAILED') {
    ElMessage.success(payload?.summary || '已办理完成');
    previewVisible.value = false;
    refreshTodos(true);
    notifyDataChanged({ action: preview.value?.action, bizNo: preview.value?.targetNo });
    return;
  }
  // 取消或失败时保留卡片本身的结果展示，只把弹窗关掉一半：失败让人能看清原因。
  if (status === 'CANCELLED' || status === 'REJECTED') {
    previewVisible.value = false;
  }
}

function openList() {
  expanded.value = !expanded.value;
  currentPage.value = 1;
}

// 工作台办理成功后会广播 mes:data-changed，这里跟着刷新，两个页面数字不会打架。
function onDataChanged() {
  refreshTodos(true);
}

onMounted(() => {
  refreshTodos();
  window.addEventListener(DATA_CHANGED_EVENT, onDataChanged);
});

onBeforeUnmount(() => {
  window.removeEventListener(DATA_CHANGED_EVENT, onDataChanged);
});
</script>

<template>
  <article class="mes-panel todo-panel">
    <div class="mes-panel-header">
      <div>
        <h2 class="mes-panel-title">我的待办</h2>
        <span v-if="previewMode" class="panel-caption">连接后端后显示当前账号的待办</span>
        <span v-else-if="!todoLoaded" class="panel-caption">{{ todoError ? '待办暂不可用' : '正在加载待办' }}</span>
        <span v-else class="panel-caption">
          共 {{ todoTotal }} 条<template v-if="todoTotal > todoItemsSorted.length">
            · 已加载 {{ todoItemsSorted.length }} 条</template
          ><template v-if="todoGeneratedAt"> · 更新于 {{ todoGeneratedAt.slice(11, 16) }}</template>
        </span>
      </div>
      <div v-if="!previewMode" class="todo-head-actions">
        <el-tooltip content="刷新待办" placement="top">
          <button class="todo-icon" type="button" aria-label="刷新待办" :disabled="todoLoading" @click="refreshTodos()">
            <AppIcon name="refresh" :size="14" />
          </button>
        </el-tooltip>
        <el-button v-if="todoItemsSorted.length > 6" link type="primary" :aria-expanded="expanded" @click="openList">{{
          expanded ? '收起待办' : '查看更多'
        }}</el-button>
      </div>
    </div>

    <div v-if="!previewMode && countChips.length" class="todo-counts">
      <span v-for="chip in countChips" :key="chip.type" class="todo-chip">
        {{ chip.label }}
        <strong>{{ chip.count }}</strong>
      </span>
    </div>

    <div class="todo-body" :aria-busy="todoLoading && !previewMode">
      <div v-if="previewMode" class="todo-empty" role="status">
        <AppIcon name="clipboard" :size="22" />
        <div>
          <strong>待办将在连接后端后显示</strong>
          <p>当前为界面预览，未读取业务待办。</p>
        </div>
      </div>
      <el-skeleton v-else-if="todoLoading && !todoLoaded" :rows="2" animated />
      <template v-else>
        <div v-if="todoError" class="todo-error" role="alert">
          <p>{{ todoError }}<span v-if="todoLoaded"> · 下方保留上次同步结果</span></p>
          <el-button text type="primary" :loading="todoLoading" @click="refreshTodos()">重新加载</el-button>
        </div>
        <p v-if="todoLoaded && !todoError && !todoItemsSorted.length" class="todo-tip" role="status">
          当前没有需要处理的待办。
        </p>

        <ul v-if="todoLoaded && todoItemsSorted.length" class="todo-list">
          <li v-for="item in visibleTodos" :key="`${item.type}-${item.bizNo}-${item.bizId}`" class="todo-row">
            <div class="todo-main">
              <div class="todo-line">
                <span class="todo-severity" :class="severityMeta(item.severity).className">
                  {{ severityMeta(item.severity).label }}
                </span>
                <span class="todo-type">{{ item.typeLabel || TYPE_LABELS[item.type] || item.type }}</span>
                <strong class="todo-title">{{ item.title }}</strong>
              </div>
              <div class="todo-meta">
                <span v-if="item.bizNo" class="todo-no">{{ item.bizNo }}</span>
                <span v-if="waitingText(item.waitingMinutes)" class="todo-wait">
                  {{ waitingText(item.waitingMinutes) }}
                </span>
                <span v-if="item.summary" class="todo-summary">{{ item.summary }}</span>
              </div>
            </div>

            <div v-if="item.actions?.length" class="todo-actions">
              <el-button
                v-for="(action, index) in item.actions"
                :key="actionKey(item, action, index)"
                size="small"
                :loading="previewingKey === actionKey(item, action, index)"
                @click="handleAction(item, action, index)"
              >
                {{ action.label || '去办理' }}
              </el-button>
            </div>
          </li>
        </ul>
        <div v-if="todoLoaded && expanded" class="todo-pagination" aria-label="已加载待办分页">
          <span>第 {{ currentPage }} / {{ pageCount }} 页 · 按紧急程度排序</span>
          <div>
            <button type="button" :disabled="currentPage === 1" @click="currentPage -= 1">上一页</button>
            <button type="button" :disabled="currentPage >= pageCount" @click="currentPage += 1">下一页</button>
          </div>
          <p v-if="todoTotal > todoItemsSorted.length">
            此处显示已加载的 {{ todoItemsSorted.length }} 条，完整记录可到对应业务页查看。
          </p>
        </div>
      </template>
    </div>

    <!-- 办理预览：复用工作台的命令卡片，前置条件、影响范围和有效期都在确认前看清。 -->
    <el-dialog v-model="previewVisible" title="办理确认" width="520px" append-to-body>
      <AiCommandCard v-if="preview" :command="preview" @resolved="handleResolved" />
    </el-dialog>
  </article>
</template>

<style scoped>
.todo-panel {
  min-width: 0;
}

.panel-caption {
  display: block;
  margin-top: 1px;
  overflow: hidden;
  color: var(--mes-text-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-head-actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 4px;
}

.todo-icon {
  display: inline-flex;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  color: #646a73;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}

.todo-icon:hover {
  color: var(--mes-primary);
  background: var(--mes-primary-soft);
}
.todo-icon:disabled {
  opacity: 0.5;
  cursor: wait;
}
@media (pointer: coarse) {
  .todo-icon {
    min-width: var(--mes-touch-target);
    min-height: var(--mes-touch-target);
  }
}

.todo-counts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 10px 14px 2px;
}

.todo-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  color: #646a73;
  font-size: 11px;
  border: 1px solid var(--mes-border);
  border-radius: 20px;
  background: #f7f8fa;
}

.todo-chip strong {
  color: var(--mes-text);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.todo-body {
  min-height: 64px;
  padding: 8px 14px 12px;
}

.todo-tip {
  margin: 0;
  padding: 14px 0;
  color: var(--mes-text-muted);
  font-size: 12px;
}

.todo-empty {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 6px;
  color: var(--mes-text-muted);
}
.todo-empty strong {
  color: var(--mes-text-secondary);
  font-size: 14px;
  font-weight: 500;
}
.todo-empty p {
  margin-top: 4px;
  font-size: 13px;
}
.todo-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.todo-error p {
  color: var(--mes-text-secondary);
  font-size: 13px;
}

.todo-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.todo-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 14px 0 0;
  color: var(--mes-text-secondary);
  font-size: 12px;
}
.todo-pagination > div {
  display: flex;
  gap: 8px;
}
.todo-pagination button {
  min-height: 44px;
  padding: 8px 14px;
  color: var(--mes-primary);
  background: var(--mes-primary-soft);
  border: 1px solid var(--mes-primary-border);
  border-radius: var(--mes-radius-sm);
  font: inherit;
  cursor: pointer;
}
.todo-pagination button:disabled {
  opacity: 0.5;
  cursor: default;
}
.todo-pagination p {
  width: 100%;
  margin: 0;
  line-height: 1.8;
}

.todo-row {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px dashed #eef1f5;
}

.todo-row:last-child {
  border-bottom: 0;
}

.todo-main {
  min-width: 0;
  flex: 1;
}

.todo-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}

.todo-severity {
  flex: none;
  padding: 0 5px;
  font-size: 10px;
  line-height: 16px;
  border-radius: 3px;
}

.todo-severity.is-high {
  color: #d83931;
  background: #fdeceb;
}

.todo-severity.is-medium {
  color: #d97a00;
  background: #fdf3e5;
}

.todo-severity.is-low {
  color: #646a73;
  background: #f2f3f5;
}

.todo-type {
  flex: none;
  color: var(--mes-text-muted);
  font-size: 11px;
}

.todo-title {
  overflow: hidden;
  color: var(--mes-text);
  font-size: 12.5px;
  font-weight: 400;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-meta {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 3px;
  color: var(--mes-text-muted);
  font-size: 11px;
}

.todo-no {
  color: var(--mes-primary);
  font-variant-numeric: tabular-nums;
}

.todo-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.todo-actions {
  display: flex;
  flex: none;
  gap: 6px;
}

@media (max-width: 720px) {
  .todo-row {
    align-items: flex-start;
    flex-direction: column;
    gap: 6px;
  }

  .todo-title,
  .todo-summary {
    white-space: normal;
  }
}
</style>
