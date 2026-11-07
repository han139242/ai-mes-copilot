/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import { computed, ref } from 'vue';
import { getMyTodos } from '@/api/ai/todo';
import { isFrameworkPreview } from '@/utils/auth';

/*
 * 我的待办状态：首页待办面板和工作台共用这一份数据。
 * 工作台办理成功后调 refreshTodos()，面板上的条数和列表随之更新，
 * 不需要两个页面各自请求、也不会出现一边办完另一边还挂着旧数字。
 */
export const todoItems = ref([]);
export const todoCounts = ref({});
export const todoTotal = ref(0);
export const todoGeneratedAt = ref('');
export const todoLoading = ref(false);
export const todoLoaded = ref(false);
// 接口不可用时在面板内说明原因并提供重试，不弹全局提示。
export const todoError = ref('');

// 并发去重：首页定时刷新和工作台办理回调可能同时触发，只让第一个请求真正发出。
let inflight = null;

export const todoItemsSorted = computed(() =>
  [...todoItems.value].sort((left, right) => severityWeight(right?.severity) - severityWeight(left?.severity)),
);

// 严重程度排序：HIGH 在前，其次 MEDIUM、LOW。
function severityWeight(severity) {
  const weight = { HIGH: 3, MEDIUM: 2, LOW: 1 };
  return weight[String(severity || '').toUpperCase()] || 0;
}

export function applyTodoPayload(payload) {
  const data = payload || {};
  todoItems.value = Array.isArray(data.items) ? data.items : [];
  todoCounts.value = data.counts && typeof data.counts === 'object' ? data.counts : {};
  todoTotal.value = Number(data.total ?? todoItems.value.length) || 0;
  todoGeneratedAt.value = data.generatedAt || '';
  todoLoaded.value = true;
}

// 拉一次待办；silent 为真时不显示加载态，用于 60 秒自动刷新。
export async function refreshTodos(silent = false) {
  // Framework preview has no authenticated backend data; do not turn it into an outage.
  if (isFrameworkPreview()) {
    return false;
  }
  if (inflight) {
    return inflight;
  }
  if (!silent) {
    todoLoading.value = true;
  }
  todoError.value = '';
  inflight = (async () => {
    try {
      const response = await getMyTodos();
      applyTodoPayload(response?.data || {});
      return true;
    } catch (error) {
      // 自动刷新失败时保留上一次的列表，避免首页数字突然清零。
      todoError.value = error?.displayMessage || error?.message || '';
      return false;
    } finally {
      todoLoading.value = false;
      inflight = null;
    }
  })();
  return inflight;
}
