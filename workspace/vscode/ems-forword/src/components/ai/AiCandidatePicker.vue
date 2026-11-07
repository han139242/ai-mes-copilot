<script setup>
import { computed, ref, watch } from 'vue';
import AppIcon from '@/components/common/AppIcon.vue';
import { getAgentCandidates } from '@/api/ai/agent';

/*
 * 候选单据选择器：用户说「帮我确认入库」但没报单号时，不要让 AI 猜一张单去办，
 * 先把当前能办的单据列出来（含能否办理与阻断原因），由用户自己挑一张再走预览。
 */
const props = defineProps({
  // 打开时带上动作编码与按钮文案，为空表示不显示。
  capability: { type: String, default: '' },
  actionLabel: { type: String, default: '' },
});
const emit = defineEmits(['pick', 'close']);

const visible = computed(() => Boolean(props.capability));
const loading = ref(false);
const errorMessage = ref('');
const candidates = ref([]);
const pickedNo = ref('');

async function load() {
  candidates.value = [];
  errorMessage.value = '';
  pickedNo.value = '';
  if (!props.capability) {
    return;
  }
  loading.value = true;
  try {
    const response = await getAgentCandidates({ capability: props.capability });
    const data = response?.data || {};
    candidates.value = Array.isArray(data.candidates) ? data.candidates : [];
    if (!candidates.value.length) {
      errorMessage.value = '当前没有可办理的单据。';
    }
  } catch (error) {
    errorMessage.value = error?.displayMessage || error?.data?.message || '候选单据加载失败，请稍后重试。';
  } finally {
    loading.value = false;
  }
}

// 打开或换动作时重新拉一次，避免复用上一次的候选列表。
watch(() => props.capability, load, { immediate: true });

function pick(candidate) {
  if (!candidate || pickedNo.value) {
    return;
  }
  pickedNo.value = candidate.bizNo;
  emit('pick', { capability: props.capability, actionLabel: props.actionLabel, candidate });
}

function close() {
  emit('close');
}

// 阻断原因按后端给的中文原样展示，不在这里二次改写口径。
function blockingText(candidate) {
  return String(candidate?.blockingReason || '').trim();
}
</script>

<template>
  <div v-if="visible" class="candidate-mask" @click.self="close">
    <div class="candidate-panel">
      <header class="candidate-head">
        <div class="candidate-title">
          <AppIcon name="list" :size="14" />
          <span>{{ actionLabel || '选择单据' }}</span>
        </div>
        <button class="candidate-close" type="button" @click="close">
          <AppIcon name="close" :size="12" />
        </button>
      </header>

      <p v-if="candidates.length" class="candidate-tip">共 {{ candidates.length }} 张。</p>

      <div v-if="loading" class="candidate-state">正在加载…</div>
      <div v-else-if="errorMessage" class="candidate-state is-error">{{ errorMessage }}</div>
      <ul v-else class="candidate-list">
        <li v-for="item in candidates" :key="item.bizNo" class="candidate-item">
          <button
            class="candidate-row"
            type="button"
            :disabled="!item.canExecute || Boolean(pickedNo)"
            @click="pick(item)"
          >
            <span class="candidate-no">{{ item.bizNo }}</span>
            <span class="candidate-label">{{ item.label || item.detail || '' }}</span>
            <span v-if="item.status" class="candidate-status">{{ item.status }}</span>
            <span v-if="!item.canExecute" class="candidate-block" :title="blockingText(item)">
              {{ blockingText(item) || '当前不可办理' }}
            </span>
            <span v-else-if="pickedNo === item.bizNo" class="candidate-loading">处理中…</span>
          </button>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.candidate-mask {
  position: fixed;
  z-index: 2600;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgb(15 23 42 / 32%);
  inset: 0;
}

.candidate-panel {
  display: flex;
  flex-direction: column;
  width: min(560px, 100%);
  max-height: 70vh;
  overflow: hidden;
  border-radius: 8px;
  background: var(--mes-surface, #fff);
  box-shadow: 0 12px 32px rgb(15 23 42 / 18%);
}

.candidate-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--mes-border, var(--mes-border));
}

.candidate-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--mes-text-primary, var(--mes-text));
  font-size: 13px;
  font-weight: 600;
}

.candidate-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: var(--mes-text-muted, var(--mes-text-muted));
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}

.candidate-close:hover {
  background: var(--mes-surface-muted, #f7f8fa);
}

.candidate-tip {
  margin: 0;
  padding: 8px 14px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 11.5px;
  line-height: 18px;
  background: var(--mes-surface-muted, #f7f8fa);
}

.candidate-state {
  padding: 20px 14px;
  color: var(--mes-text-secondary, #646a73);
  font-size: 12px;
  text-align: center;
}

.candidate-state.is-error {
  color: var(--mes-danger, #d83931);
}

.candidate-list {
  flex: 1;
  margin: 0;
  padding: 6px;
  overflow-y: auto;
  list-style: none;
}

.candidate-item + .candidate-item {
  margin-top: 4px;
}

.candidate-row {
  display: grid;
  width: 100%;
  padding: 8px 10px;
  color: inherit;
  font-size: 12px;
  text-align: left;
  border: 1px solid var(--mes-border, var(--mes-border));
  border-radius: 6px;
  background: var(--mes-surface, #fff);
  cursor: pointer;
  gap: 2px 8px;
  grid-template-columns: auto 1fr auto;
}

.candidate-row:hover:not(:disabled) {
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--mes-primary-soft, #eaf2ff);
}

.candidate-row:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.candidate-no {
  color: var(--mes-text-primary, var(--mes-text));
  font-weight: 600;
  white-space: nowrap;
}

.candidate-label {
  overflow: hidden;
  color: var(--mes-text-secondary, #646a73);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.candidate-status {
  color: var(--mes-text-muted, var(--mes-text-muted));
  white-space: nowrap;
}

/* 阻断原因单独占一行，避免和单号挤在一起看不全。 */
.candidate-block {
  overflow: hidden;
  color: var(--mes-warning, #d97706);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  grid-column: 1 / -1;
}

.candidate-loading {
  color: var(--mes-text-muted, var(--mes-text-muted));
  grid-column: 1 / -1;
}
</style>
