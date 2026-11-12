<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppIcon from '@/components/common/AppIcon.vue';
import { aiPageContext } from '@/utils/ai-page-context';
import {
  activeMessages,
  activeQuestion,
  canUndoQuestion,
  cancelSend,
  dockOpen,
  ensureActiveConversation,
  hasQuestionDrafts,
  historyChanging,
  mode,
  sendMessage,
  sending,
  replaceQuestion,
  setDockOpen,
  setMode,
  undoQuestionReplacement,
} from '@/stores/aiConversation';

// 全局悬浮助手：任何业务页面右下角都能唤起，和工作台读同一份 store。
// 两个形态只是同一会话的两种渲染位置，消息只写一次，切形态不会复制或丢失。
const router = useRouter();
const route = useRoute();
const WINDOW_WIDTH = 380;
const WINDOW_HEIGHT = 560;
const EDGE = 12;

// 工作台页面自带完整对话区，右下角的圆形入口会和输入区提示文字重叠，所以这里隐藏。
const triggerHidden = computed(() => route.path.startsWith('/ai/workbench'));
// 私有问答页已有业务助手入口；不让悬浮圆钮遮住小屏输入区。Ctrl+K 仍可打开浮窗。
const privateKnowledgePage = computed(() => route.path === '/ai/knowledge');

// 浮窗是否真的渲染：工作台「工作台形态」下不渲染，否则会浮在输入区上面挡住发送按钮。
// dockOpen 会被持久化，上次停在悬浮形态再回来必须靠 mode 判断兜住。
const panelVisible = computed(() => dockOpen.value && !(triggerHidden.value && mode.value === 'workbench'));

// 浮窗里的消息直接取自当前会话，和工作台完全一致。
const dockMessages = activeMessages;
const dockSending = sending;

const question = activeQuestion;
const composer = ref();
const messageList = ref();
// pinned 为 true 时固定吸附右下角；一旦拖动就转为自由位置，位置限制在视口内。
const pinned = ref(true);
const minimized = ref(false);
const pos = ref({ left: 0, top: 0 });
const unreadHint = ref(false);

// 浮窗里的三个快捷问句覆盖最高频的查询场景。
const quickPrompts = ['L200 工单的报工情况', '本周产量与不良率趋势', '成品仓当前可发库存'];

let dragging = null;

const hasMessages = computed(() => dockMessages.value.length > 0);

// 小屏时按视口收窄，避免浮窗超出屏幕。
const windowStyle = computed(() => {
  const width = `min(92vw, ${WINDOW_WIDTH}px)`;
  if (minimized.value) {
    return pinned.value
      ? { right: `${EDGE}px`, bottom: `${EDGE}px`, width }
      : { left: `${pos.value.left}px`, top: `${pos.value.top}px`, width };
  }
  const height = `min(76vh, ${WINDOW_HEIGHT}px)`;
  return pinned.value
    ? { right: `${EDGE}px`, bottom: `${EDGE}px`, width, height }
    : { left: `${pos.value.left}px`, top: `${pos.value.top}px`, width, height };
});

function openDock() {
  if (triggerHidden.value) setMode('dock');
  setDockOpen(true);
  unreadHint.value = false;
  nextTick(() => {
    scrollToLatest();
    composer.value?.focus?.();
  });
}

function closeDock() {
  setDockOpen(false);
}

function togglePin() {
  // 从自由位置回到钉住时清掉拖动坐标，交给右下角锚点接管。
  pinned.value = !pinned.value;
  if (pinned.value) {
    pos.value = { left: 0, top: 0 };
  }
}

// 拖动只跟随标题栏，松手后位置固定；始终限制在视口内，防止拖出屏幕找不回来。
function startDrag(event) {
  if (event.button !== 0) {
    return;
  }
  const box = event.currentTarget.closest('.ai-dock')?.getBoundingClientRect();
  if (!box) {
    return;
  }
  dragging = {
    startX: event.clientX,
    startY: event.clientY,
    left: box.left,
    top: box.top,
    width: box.width,
    height: box.height,
  };
  pinned.value = false;
  pos.value = { left: box.left, top: box.top };
  window.addEventListener('pointermove', onDrag);
  window.addEventListener('pointerup', endDrag);
  event.preventDefault();
}

function onDrag(event) {
  if (!dragging) {
    return;
  }
  const maxLeft = Math.max(EDGE, window.innerWidth - dragging.width - EDGE);
  const maxTop = Math.max(EDGE, window.innerHeight - dragging.height - EDGE);
  pos.value = {
    left: Math.min(Math.max(EDGE, dragging.left + event.clientX - dragging.startX), maxLeft),
    top: Math.min(Math.max(EDGE, dragging.top + event.clientY - dragging.startY), maxTop),
  };
}

function endDrag() {
  dragging = null;
  window.removeEventListener('pointermove', onDrag);
  window.removeEventListener('pointerup', endDrag);
}

function submit() {
  const content = question.value.trim();
  if (!content || dockSending.value || historyChanging.value) {
    return;
  }
  question.value = '';
  ensureActiveConversation();
  sendMessage(content, { source: 'ai-dock', context: aiPageContext(route) });
}

// 浮窗里 Enter 直接发送，Shift+Enter 换行，和独立工作台保持一致。
function onKeydown(event) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey || event.key !== 'Enter') {
    return;
  }
  event.preventDefault();
  submit();
}

function useQuickPrompt(prompt) {
  if (historyChanging.value) return;
  replaceQuestion(prompt);
  nextTick(() => composer.value?.focus?.());
}

// 命令预览需要完整的前置条件卡片，浮窗放不下，引导用户回工作台确认。
function goWorkbench() {
  setDockOpen(false);
  setMode('workbench');
  router.push('/ai/workbench');
}

function scrollToLatest() {
  nextTick(() => {
    const wrap = messageList.value;
    if (wrap) {
      wrap.scrollTop = wrap.scrollHeight;
    }
  });
}

// 新消息到达时自动滚到底部；浮窗关闭状态下只提示有新回答。
watch(
  () => dockMessages.value.length,
  () => {
    if (dockOpen.value) {
      scrollToLatest();
    } else {
      unreadHint.value = true;
    }
  },
);

// Ctrl/Cmd + K 全局唤起，浏览器占用组合键时不再拦截默认行为。
function onGlobalKeydown(event) {
  if (event.defaultPrevented || event.repeat || event.shiftKey || event.altKey) return;
  if (!(event.ctrlKey || event.metaKey) || String(event.key).toLowerCase() !== 'k') {
    return;
  }
  if ([...document.querySelectorAll('[role="dialog"]:not(.ai-dock)')].some((dialog) => dialog.getClientRects().length))
    return;
  event.preventDefault();
  if (panelVisible.value) {
    closeDock();
  } else {
    openDock();
  }
}

function beforeUnload(event) {
  if (!hasQuestionDrafts.value) return;
  event.preventDefault();
  event.returnValue = '';
}

onMounted(() => {
  window.addEventListener('keydown', onGlobalKeydown);
  window.addEventListener('beforeunload', beforeUnload);
  if (dockOpen.value) {
    scrollToLatest();
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown);
  window.removeEventListener('beforeunload', beforeUnload);
  endDrag();
  cancelSend();
});
</script>

<template>
  <div class="ai-dock-root">
    <!-- 收起状态：右下角圆形入口，仅显示 bot 图标和未读提示点。
         工作台页面已经内置对话区，这里不再显示入口，避免压住底部提示文字。 -->
    <button
      v-if="!panelVisible && !triggerHidden && !privateKnowledgePage"
      class="dock-trigger"
      type="button"
      aria-label="打开 AI 助手（Ctrl+K）"
      aria-keyshortcuts="Control+K Meta+K"
      title="打开 AI 助手（Ctrl+K）"
      @click="openDock"
    >
      <AppIcon name="bot" :size="22" />
      <span v-if="unreadHint" class="dock-dot" />
    </button>

    <section
      v-else-if="panelVisible"
      class="ai-dock"
      :class="{ 'is-minimized': minimized }"
      :style="windowStyle"
      role="dialog"
      aria-label="AI 助手浮窗"
    >
      <!-- 标题栏同时是拖动把手，右侧三个按钮分别是钉住、最小化、关闭。 -->
      <header class="dock-head" @pointerdown="startDrag">
        <span class="dock-avatar"><AppIcon name="bot" :size="13" /></span>
        <div class="dock-title">
          <strong>AI 助手</strong>
          <small>{{ dockSending ? '正在生成…' : 'Ctrl+K 唤起' }}</small>
        </div>
        <div class="dock-tools" @pointerdown.stop>
          <button
            type="button"
            :class="{ 'is-on': pinned }"
            :aria-pressed="pinned ? 'true' : 'false'"
            :title="pinned ? '已钉住右下角' : '未钉住，可拖动'"
            aria-label="钉住浮窗"
            @click="togglePin"
          >
            <AppIcon name="link" :size="13" />
          </button>
          <button
            type="button"
            :title="minimized ? '展开' : '最小化'"
            :aria-label="minimized ? '展开浮窗' : '最小化浮窗'"
            @click="minimized = !minimized"
          >
            <AppIcon :name="minimized ? 'chevron' : 'more'" :size="13" />
          </button>
          <button type="button" title="关闭" aria-label="关闭浮窗" @click="closeDock">
            <AppIcon name="close" :size="13" />
          </button>
        </div>
      </header>

      <template v-if="!minimized">
        <div ref="messageList" class="dock-body">
          <div v-if="!hasMessages" class="dock-welcome">
            <span class="dock-welcome-mark"><AppIcon name="bot" :size="18" /></span>
            <strong>有什么可以帮你查？</strong>
          </div>

          <div v-for="message in dockMessages" :key="message.id" class="dock-msg" :class="`is-${message.role}`">
            <p>{{ message.content }}</p>
            <span v-if="message.blocks?.length" class="dock-msg-meta">
              <AppIcon name="columns" :size="11" />返回 {{ message.blocks.length }} 个结果块
            </span>
            <span v-if="message.command" class="dock-msg-command">
              <AppIcon name="alert" :size="11" />
              含业务命令预览
              <button type="button" @click="goWorkbench">去工作台确认</button>
            </span>
            <span v-if="message.sequence" class="dock-msg-command">
              <AppIcon name="alert" :size="11" />
              含多步办理计划（{{ message.sequence.totalSteps || message.sequence.steps?.length || 0 }} 步）
              <button type="button" @click="goWorkbench">去工作台执行</button>
            </span>
            <span v-if="message.dataGaps?.length" class="dock-msg-gap">
              <AppIcon name="alert" :size="11" />未取到：{{ message.dataGaps.join('、') }}
            </span>
            <span class="dock-msg-time">{{ message.time }}</span>
          </div>

          <div v-if="dockSending" class="dock-thinking">
            <span class="dot" /><span class="dot" /><span class="dot" />
            <span>正在查询业务数据…</span>
          </div>
        </div>

        <footer class="dock-foot">
          <div v-if="!hasMessages" class="dock-prompts">
            <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="useQuickPrompt(prompt)">
              {{ prompt }}
            </button>
          </div>
          <div class="dock-input">
            <textarea
              ref="composer"
              v-model="question"
              rows="1"
              maxlength="500"
              placeholder="输入问题，Enter 发送"
              aria-label="浮窗业务问题"
              :disabled="historyChanging"
              @keydown="onKeydown"
            />
            <button
              v-if="dockSending"
              class="dock-send is-stop"
              type="button"
              aria-label="停止生成"
              @click="cancelSend"
            >
              <AppIcon name="close" :size="13" />
            </button>
            <button
              v-else
              class="dock-send"
              type="button"
              aria-label="发送"
              :disabled="historyChanging || !question.trim()"
              @click="submit"
            >
              <AppIcon name="arrow" :size="13" />
            </button>
          </div>
          <div class="dock-input-options">
            <button v-if="canUndoQuestion" type="button" @click="undoQuestionReplacement">撤销填入</button>
            <p class="dock-hint">Enter 发送 / Shift+Enter 换行</p>
          </div>
        </footer>
      </template>
    </section>
  </div>
</template>

<style scoped>
/* 悬浮助手沿用系统视觉基线：单一主色、1px 描边、小圆角，不使用渐变、发光和玻璃拟态。 */
.ai-dock-root {
  --dock-line: var(--mes-border, var(--mes-border));
  --dock-line-soft: var(--mes-border-light, #eef1f5);
  --dock-bg: var(--mes-surface, #fff);
  --dock-bg-soft: var(--mes-surface-muted, #f7f8fa);
  --dock-text: var(--mes-text, var(--mes-text));
  --dock-text-2: var(--mes-text-secondary, #646a73);
  --dock-text-3: var(--mes-text-muted, var(--mes-text-muted));
  --dock-radius: 8px;
}
.dock-input-options {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dock-input-options > button {
  min-height: 32px;
  padding: 2px 0;
  color: var(--mes-primary);
  border: 0;
  background: transparent;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.dock-trigger {
  position: fixed;
  z-index: 60;
  right: 24px;
  bottom: 24px;
  display: inline-flex;
  width: 52px;
  height: 52px;
  align-items: center;
  justify-content: center;
  color: #fff;
  border: 1px solid var(--mes-primary);
  border-radius: 50%;
  background: var(--mes-primary);
  box-shadow: 0 6px 18px rgb(15 35 60 / 18%);
  cursor: pointer;
  transition:
    background-color 140ms ease,
    transform 140ms ease;
}

.dock-trigger:hover {
  background: var(--mes-primary-hover, #1859c4);
  transform: translateY(-1px);
}
.dock-trigger:active {
  transform: translateY(0);
}

.dock-dot {
  position: absolute;
  top: 9px;
  right: 9px;
  width: 8px;
  height: 8px;
  border: 1.5px solid var(--mes-primary);
  border-radius: 50%;
  background: #fff;
}

.ai-dock {
  position: fixed;
  z-index: 60;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--dock-line);
  border-radius: var(--dock-radius);
  background: var(--dock-bg);
  box-shadow: 0 12px 32px rgb(15 35 60 / 16%);
}

.dock-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
  padding: 9px 8px 9px 10px;
  border-bottom: 1px solid var(--dock-line-soft);
  background: var(--dock-bg);
  cursor: grab;
  user-select: none;
  touch-action: none;
}

.dock-head:active {
  cursor: grabbing;
}

.dock-avatar {
  display: inline-flex;
  width: 24px;
  height: 24px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 6px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.dock-title {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 0;
  line-height: 1.3;
}

.dock-title strong {
  color: var(--dock-text);
  font-size: 12.5px;
  font-weight: 600;
}
.dock-title small {
  color: var(--dock-text-3);
  font-size: 10.5px;
}

.dock-tools {
  display: flex;
  flex: none;
  gap: 2px;
}

.dock-tools button {
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  color: var(--dock-text-2);
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}

.dock-tools button:hover {
  color: var(--dock-text);
  background: var(--dock-bg-soft);
}
.dock-tools button.is-on {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--mes-primary-soft, #eaf2ff);
}

.dock-body {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  overflow-y: auto;
  padding: 12px;
  background: var(--dock-bg-soft);
}

.dock-welcome {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 4px 2px;
}

.dock-welcome-mark {
  display: inline-flex;
  width: 34px;
  height: 34px;
  align-items: center;
  justify-content: center;
  margin-bottom: 2px;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 8px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.dock-welcome strong {
  color: var(--dock-text);
  font-size: 13px;
}
.dock-msg {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-width: 88%;
}

.dock-msg p {
  margin: 0;
  padding: 8px 10px;
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.dock-msg.is-user {
  align-self: flex-end;
  align-items: flex-end;
}

.dock-msg.is-user p {
  color: #fff;
  border: 1px solid var(--mes-primary);
  border-radius: 8px 8px 2px 8px;
  background: var(--mes-primary);
}

.dock-msg.is-assistant p,
.dock-msg.is-system p {
  color: var(--dock-text);
  border: 1px solid var(--dock-line);
  border-radius: 8px 8px 8px 2px;
  background: var(--dock-bg);
}

.dock-msg.is-system p {
  color: var(--mes-danger, #d64545);
  border-color: var(--mes-danger, #d64545);
  background: var(--mes-danger-soft, #fdeeee);
}

.dock-msg-time {
  color: var(--dock-text-3);
  font-size: 10px;
}

.dock-msg-meta,
.dock-msg-command {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--dock-text-3);
  font-size: 10.5px;
}

.dock-msg-command {
  color: var(--mes-warning, #d98a00);
}

.dock-msg-gap {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--mes-warning, #d98a00);
  font-size: 10.5px;
}

.dock-msg-command button {
  padding: 0 2px;
  color: var(--mes-primary);
  font-size: 10.5px;
  border: 0;
  background: none;
  text-decoration: underline;
  cursor: pointer;
}

.dock-thinking {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--dock-text-3);
  font-size: 11px;
}

.dock-thinking .dot {
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--mes-primary);
  animation: dock-blink 1.1s ease-in-out infinite;
}

.dock-thinking .dot:nth-child(2) {
  animation-delay: 0.16s;
}
.dock-thinking .dot:nth-child(3) {
  animation-delay: 0.32s;
}
.dock-thinking span:last-child {
  margin-left: 3px;
}

@keyframes dock-blink {
  0%,
  100% {
    opacity: 0.25;
  }
  50% {
    opacity: 1;
  }
}

.dock-foot {
  flex: none;
  padding: 9px 10px 10px;
  border-top: 1px solid var(--dock-line-soft);
  background: var(--dock-bg);
}

.dock-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-bottom: 8px;
}

.dock-prompts button {
  max-width: 100%;
  overflow: hidden;
  padding: 3px 8px;
  color: var(--dock-text-2);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--dock-line);
  border-radius: 20px;
  background: var(--dock-bg);
  cursor: pointer;
}

.dock-prompts button:hover {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--mes-primary-soft, #eaf2ff);
}

.dock-input {
  display: flex;
  align-items: flex-end;
  gap: 6px;
  padding: 5px 5px 5px 9px;
  border: 1px solid var(--dock-line);
  border-radius: 6px;
  background: var(--dock-bg);
  transition: border-color 140ms ease;
}

.dock-input:focus-within {
  border-color: var(--mes-primary);
}

.dock-input textarea {
  min-height: 20px;
  max-height: 76px;
  flex: 1;
  padding: 2px 0;
  color: var(--dock-text);
  font: inherit;
  font-size: 12.5px;
  line-height: 1.6;
  border: 0;
  background: transparent;
  outline: none;
  resize: none;
}

.dock-input textarea::placeholder {
  color: var(--dock-text-3);
}

.dock-send {
  display: inline-flex;
  width: 26px;
  height: 26px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #fff;
  border: 1px solid var(--mes-primary);
  border-radius: 5px;
  background: var(--mes-primary);
  cursor: pointer;
}

.dock-send:hover:not(:disabled) {
  background: var(--mes-primary-hover, #1859c4);
}
.dock-send:disabled {
  color: var(--dock-text-3);
  border-color: var(--dock-line);
  background: var(--dock-bg-soft);
  cursor: not-allowed;
}
.dock-send.is-stop {
  color: var(--dock-text-2);
  border-color: var(--dock-line);
  background: var(--dock-bg);
}

.dock-hint {
  margin: 6px 0 0;
  color: var(--dock-text-3);
  font-size: 10px;
}

/* 最小化只保留标题栏，宽度不变但高度收到标题栏高度。 */
.ai-dock.is-minimized {
  height: auto !important;
}

@media (max-width: 768px) {
  .dock-trigger {
    right: 16px;
    bottom: 16px;
    width: 46px;
    height: 46px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-dock-root *,
  .ai-dock-root *::before {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
