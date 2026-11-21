<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import dayjs from 'dayjs';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import MesPageHeading from '@/components/common/MesPageHeading.vue';
import {
  acknowledgeNotification,
  getNotificationPage,
  getNotificationUnreadCount,
  markAllNotificationsRead,
  markNotificationRead,
} from '@/api/sys/notification';
import { hasPermission } from '@/utils/permission';
import { isNotificationRouteAccessible } from '@/config/navigation';

// 消息中心集中查询、筛选、阅读和确认当前用户收到的业务通知。
const router = useRouter();
// rows/total保存分页结果；unreadTotal/categoryCounts保存顶部未读统计。
const loading = ref(false);
const loadError = ref('');
const unreadLoading = ref(false);
const unreadError = ref(false);
let loadSequence = 0, unreadSequence = 0;
const markingAll = ref(false);
const rows = ref([]);
const total = ref(0);
const unreadTotal = ref(null);
const categoryCounts = ref({});
const filter = reactive({ current: 1, size: 20, view: 'UNREAD', category: '', severity: '' });

// 查看、标记已读和确认通知是不同权限，页面据此控制相应操作按钮。
const canRead = computed(() => hasPermission('sys:notification:read'));
// 判断当前账号是否可以执行“确认收到”。
const canAcknowledge = computed(() => hasPermission('sys:notification:ack'));
// 根据总条数和每页条数计算至少为1的总页数。
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / filter.size)));
const unreadLabel = computed(() => {
  if (unreadTotal.value === null) return unreadLoading.value ? '正在读取未读数' : '未读数暂不可用';
  return `${unreadTotal.value} 条未读${unreadError.value ? ' · 上次结果' : ''}`;
});

// 通知业务分类筛选选项，空值代表全部分类。
const categoryOptions = [
  { value: '', label: '全部分类' },
  { value: 'MFG', label: '生产' },
  { value: 'PLAN', label: '计划' },
  { value: 'QMS', label: '质量' },
  { value: 'WMS', label: '仓储' },
  { value: 'EAM', label: '设备' },
  { value: 'SYSTEM', label: '系统' },
];

// 通知严重程度筛选选项，值与后端severity编码保持一致。
const severityOptions = [
  { value: '', label: '全部级别' },
  { value: 'INFO', label: '普通' },
  { value: 'WARNING', label: '提醒' },
  { value: 'HIGH', label: '重要' },
  { value: 'CRITICAL', label: '紧急' },
];

async function loadUnread() {
  const sequence = ++unreadSequence;
  unreadLoading.value = true;
  try {
    const result = await getNotificationUnreadCount();
    if (sequence !== unreadSequence) return;
    unreadTotal.value = Number(result?.data?.total || 0);
    categoryCounts.value = result?.data?.categories || {};
    unreadError.value = false;
  } catch {
    if (sequence === unreadSequence) unreadError.value = true;
  } finally {
    if (sequence === unreadSequence) unreadLoading.value = false;
  }
}

async function loadPage() {
  const sequence = ++loadSequence;
  loading.value = true;
  loadError.value = '';
  try {
    const pageResult = await getNotificationPage(
      {
        current: filter.current,
        size: filter.size,
        category: filter.category || undefined,
        severity: filter.severity || undefined,
        readStatus: filter.view === 'ALL' ? undefined : filter.view,
      },
      { suppressErrorMessage: true },
    );
    if (sequence !== loadSequence) return;
    rows.value = pageResult?.data?.records || [];
    total.value = Number(pageResult?.data?.total || 0);
  } catch (error) {
    if (sequence === loadSequence) loadError.value = error.displayMessage || '消息暂未加载，请重试。';
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}

function loadData() {
  return Promise.all([loadPage(), loadUnread()]);
}

// 切换全部/未读视图时重置到第一页并重新查询。
function changeView() {
  // 筛选条件改变后回到第一页，避免旧页码超过新结果总页数。
  filter.current = 1;
  loadPage();
}

// 修改分类或级别筛选时重置到第一页并重新查询。
function changeFilter() {
  filter.current = 1;
  loadPage();
}

// 打开通知前按权限标记已读，再安全跳转到关联业务页。
async function openNotification(row) {
  // 未读消息先更新状态，再跳到通知携带的站内业务页面。
  if (row.readStatus === 'UNREAD' && canRead.value) {
    await markNotificationRead(row.recipientId);
  }
  if (isNotificationRouteAccessible(router, row.routePath)) {
    await router.push(row.routePath);
  } else {
    await loadData();
  }
}

// 将当前分类或全部分类的未读通知批量标记为已读。
async function markAllRead() {
  // 有分类筛选时只批量处理该分类，否则处理全部未读通知。
  if (!canRead.value || !unreadTotal.value || unreadError.value || unreadLoading.value) {
    return;
  }
  markingAll.value = true;
  try {
    await markAllNotificationsRead(filter.category || undefined);
    ElMessage.success('已标记为已读');
    await loadData();
  } finally {
    markingAll.value = false;
  }
}

// 调用确认接口表示用户已明确收到该条重要通知，然后刷新列表。
async function acknowledge(row) {
  await acknowledgeNotification(row.recipientId);
  ElMessage.success('已确认收到');
  await loadData();
}

// 将通知分类编码转换为下拉选项中的中文标签。
function categoryLabel(value) {
  return categoryOptions.find((item) => item.value === value)?.label || value || '业务';
}

// 将严重程度编码转换成中文文字和Element Plus标签类型。
function severityMeta(value) {
  return (
    {
      INFO: { label: '普通', type: 'info' },
      WARNING: { label: '提醒', type: 'warning' },
      HIGH: { label: '重要', type: 'danger' },
      CRITICAL: { label: '紧急', type: 'danger' },
    }[value] || { label: value || '普通', type: 'info' }
  );
}

// 将后端时间格式化为统一的年月日时分显示。
function formatTime(value) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '--';
}

// 页面挂载后立即执行第一次通知查询。
onMounted(loadData);
onBeforeUnmount(() => { loadSequence += 1; unreadSequence += 1; });
</script>

<template>
  <!-- 顶部负责总量和批量操作，下面的筛选条件驱动同一个loadData查询流程。 -->
  <div class="notification-center-page">
    <MesPageHeading title="消息中心" icon="bell" class="notifications-heading">
      <template #detail>
        <span aria-live="polite">{{ unreadLabel }}</span>
        <el-button v-if="unreadError" link type="primary" :loading="unreadLoading" @click="loadUnread">
          重试未读数
        </el-button>
      </template>
      <template #actions>
        <el-button circle aria-label="刷新消息" :loading="loading || unreadLoading" @click="loadData">
          <AppIcon name="refresh" :size="16" />
        </el-button>
        <el-button v-if="canRead" :loading="markingAll" :disabled="!unreadTotal || unreadError || unreadLoading" @click="markAllRead">
          <AppIcon name="check" :size="15" />
          全部已读
        </el-button>
      </template>
    </MesPageHeading>

    <section class="notification-toolbar">
      <el-tabs v-model="filter.view" class="notification-tabs" @tab-change="changeView">
        <el-tab-pane name="UNREAD" :label="unreadTotal === null || unreadError ? '未读' : `未读 ${unreadTotal}`" />
        <el-tab-pane name="READ" label="已读" />
        <el-tab-pane name="ALL" label="全部" />
      </el-tabs>
      <div class="notification-filters">
        <el-select v-model="filter.category" aria-label="通知分类" @change="changeFilter">
          <el-option
            v-for="item in categoryOptions"
            :key="item.value || 'all'"
            :label="item.value && unreadTotal !== null && !unreadError ? `${item.label} ${categoryCounts[item.value] || 0}` : item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="filter.severity" aria-label="通知级别" @change="changeFilter">
          <el-option
            v-for="item in severityOptions"
            :key="item.value || 'all'"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </div>
    </section>

    <section v-loading="loading" class="notification-list" element-loading-text="正在加载消息">
      <div
        v-for="row in rows"
        :key="row.recipientId"
        class="notification-row"
        :class="{ unread: row.readStatus === 'UNREAD' }"
      >
        <span class="notification-status-dot" />
        <span class="notification-row-main">
          <span class="notification-row-title">
            <button type="button" class="notification-open" @click="openNotification(row)">{{ row.title }}</button>
            <el-tag size="small" effect="plain">{{ categoryLabel(row.category) }}</el-tag>
            <el-tag size="small" effect="plain" :type="severityMeta(row.severity).type">{{
              severityMeta(row.severity).label
            }}</el-tag>
          </span>
          <span class="notification-summary">{{ row.summary || row.bizNo || '业务状态已更新' }}</span>
          <span class="notification-meta">
            <span>{{ row.bizNo || row.bizType }}</span>
            <span>{{ formatTime(row.occurredTime) }}</span>
            <span v-if="row.readStatus === 'UNREAD'">未读</span>
          </span>
        </span>
        <span v-if="canAcknowledge && row.ackStatus === 'PENDING'" class="notification-row-actions">
          <el-button size="small" @click="acknowledge(row)"
            >确认收到</el-button
          >
        </span>
      </div>
      <div v-if="loadError" class="notification-load-error" role="alert">
        <AppIcon name="alert" :size="16" />{{ loadError }}
        <el-button link type="primary" @click="loadPage">重新加载</el-button>
      </div>
      <el-empty v-else-if="!loading && !rows.length" description="暂无符合条件的消息" :image-size="60" />
    </section>

    <footer v-if="total" class="notification-pagination">
      <span>共 {{ total }} 条</span>
      <el-pagination
        v-model:current-page="filter.current"
        :page-size="filter.size"
        :page-count="pageCount"
        layout="prev, pager, next"
        @current-change="loadPage"
      />
    </footer>
  </div>
</template>

<style scoped>
/* 消息中心样式：控制筛选工具栏、通知列表、未读状态、分页和移动端排列。 */
.notification-center-page {
  min-width: 0;
}
.notification-toolbar,
.notification-filters,
.notification-row-title,
.notification-meta,
.notification-row-actions,
.notification-pagination {
  display: flex;
  align-items: center;
}
.notifications-heading {
  margin-bottom: 14px;
}
.notification-toolbar {
  min-height: 52px;
  justify-content: space-between;
  gap: 16px;
  padding: 0 14px;
  border: 1px solid var(--mes-border);
  border-bottom: 0;
  background: var(--mes-surface);
}
.notification-tabs {
  align-self: stretch;
}
.notification-tabs :deep(.el-tabs__header) {
  height: 100%;
  margin: 0;
}
.notification-tabs :deep(.el-tabs__nav-wrap),
.notification-tabs :deep(.el-tabs__nav-scroll),
.notification-tabs :deep(.el-tabs__nav) {
  height: 100%;
}
.notification-tabs :deep(.el-tabs__item) {
  height: 100%;
  font-size: 12px;
}
.notification-filters {
  gap: 8px;
}
.notification-filters :deep(.el-select) {
  width: 132px;
}
.notification-list {
  min-height: 320px;
  border: 1px solid var(--mes-border);
  background: var(--mes-surface);
}
.notification-load-error {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 14px 16px;
  color: var(--mes-danger);
}
.notification-row {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 92px;
  grid-template-columns: 10px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 13px 16px;
  color: var(--mes-text);
  border: 0;
  border-bottom: 1px solid var(--mes-border);
  background: transparent;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.notification-row:hover {
  background: var(--mes-surface-muted);
}
.notification-row.unread {
  background: rgb(var(--mes-primary-rgb) / 4%);
}
.notification-status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--mes-border-strong);
}
.notification-row.unread .notification-status-dot {
  background: var(--mes-primary);
}
.notification-row-main {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
}
.notification-row-title {
  min-width: 0;
  gap: 6px;
}
.notification-open {
  min-width: 0;
  overflow: hidden;
  margin-right: 4px;
  padding: 0;
  color: var(--mes-text);
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.notification-open::after {
  position: absolute;
  inset: 0;
  content: '';
}
.notification-open:hover,
.notification-open:focus-visible {
  color: var(--mes-primary);
}
.notification-summary {
  overflow: hidden;
  color: var(--mes-text-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-meta {
  gap: 14px;
  color: var(--mes-text-muted);
  font-size: 10px;
}
.notification-row-actions {
  position: relative;
  z-index: 1;
  gap: 10px;
  color: var(--mes-text-muted);
}
.notification-pagination {
  justify-content: space-between;
  padding: 11px 14px;
  border: 1px solid var(--mes-border);
  border-top: 0;
  background: var(--mes-surface);
}
.notification-pagination > span {
  color: var(--mes-text-muted);
  font-size: 11px;
}
@media (max-width: 700px) {
    .notification-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
    .notification-filters {
    width: 100%;
  }
  .notification-toolbar {
    padding-top: 10px;
  }
  .notification-tabs {
    width: 100%;
  }
  .notification-filters :deep(.el-select) {
    min-width: 0;
    flex: 1;
  }
  .notification-row {
    grid-template-columns: 8px minmax(0, 1fr);
    padding: 12px;
  }
  .notification-row-actions {
    grid-column: 2;
    justify-content: flex-end;
  }
  .notification-row-title {
    align-items: flex-start;
    flex-wrap: wrap;
  }
  .notification-open {
    width: 100%;
  }
  .notification-summary {
    white-space: normal;
  }
  .notification-meta {
    align-items: flex-start;
    flex-direction: column;
    gap: 3px;
  }
}
</style>
