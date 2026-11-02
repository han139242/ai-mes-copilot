<script setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import { previewAgentCommand } from '@/api/ai/agent';

/*
 * 回答下方的操作入口：后端在回答里给出 actions 数组，
 * 查看类直接跳页面，办理类先拉一份预览、由父组件插入命令卡片，用户点确认才真正执行。
 */
const props = defineProps({
  actions: { type: Array, default: () => [] },
  // 预览时带上会话上下文，后端据此把命令记录挂到同一条会话上。
  conversationId: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
});
const emit = defineEmits(['preview', 'error', 'need-candidates']);

const router = useRouter();
const pendingCode = ref('');

// 空数组时不占位：既没有边框也没有间距，气泡看起来和以前一样。
const visibleActions = computed(() => (Array.isArray(props.actions) ? props.actions.filter(Boolean) : []));

function kindOf(action) {
  if (action?.kind === 'view' || action?.kind === 'command') {
    return action.kind;
  }
  // 老数据没有 kind：有路由按查看处理，有动作编码按办理处理。
  return action?.route ? 'view' : 'command';
}

function actionKey(action, index) {
  return `${action?.code || action?.label || 'action'}-${index}`;
}

// 预览接口只收一句自然语言，所以用「动作名 + 单号」拼话术，例如「开始生产 WO-2026…」。
function previewText(action) {
  const params = action?.params || {};
  const targetNo = String(params.targetNo || params.bizNo || '').trim();
  const label = String(action?.label || '').trim();
  if (targetNo) {
    return `${label} ${targetNo}`;
  }
  return label;
}

// 没带单号时不硬凑预览：后端拿不到目标单据只会给出「未找到目标单据」，
// 改成弹出候选列表让用户自己挑一张。
function targetNoOf(action) {
  const params = action?.params || {};
  return String(params.targetNo || params.bizNo || '').trim();
}

async function handleClick(action, index) {
  if (kindOf(action) === 'view') {
    openRoute(action);
    return;
  }
  if (props.disabled || pendingCode.value) {
    return;
  }
  if (!targetNoOf(action)) {
    emit('need-candidates', {
      capability: action?.params?.action || action?.code || '',
      actionLabel: action?.label || '',
    });
    return;
  }
  await runPreview(action, index);
}

// 拿预览：成功就交给父组件插卡片，失败只在这一排按钮上报一句。
async function runPreview(action, index) {
  const text = previewText(action);
  if (!text) {
    return;
  }
  pendingCode.value = actionKey(action, index);
  try {
    const command = await requestPreview(text, action);
    if (!command.confirmationId) {
      emit('error', command.message || '没能生成办理预览，请到对应页面手工办理。');
      return;
    }
    emit('preview', command);
  } catch (error) {
    emit('error', error?.displayMessage || error?.data?.message || '办理预览暂不可用，请稍后重试。');
  } finally {
    pendingCode.value = '';
  }
}

// 跳转时把定位参数一并带上，例如 focus=<主键>、tab=runs&runId=77，
// 否则只能落到列表首页，用户还得自己再搜一遍。
function openRoute(action) {
  if (!action?.route) {
    return;
  }
  const query = {};
  Object.entries(action.params || {}).forEach(([key, value]) => {
    const text = value === null || value === undefined ? '' : String(value).trim();
    if (text) {
      query[key] = text;
    }
  });
  if (Object.keys(query).length) {
    router.push({ path: action.route, query });
    return;
  }
  router.push(action.route);
}

async function requestPreview(text, action) {
  const response = await previewAgentCommand({
    text,
    conversationId: props.conversationId || undefined,
    source: 'WORKBENCH',
    context: { capability: action?.params?.action || action?.code || '' },
  });
  return response?.data || {};
}
</script>

<template>
  <div v-if="visibleActions.length" class="ai-action-buttons">
    <button
      v-for="(action, index) in visibleActions"
      :key="actionKey(action, index)"
      class="action-button"
      type="button"
      :class="{ 'is-view': kindOf(action) === 'view' }"
      :disabled="disabled || Boolean(pendingCode)"
      @click="handleClick(action, index)"
    >
      <AppIcon :name="kindOf(action) === 'view' ? 'eye' : 'play'" :size="12" />
      <span>{{ action.label || (kindOf(action) === 'view' ? '查看' : '办理') }}</span>
      <span v-if="kindOf(action) === 'command' && targetNoOf(action)">{{ targetNoOf(action) }}</span>
      <span v-if="pendingCode === actionKey(action, index)" class="action-loading">…</span>
    </button>
  </div>
</template>

<style scoped>
/* 一排次要按钮：描边样式，比正文小一号，不抢正文的注意力。 */
.ai-action-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.action-button {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  overflow: hidden;
  color: var(--mes-primary, #3370ff);
  font-size: 11.5px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 4px;
  background: var(--mes-surface, #fff);
  cursor: pointer;
  transition:
    background-color 140ms ease,
    border-color 140ms ease;
}

.action-button:hover:not(:disabled) {
  background: var(--mes-primary-soft, #eaf2ff);
}

.action-button:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/* 查看类用中性色，和办理类区分开，避免用户误以为都会改数据。 */
.action-button.is-view {
  color: var(--mes-text-secondary, #646a73);
  border-color: var(--mes-border, #e5e7eb);
}

.action-button.is-view:hover:not(:disabled) {
  color: var(--mes-primary, #3370ff);
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--mes-surface-muted, #f7f8fa);
}

.action-loading {
  color: var(--mes-text-muted, #8f959e);
}
</style>
