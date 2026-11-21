<script setup>
import { ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import { readSavedFilterValues } from '@/utils/resource-page-state';

const props = defineProps({
  storageKey: { type: String, required: true },
  fields: { type: Array, required: true },
  current: { type: Object, required: true },
  canSave: { type: Boolean, default: false },
});
const emit = defineEmits(['apply']);
const views = ref([]);
const open = ref(false);
const name = ref('');
const error = ref('');
const limit = 12;

watch(() => props.storageKey, () => {
  views.value = [];
  open.value = false;
  if (!props.storageKey) return;
  try {
    const stored = JSON.parse(localStorage.getItem(props.storageKey) || '[]');
    if (Array.isArray(stored)) {
      views.value = stored
        .filter((item) => typeof item?.name === 'string' && item.name.trim() && item.name.length <= 32)
        .slice(0, limit)
        .map(({ name, values }) => ({ name, values: readSavedFilterValues(props.fields, values) }))
        .filter(({ values }) => Object.keys(values).length);
    }
  } catch {
    // Saved filters are optional when browser storage is unavailable.
  }
}, { immediate: true });

function persist(next) {
  if (!props.storageKey) return false;
  try {
    localStorage.setItem(props.storageKey, JSON.stringify(next));
  } catch {
    error.value = '浏览器暂时无法保存筛选。';
    return false;
  }
  views.value = next;
  error.value = '';
  return true;
}

function saveView() {
  const label = name.value.trim();
  if (!props.canSave || !label || views.value.length >= limit) return;
  if (views.value.some((view) => view.name === label)) {
    error.value = '已有同名筛选，请换一个名称。';
    return;
  }
  const values = readSavedFilterValues(props.fields, props.current);
  if (Object.keys(values).length && persist([...views.value, { name: label, values }])) {
    name.value = '';
    ElMessage.success('已保存筛选');
  }
}

function applyView(view) {
  open.value = false;
  emit('apply', view.values);
}

function removeView(index) {
  persist(views.value.filter((_, position) => position !== index));
}
</script>

<template>
  <el-popover
    v-model:visible="open"
    placement="bottom-end"
    :width="300"
    trigger="click"
    @hide="name = ''; error = ''"
  >
    <template #reference>
      <el-button :aria-expanded="open"><AppIcon name="pin" :size="15" />常用筛选</el-button>
    </template>

    <div class="filter-views">
      <p>保存在此浏览器，仅当前账号可用。</p>
      <ul v-if="views.length">
        <li v-for="(view, index) in views" :key="view.name">
          <button type="button" class="filter-view-apply" @click="applyView(view)">
            {{ view.name }}
          </button>
          <el-button
            link
            type="info"
            :aria-label="`删除常用筛选 ${view.name}`"
            @click="removeView(index)"
          ><AppIcon name="close" :size="15" /></el-button>
        </li>
      </ul>
      <p v-else>还没有保存的筛选。</p>
      <form v-if="views.length < limit" class="filter-view-save" @submit.prevent="saveView">
        <el-input
          v-model="name"
          maxlength="32"
          placeholder="为当前筛选命名"
          aria-label="常用筛选名称"
          :disabled="!canSave"
          @input="error = ''"
        />
        <el-button native-type="submit" type="primary" :disabled="!canSave || !name.trim()">保存</el-button>
      </form>
      <p v-if="!canSave && views.length < limit">应用查询条件后可保存。</p>
      <p v-if="views.length >= limit">已保存 {{ limit }} 个筛选。</p>
      <p v-if="error" role="alert">{{ error }}</p>
    </div>
  </el-popover>
</template>

<style scoped>
.filter-views {
  display: grid;
  gap: 8px;
  color: var(--mes-text);
}
.filter-views p {
  margin: 0;
  color: var(--mes-text-secondary);
  font-size: var(--mes-font-label);
}
.filter-views ul {
  display: grid;
  max-height: min(40vh, 240px);
  gap: 2px;
  margin: 0;
  padding: 4px 0;
  overflow-y: auto;
  list-style: none;
}
.filter-views li,
.filter-view-save {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 6px;
}
.filter-view-apply {
  min-width: 0;
  min-height: 38px;
  flex: 1;
  padding: 5px 8px;
  overflow: hidden;
  color: var(--mes-text);
  border: 0;
  border-radius: var(--mes-radius-sm);
  background: transparent;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  font: inherit;
  cursor: pointer;
}
.filter-view-apply:hover {
  color: var(--mes-primary);
  background: var(--mes-primary-soft);
}
.filter-views li :deep(.el-button) {
  min-width: 36px;
  min-height: 36px;
}
</style>
