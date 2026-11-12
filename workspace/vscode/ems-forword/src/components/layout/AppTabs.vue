<script setup>
import { nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import { firstAccessiblePath, getModuleDefinition } from '@/config/navigation';

// refresh事件交给layout处理，因为重新创建RouterView组件需要由父布局修改key。
const emit = defineEmits(['refresh']);
const route = useRoute();
const router = useRouter();
defineProps({ compact: Boolean });
const closingPath = ref('');
const tabsScroll = ref();

async function revealActiveTab() {
  await nextTick();
  tabsScroll.value?.querySelector('.tab-item.active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
}

// tabs与layout中的标签数组双向绑定，新增和关闭标签时父子组件看到同一份状态。
const tabs = defineModel({
  type: Array,
  default: () => [],
});

// 标签的身份是路由路径（不含 query）。业务页的下钻、筛选、定位都以 query 表达，
// 同一个页面换一组 query 仍然是一个页面，不能每次都新开一个同名标签。
function tabIdentity(currentRoute) {
  return currentRoute.path;
}

// 普通页面标题来自route.meta；模块首页需要根据动态moduleKey查询模块名称。
function routeTitle(currentRoute) {
  if (currentRoute.name === 'ModuleHub') {
    return getModuleDefinition(currentRoute.params.moduleKey).title;
  }
  return currentRoute.meta.title || '未命名页面';
}

// 每次路由变化都把当前页面加入标签栏。同一个路径只保留一个标签，
// 但记住最近一次的 fullPath，这样从标签切回来时还能带上原来的筛选条件。
function addCurrentRoute(currentRoute) {
  if (!currentRoute.name || currentRoute.meta.public) {
    return;
  }
  const identity = tabIdentity(currentRoute);
  const target = tabs.value.find((tab) => tab.path === identity);
  if (!target) {
    tabs.value.push({
      path: identity,
      fullPath: currentRoute.fullPath,
      title: routeTitle(currentRoute),
      affix: Boolean(currentRoute.meta.affix),
    });
    return;
  }
  target.fullPath = currentRoute.fullPath;
  target.title = routeTitle(currentRoute);
}

// 判断标签是否为当前页。query 变化不改变标签身份，所以这里只比路径。
function isActive(tab) {
  return tab.path === route.path;
}

// 固定首页不能关闭；关闭当前标签后自动跳到右侧、左侧或首页。
async function closeTab(tab) {
  if (tab.affix || closingPath.value) return;
  const index = tabs.value.findIndex((item) => item.path === tab.path);
  if (index < 0) return;
  closingPath.value = tab.path;
  try {
    if (isActive(tab)) {
      const next = tabs.value[index + 1] || tabs.value[index - 1];
      const failure = await router.push(next ? next.fullPath || next.path : firstAccessiblePath('/forbidden'));
      // Router guards can keep an unsaved page open. Keep its tab as well.
      if (failure || isActive(tab)) return;
    }
    tabs.value = tabs.value.filter((item) => item.path !== tab.path);
    await revealActiveTab();
  } catch {
    // A failed navigation must never remove the current page's tab.
  } finally {
    closingPath.value = '';
  }
}

// 保留固定标签和当前标签，移除其余已打开页面。
function closeOthers() {
  tabs.value = tabs.value.filter((tab) => tab.affix || isActive(tab));
}

// immediate使组件首次加载时也立刻登记当前路由。
watch(
  () => route.fullPath,
  () => {
    addCurrentRoute(route);
    revealActiveTab();
  },
  { immediate: true },
);
</script>

<template>
  <!-- 左侧循环显示已打开页面标签，右侧提供刷新和关闭其他标签操作。 -->
  <nav class="app-tabs" :class="{ 'is-compact': compact }" aria-label="已打开页面">
    <div ref="tabsScroll" class="tabs-scroll">
      <div v-for="tab in tabs" :key="tab.path" class="tab-item" :class="{ active: isActive(tab) }">
        <button
          class="tab-open"
          type="button"
          :title="tab.title"
          :aria-current="isActive(tab) ? 'page' : undefined"
          @click="router.push(tab.fullPath || tab.path)"
        >
          <span class="tab-title">{{ tab.title }}</span>
        </button>
        <button
          v-if="!tab.affix"
          class="tab-close"
          type="button"
          :aria-label="`关闭${tab.title}标签页`"
          :disabled="closingPath === tab.path"
          @click.stop="closeTab(tab)"
        >
          <AppIcon name="close" :size="12" />
        </button>
      </div>
    </div>

    <div class="tab-actions">
      <el-tooltip content="刷新当前页" placement="bottom">
        <el-button text aria-label="刷新当前页" @click="emit('refresh')">
          <AppIcon name="refresh" :size="15" />
        </el-button>
      </el-tooltip>
      <el-dropdown trigger="click">
        <el-button text aria-label="标签页菜单"><AppIcon name="more" :size="16" /></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item @click="closeOthers">关闭其他标签页</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </nav>
</template>

<style scoped>
/* 多页签栏样式：控制横向滚动、激活状态、关闭按钮和右侧操作区。 */
.app-tabs {
  display: flex;
  width: 100%;
  height: var(--mes-tabs-height);
  align-items: center;
  border-bottom: 1px solid var(--mes-border);
  background: var(--mes-tabs-bg);
}
.app-tabs.is-compact {
  height: 40px;
  border-bottom: 0;
  background: transparent;
}
.is-compact .tabs-scroll {
  padding: 0;
  gap: 4px;
}
.is-compact .tab-item {
  font-size: 13px;
}

.tabs-scroll {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 2px;
  overflow-x: auto;
  padding: 0 10px;
  scrollbar-width: none;
}

.tabs-scroll::-webkit-scrollbar {
  display: none;
}

.tab-item {
  position: relative;
  display: inline-flex;
  height: 36px;
  min-width: 82px;
  max-width: 174px;
  flex: none;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin: 0;
  padding: 0 8px 0 12px;
  color: var(--mes-text-secondary);
  border: 0;
  border-right: 0;
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  cursor: pointer;
}

.tab-item:hover {
  color: var(--mes-text);
  background: rgb(255 255 255 / 62%);
}

.tab-item.active {
  z-index: 1;
  color: var(--mes-primary);
  background: var(--mes-tab-surface);
  box-shadow: none;
  font-weight: 600;
}

.tab-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tab-open {
  display: inline-flex;
  min-width: 0;
  height: 100%;
  flex: 1;
  align-items: center;
  justify-content: center;
  padding: 0;
  color: inherit;
  border: 0;
  background: transparent;
  font: inherit;
  cursor: pointer;
}
.tab-open:focus-visible,
.tab-close:focus-visible {
  outline: 2px solid var(--mes-primary);
  outline-offset: 2px;
}

.tab-close {
  display: inline-flex;
  width: 24px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: var(--mes-radius-sm);
  background: transparent;
  color: var(--mes-text-muted);
  cursor: pointer;
}

.tab-close:hover {
  color: var(--mes-danger);
  background: var(--mes-danger-soft);
}

.tab-actions {
  display: flex;
  flex: none;
  align-items: center;
  padding: 0 7px;
  border-left: 1px solid var(--mes-border);
  background: transparent;
}

.tab-actions :deep(.el-button) {
  width: 30px;
  height: 30px;
  margin: 0;
  padding: 0;
  color: var(--mes-text-muted);
}

@media (max-width: 640px) {
  .tab-open,
  .tab-close {
    min-height: 36px;
  }
  .tab-item {
    min-width: 94px;
  }

  .tab-actions {
    display: none;
  }
}
</style>
