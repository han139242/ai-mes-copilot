<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import GroundedAnswer from './GroundedAnswer.vue';
import { noteDraft, noteChanged, noteMarkdown, noteBusinessDraft } from '@/utils/review-notebook';
import { authRevision, getToken } from '@/utils/auth';
import { registerPageRefreshGuard } from '@/utils/app-events';
const props = defineProps({ active: Boolean, request: { type: Function, required: true } });
const emit = defineEmits(['source', 'business']);
const ownerToken = getToken();
const items = ref([]),
  selectedId = ref(''),
  snapshot = ref(null),
  draft = ref(null);
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  newTask = ref(''),
  taskInput = ref();
let mounted = true,
  sequence = 0;
let discardCheck = null,
  cancelDiscardCheck = null;
const current = computed(
  () =>
    items.value.find((n) => n.id === selectedId.value) ||
    (snapshot.value ? { ...snapshot.value, fresh: false, reason: '工作单已在别处删除，当前草稿仍保留' } : null),
);
const dirty = computed(() => noteChanged(draft.value, snapshot.value));
const completed = computed(() => draft.value?.tasks.filter((t) => t.done).length || 0);
function adopt(note) {
  if (!mounted) return;
  selectedId.value = note.id;
  snapshot.value = note;
  draft.value = noteDraft(note);
  newTask.value = '';
}
function message(failure) {
  return failure?.response?.data?.message || failure?.message || '暂时无法保存，请稍后重试';
}
async function canSwitch() {
  if (getToken() !== ownerToken) {
    clearForAuthChange();
    return true;
  }
  if (!dirty.value) return true;
  if (!discardCheck) {
    const confirmation = ElMessageBox.confirm('当前工作单有未保存的修改。', '保留当前草稿？', {
      confirmButtonText: '放弃修改并继续',
      cancelButtonText: '保留草稿',
      type: 'warning',
      closeOnHashChange: false,
    }).then(
      () => true,
      () => getToken() !== ownerToken,
    );
    discardCheck = Promise.race([
      confirmation,
      new Promise((resolve) => {
        cancelDiscardCheck = () => resolve(true);
      }),
    ]).finally(() => {
      discardCheck = null;
      cancelDiscardCheck = null;
    });
  }
  return discardCheck;
}
async function select(note) {
  if (!busy.value && note.id !== selectedId.value && (await canSwitch())) adopt(note);
}
async function refresh() {
  if (busy.value) return;
  const token = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const value = await props.request('notes.list');
    if (!mounted || token !== sequence) return;
    items.value = value;
    const selected = value.find((n) => n.id === selectedId.value);
    if (selected && !dirty.value) adopt(selected);
    else if (!selectedId.value && value.length) adopt(value[0]);
    else if (selectedId.value && !selected && !dirty.value) {
      selectedId.value = '';
      snapshot.value = null;
      draft.value = null;
    }
  } catch (failure) {
    if (mounted && token === sequence) error.value = message(failure);
  } finally {
    if (mounted && token === sequence) loading.value = false;
  }
}
function beginMutation() {
  sequence++;
  loading.value = false;
  busy.value = true;
  error.value = '';
}
async function capture(historyId) {
  if (!mounted || busy.value || !(await canSwitch()) || !mounted) return;
  beginMutation();
  try {
    const note = await props.request('notes.add', { historyId });
    if (!mounted) return;
    items.value = [note, ...items.value.filter((n) => n.id !== note.id)];
    adopt(note);
    ElMessage.success('已保存答复和原文依据');
  } catch (failure) {
    if (mounted) error.value = message(failure);
  } finally {
    if (mounted) busy.value = false;
  }
}
async function save() {
  if (!dirty.value) return current.value;
  const value = await props.request('notes.update', noteDraft(draft.value));
  if (!mounted) return null;
  items.value = [value, ...items.value.filter((n) => n.id !== value.id)];
  adopt(value);
  return value;
}
async function persist() {
  if (busy.value) return;
  beginMutation();
  try {
    await save();
    if (mounted) ElMessage.success('工作单已保存');
  } catch (failure) {
    if (mounted) error.value = message(failure);
  } finally {
    if (mounted) busy.value = false;
  }
}
function addTask() {
  const text = newTask.value.trim();
  if (!draft.value || !text || draft.value.tasks.length >= 20) return;
  draft.value.tasks.push({ text, done: false });
  newTask.value = '';
  nextTick(() => taskInput.value?.focus());
}
function downloadNote(note, checkedAt = new Date().toISOString()) {
  const blob = new Blob([noteMarkdown(note, checkedAt)], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob),
    link = document.createElement('a');
  link.href = url;
  link.download = note.title.replace(/[\\/:*?"<>|\r\n]/g, '_').slice(0, 80) + '.md';
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 30000);
}
function exportDraft() {
  if (!snapshot.value || !draft.value) return;
  downloadNote(
    { ...snapshot.value, ...draft.value, fresh: false, reason: '未保存本机草稿，未核对最新来源' },
    '未核对（本机草稿）',
  );
}
async function remove() {
  if (!current.value || busy.value) return;
  try {
    await ElMessageBox.confirm('删除这份工作单，原问答和资料会保留。', '删除工作单', {
      confirmButtonText: '删除工作单',
      cancelButtonText: '保留',
      type: 'warning',
    });
  } catch {
    return;
  }
  beginMutation();
  try {
    await props.request('notes.delete', { id: draft.value.id, version: draft.value.version });
    if (!mounted) return;
    items.value = items.value.filter((n) => n.id !== selectedId.value);
    selectedId.value = '';
    snapshot.value = null;
    draft.value = null;
    if (items.value.length) adopt(items.value[0]);
  } catch (failure) {
    if (mounted) error.value = message(failure);
  } finally {
    if (mounted) busy.value = false;
  }
}
async function verifiedNote() {
  await save();
  const value = await props.request('notes.list');
  const note = value.find((n) => n.id === selectedId.value);
  if (!note) throw new Error('工作单已删除，当前草稿仍保留');
  if (mounted) {
    items.value = value;
    adopt(note);
  }
  return note;
}
async function useNote(action) {
  if (busy.value || !current.value) return;
  beginMutation();
  try {
    const note = await verifiedNote();
    if (!mounted) return;
    if (action === 'business') {
      if (!note.fresh) throw new Error('依据已变化，请先重新核对；工作单仍可导出留档');
      emit('business', noteBusinessDraft(note));
      return;
    }
    downloadNote(note);
    ElMessage.success('已导出工作单，包含版本与原文摘录');
  } catch (failure) {
    if (mounted) error.value = message(failure);
  } finally {
    if (mounted) busy.value = false;
  }
}
function clearForAuthChange() {
  if (discardCheck) {
    cancelDiscardCheck?.();
    ElMessageBox.close();
  }
  mounted = false;
  sequence++;
  items.value = [];
  selectedId.value = '';
  snapshot.value = null;
  draft.value = null;
  newTask.value = '';
  error.value = '';
  loading.value = false;
  busy.value = false;
}
watch(authRevision, () => {
  if (getToken() !== ownerToken) clearForAuthChange();
});
function canLeave() {
  if (getToken() !== ownerToken) {
    clearForAuthChange();
    return true;
  }
  return !mounted || (!busy.value && canSwitch());
}
function beforeUnload(event) {
  if (getToken() !== ownerToken) {
    clearForAuthChange();
    return;
  }
  if (dirty.value) {
    event.preventDefault();
    event.returnValue = '';
  }
}
window.addEventListener('beforeunload', beforeUnload);
const removeRefreshGuard = registerPageRefreshGuard(canLeave);
onBeforeRouteLeave(canLeave);
onBeforeUnmount(() => {
  if (discardCheck) {
    cancelDiscardCheck?.();
    ElMessageBox.close();
  }
  mounted = false;
  sequence++;
  window.removeEventListener('beforeunload', beforeUnload);
  removeRefreshGuard();
});
watch(
  () => props.active,
  (active) => {
    if (active) refresh();
  },
  { immediate: true },
);
defineExpose({ capture, clearForAuthChange });
</script>

<template>
  <section class="review-notebook" aria-label="研判工作单" :aria-busy="loading || busy">
    <aside class="review-list">
      <header>
        <h2>
          我的工作单 <span>{{ items.length }}</span>
        </h2>
        <button type="button" class="icon-action" aria-label="刷新工作单" :disabled="loading || busy" @click="refresh">
          <AppIcon name="refresh" :size="16" />
        </button>
      </header>
      <p v-if="!items.length" class="review-list-empty">把有用的答复存到这里，留下依据和下一步。</p>
      <button
        v-for="note in items"
        :key="note.id"
        type="button"
        class="review-list-item"
        :class="{ active: note.id === selectedId }"
        :aria-pressed="note.id === selectedId"
        :disabled="busy"
        @click="select(note)"
      >
        <strong>{{ note.title }}</strong
        ><span :class="{ stale: !note.fresh }"
          ><AppIcon :name="note.fresh ? 'check' : 'alert'" :size="13" />{{
            note.fresh ? '依据有效' : '需重新核对'
          }}</span
        >
      </button>
      <p class="review-private"><AppIcon name="lock" :size="14" />仅自己可见 · 保存在私有库</p>
    </aside>
    <main class="review-main">
      <p v-if="error" class="source-error" role="alert">
        {{ error }} <button v-if="dirty" type="button" class="text-action" @click="exportDraft">导出当前草稿</button>
      </p>
      <div v-if="!current" class="review-empty">
        <AppIcon name="clipboard" :size="30" />
        <h2>让一次问答，成为下一步的依据。</h2>
        <p>在资料答复下选择“存为工作单”，再补充现场备注和核对项。</p>
      </div>
      <template v-else>
        <header class="review-heading">
          <div>
            <span class="review-fresh" :class="{ stale: !current.fresh }">{{
              current.fresh ? '依据有效' : '需重新核对'
            }}</span
            ><span class="review-save-state" role="status">{{
              busy ? '正在保存…' : dirty ? '有未保存修改' : '已保存'
            }}</span>
          </div>
          <div class="review-actions">
            <el-button :disabled="busy || !dirty" @click="adopt(snapshot)">撤销修改</el-button
            ><el-button type="primary" :loading="busy" :disabled="!dirty" @click="persist">保存工作单</el-button>
          </div>
        </header>
        <p v-if="!current.fresh" class="review-stale" role="status">
          {{ current.reason }}。保留原记录；请核对新版本后再办理业务。
        </p>
        <p v-if="draft.version !== current.version" class="review-stale">
          这份工作单已在别处修改。<button type="button" class="text-action" @click="exportDraft">导出当前草稿</button
          >后再刷新核对。
        </p>
        <label class="sr-only" for="review-title">工作单标题</label
        ><input id="review-title" v-model="draft.title" class="review-title" maxlength="100" :disabled="busy" />
        <div class="review-body">
          <section class="review-answer">
            <h3>资料答复</h3>
            <GroundedAnswer :text="current.answer" :citations="current.citations" @source="emit('source', $event)" />
            <details class="review-sources">
              <summary>{{ current.citations.length }} 处原文依据</summary>
              <button
                v-for="source in current.citations"
                :key="source.id"
                type="button"
                @click="emit('source', source)"
              >
                <span>{{ source.id }} · {{ source.name }}</span
                ><small>v{{ source.version }} · {{ source.locator }}</small>
              </button>
            </details>
          </section>
          <section class="review-personal">
            <h3>
              人工核对 <span>{{ completed }}/{{ draft.tasks.length }}</span>
            </h3>
            <p v-if="!draft.tasks.length" class="review-hint">把需要现场确认的事列在这里。</p>
            <div v-for="(task, i) in draft.tasks" :key="i" class="review-task">
              <label
                ><input v-model="task.done" type="checkbox" :disabled="busy" /><span :class="{ done: task.done }">{{
                  task.text
                }}</span></label
              ><button
                type="button"
                class="icon-action"
                :aria-label="`移除核对项：${task.text}`"
                :disabled="busy"
                @click="draft.tasks.splice(i, 1)"
              >
                <AppIcon name="close" :size="14" />
              </button>
            </div>
            <form class="review-task-form" @submit.prevent="addTask">
              <input
                ref="taskInput"
                v-model="newTask"
                aria-label="新增人工核对项"
                placeholder="添加一个需要确认的事项"
                maxlength="160"
                :disabled="busy || draft.tasks.length >= 20"
              /><button
                type="submit"
                class="icon-action"
                aria-label="添加核对项"
                :disabled="busy || !newTask.trim() || draft.tasks.length >= 20"
              >
                <AppIcon name="plus" :size="18" />
              </button>
            </form>
            <label class="review-comment-label" for="review-comment">现场备注</label
            ><textarea
              id="review-comment"
              v-model="draft.comment"
              maxlength="2000"
              rows="4"
              placeholder="记录你的观察、待确认内容或交接说明…"
              :disabled="busy"
            />
          </section>
        </div>
        <footer class="review-footer">
          <button type="button" class="text-action" :disabled="busy" @click="remove">删除工作单</button>
          <div>
            <el-button :disabled="busy" @click="useNote('export')"
              ><AppIcon name="download" :size="15" />导出交接稿</el-button
            ><el-button :disabled="busy || !current.fresh" @click="useNote('business')"
              >转业务草稿<AppIcon name="arrow" :size="15"
            /></el-button>
          </div>
        </footer>
      </template>
    </main>
  </section>
</template>

<style scoped src="./review-notebook.css"></style>
