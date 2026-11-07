<script setup>
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import { appName, appShortName } from '@/config/branding';
import { getNavigationGroups, getNavigationItems } from '@/config/navigation';
import { openAiAssistant } from '@/utils/app-events';
import { backendConnection, backendConnectionLabel } from '@/utils/service-connection';

// collapsed控制桌面端折叠；mobile表示当前使用移动端抽屉布局。
const props = defineProps({
  collapsed: { type: Boolean, default: false },
  mobile: { type: Boolean, default: false },
});

// 点击菜单后通知父布局关闭移动端抽屉。
const emit = defineEmits(['navigate']);
const route = useRoute();
const router = useRouter();
// navigation.js已按当前用户权限过滤菜单，这里分别取得扁平搜索列表和分组展示列表。
const navigationItems = computed(() => getNavigationItems());
// 分组菜单用于正常侧边栏展示。
const navigationGroups = computed(() => getNavigationGroups());
// 当前路由路径交给Element Plus高亮正在访问的菜单项。
const activePath = computed(() => route.path);
// 移动端始终显示完整抽屉菜单，只有桌面端collapsed才折叠为图标栏。
const menuCollapsed = computed(() => props.collapsed && !props.mobile);
const searchKeyword = ref('');
// 分组折叠状态：默认全部展开，点击分组标题可收起，减少长菜单的视觉噪音。
const collapsedGroups = ref(new Set());
// 菜单搜索同时匹配标题和业务说明，computed会随输入内容自动更新。
const searchResults = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return [];
  }
  return navigationItems.value.filter((item) =>
    [item.label, item.description].some((value) =>
      String(value || '')
        .toLowerCase()
        .includes(keyword),
    ),
  );
});

// 切换某个业务分组的展开/收起状态。
function toggleGroup(label) {
  const next = new Set(collapsedGroups.value);
  if (next.has(label)) {
    next.delete(label);
  } else {
    next.add(label);
  }
  collapsedGroups.value = next;
}

// 为Element Plus菜单生成唯一index：路由项使用path，动作项使用action前缀。
function itemIndex(item) {
  // Element Plus菜单需要唯一index；普通菜单用路径，AI按钮用action前缀区分。
  return item.path || `action:${item.action}`;
}

// 处理菜单选择：普通项跳转路由，AI项广播打开助手事件，最后通知移动端关闭菜单。
function handleSelect(index) {
  // 根据index找回菜单配置，再决定打开AI助手还是执行路由跳转。
  const item = navigationItems.value.find((candidate) => itemIndex(candidate) === index);
  if (!item) {
    return;
  }

  if (item.action === 'ai') {
    openAiAssistant();
  } else if (item.path) {
    router.push(item.path);
  }
  searchKeyword.value = '';
  emit('navigate');
}

// 在菜单搜索框按Enter时直接打开第一条匹配结果。
function openFirstSearchResult() {
  const [first] = searchResults.value;
  if (first) {
    handleSelect(itemIndex(first));
  }
}
</script>

<template>
  <!-- 搜索时显示扁平结果；正常状态按navigationGroups渲染可折叠的业务分组菜单。 -->
  <aside class="app-sidebar" :class="{ 'is-collapsed': menuCollapsed }">
    <div class="sidebar-brand">
      <span class="sidebar-logo" aria-hidden="true">
        <AppIcon name="bot" :size="18" />
      </span>
      <div v-show="!menuCollapsed" class="brand-copy">
        <strong>{{ appShortName }}</strong>
        <span>自然语言 · 制造执行</span>
      </div>
    </div>

    <div v-if="!menuCollapsed" class="sidebar-search">
      <el-input v-model="searchKeyword" clearable placeholder="搜索菜单 / 功能" @keyup.enter="openFirstSearchResult">
        <template #prefix><AppIcon name="search" :size="15" /></template>
      </el-input>
    </div>

    <el-scrollbar class="sidebar-scroll">
      <div v-if="searchKeyword && !menuCollapsed" class="sidebar-search-results">
        <button
          v-for="item in searchResults"
          :key="itemIndex(item)"
          type="button"
          @click="handleSelect(itemIndex(item))"
        >
          <span><AppIcon :name="item.icon || 'database'" :size="17" /></span>
          <div>
            <strong>{{ item.label }}</strong
            ><small>{{ item.description || '打开业务页面' }}</small>
          </div>
          <AppIcon name="right" :size="14" />
        </button>
        <p v-if="!searchResults.length">没有匹配的菜单</p>
      </div>
      <el-menu
        v-else
        :default-active="activePath"
        :collapse="menuCollapsed"
        :collapse-transition="false"
        unique-opened
        @select="handleSelect"
      >
        <template v-for="group in navigationGroups" :key="group.label">
          <button
            v-if="!menuCollapsed"
            class="menu-group-label"
            type="button"
            :class="{ collapsed: collapsedGroups.has(group.label) }"
            @click="toggleGroup(group.label)"
          >
            <span>{{ group.label }}</span>
            <AppIcon name="chevron" :size="12" />
          </button>
          <template v-if="!collapsedGroups.has(group.label)">
            <template v-for="item in group.items" :key="item.key || item.path || item.action">
              <el-sub-menu v-if="item.children?.length" :index="`group:${item.key}`">
                <template #title>
                  <AppIcon :name="item.icon" :size="18" />
                  <span>{{ item.label }}</span>
                </template>
                <el-menu-item v-for="child in item.children" :key="child.path" :index="itemIndex(child)">
                  <span class="submenu-marker" />
                  <template #title>{{ child.label }}</template>
                </el-menu-item>
              </el-sub-menu>
              <el-menu-item v-else :index="itemIndex(item)" :class="{ 'is-featured': item.featured }">
                <AppIcon :name="item.icon" :size="18" />
                <template #title>{{ item.label }}</template>
              </el-menu-item>
            </template>
          </template>
        </template>
      </el-menu>
    </el-scrollbar>

    <div class="sidebar-footer" :class="{ compact: menuCollapsed }">
      <span class="service-dot" :class="backendConnection" />
      <div v-show="!menuCollapsed">
        <strong>{{ backendConnectionLabel }}</strong>
        <span>{{ appName }}</span>
      </div>
    </div>
  </aside>
</template>

<style scoped>
/* 侧边栏：浅色导航 + 单一操作色，形成“工业控制台”的视觉基调。 */
.app-sidebar {
  display: flex;
  width: 100%;
  height: 100%;
  flex-direction: column;
  color: var(--mes-sidebar-text);
  border-right: 1px solid var(--mes-sidebar-border);
  background: var(--mes-sidebar);
}

.sidebar-brand {
  display: flex;
  height: var(--mes-header-height);
  flex: none;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  border-bottom: 1px solid var(--mes-sidebar-border);
}

.is-collapsed .sidebar-brand {
  justify-content: center;
  padding: 0;
}

.sidebar-search {
  flex: none;
  padding: 10px 12px;
  border-bottom: 1px solid var(--mes-sidebar-border);
}

.sidebar-search :deep(.el-input__wrapper) {
  min-height: 32px;
  border-radius: 6px;
  background: var(--mes-sidebar);
  box-shadow: 0 0 0 1px var(--mes-sidebar-border) inset;
}

.sidebar-search :deep(.el-input__inner) {
  color: var(--mes-sidebar-active-text);
  font-size: 13px;
}

.sidebar-search :deep(.el-input__inner::placeholder) {
  color: var(--mes-sidebar-muted);
}

.sidebar-search :deep(.el-input__prefix) {
  color: var(--mes-sidebar-muted);
}

.sidebar-search-results {
  display: flex;
  flex-direction: column;
  padding: 8px 0;
}

.sidebar-search-results button {
  display: grid;
  width: 100%;
  min-height: 54px;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  color: var(--mes-sidebar-text);
  border: 0;
  border-left: 3px solid transparent;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.sidebar-search-results button:hover {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-raised);
}

.sidebar-search-results button > span {
  display: inline-flex;
  width: 26px;
  height: 26px;
  align-items: center;
  justify-content: center;
  color: var(--mes-sidebar-muted);
  border: 1px solid var(--mes-sidebar-border);
  border-radius: 5px;
  background: var(--mes-sidebar);
}

.sidebar-search-results button > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.sidebar-search-results strong,
.sidebar-search-results small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-search-results strong {
  font-size: 12px;
}
.sidebar-search-results small {
  margin-top: 2px;
  color: var(--mes-sidebar-muted);
  font-size: 11px;
}
.sidebar-search-results p {
  padding: 24px 16px;
  color: var(--mes-sidebar-muted);
  font-size: 11px;
  text-align: center;
}

/* 品牌标志：线框圆环，不用发光和渐变。 */
.sidebar-logo {
  color: var(--mes-primary);
  position: relative;
  display: inline-flex;
  width: 30px;
  height: 30px;
  flex: none;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--mes-sidebar-featured-border);
  border-radius: 7px;
  background: var(--mes-sidebar-raised);
}

.brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.brand-copy strong {
  overflow: hidden;
  color: var(--mes-sidebar-active-text);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.2px;
  line-height: 1.25;
  white-space: nowrap;
}

.brand-copy span {
  margin-top: 2px;
  color: var(--mes-sidebar-muted);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.sidebar-scroll {
  flex: 1;
}

:deep(.el-menu) {
  border-right: 0;
  background: transparent;
}

:deep(.el-menu-item) {
  height: 40px;
  margin: 1px 8px;
  padding: 0 10px !important;
  gap: 10px;
  color: var(--mes-sidebar-text);
  border-radius: 6px;
  font-size: 13px;
  line-height: 40px;
}

:deep(.el-sub-menu__title) {
  height: 40px;
  margin: 1px 8px;
  padding: 0 10px !important;
  gap: 10px;
  color: var(--mes-sidebar-text);
  border-radius: 6px;
  font-size: 13px;
  line-height: 40px;
}

:deep(.el-sub-menu__title:hover) {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-raised);
}

:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-raised);
  font-weight: 500;
}

:deep(.el-sub-menu .el-menu-item) {
  height: 32px;
  margin: 1px 8px 1px 14px;
  padding-left: 12px !important;
  font-size: 12px;
  line-height: 32px;
}

.submenu-marker {
  width: 4px;
  height: 4px;
  flex: none;
  border-radius: 50%;
  background: var(--mes-sidebar-muted);
  transition: background 0.15s ease;
}

:deep(.el-sub-menu .el-menu-item.is-active) .submenu-marker {
  background: var(--mes-primary);
}

:deep(.el-menu-item:hover) {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-raised);
}

/* Selected navigation uses a quiet surface and stronger text. */
:deep(.el-menu-item.is-active) {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-active);
  font-weight: 600;
}

:deep(.el-menu-item.is-featured) {
  color: var(--mes-sidebar-text);
}

:deep(.el-menu-item.is-featured:hover),
:deep(.el-menu-item.is-featured.is-active) {
  color: var(--mes-sidebar-active-text);
  background: var(--mes-sidebar-active);
}

:deep(.el-menu--collapse .el-menu-item),
:deep(.el-menu--collapse .el-sub-menu__title) {
  justify-content: center;
  margin: 2px 8px;
  padding: 0 !important;
}

/* 分组标题可点击收起，折叠时箭头旋转。 */
.menu-group-label {
  display: flex;
  width: calc(100% - 16px);
  height: 28px;
  align-items: center;
  justify-content: space-between;
  margin: 12px 8px 3px;
  padding: 0 6px;
  color: var(--mes-sidebar-group);
  border: 0;
  background: transparent;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.8px;
  cursor: pointer;
}

.menu-group-label span {
  position: relative;
  padding-left: 0;
}

.menu-group-label:hover {
  color: var(--mes-sidebar-text);
}

.menu-group-label :deep(.app-icon) {
  transform: rotate(-90deg);
  transition: transform 0.18s ease;
}

.menu-group-label.collapsed :deep(.app-icon) {
  transform: rotate(90deg);
}

.sidebar-footer {
  display: flex;
  min-height: 52px;
  flex: none;
  align-items: center;
  gap: 9px;
  padding: 10px 16px;
  border-top: 1px solid var(--mes-sidebar-border);
}

.sidebar-footer.compact {
  justify-content: center;
  padding: 0;
}

.sidebar-footer > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
}

.sidebar-footer strong {
  color: var(--mes-sidebar-text);
  font-size: 11px;
  font-weight: 500;
}

.sidebar-footer span:not(.service-dot) {
  overflow: hidden;
  color: var(--mes-sidebar-muted);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: #22c55e;
}

.service-dot.unknown {
  background: #94a3b8;
}
.service-dot.disconnected {
  background: #f59e0b;
}
</style>
