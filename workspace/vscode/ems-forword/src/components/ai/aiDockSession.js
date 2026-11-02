/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import { computed, ref } from 'vue';
import dayjs from 'dayjs';
import { chatWithAi } from '@/api/ai';

// 悬浮窗和工作台共用同一份会话：都用这个模块读写 sessionStorage，
// 切换形态时不复制消息，只切换渲染位置，因此不会出现两份不一致的记录。
const STORAGE_KEY = 'mes_ai_dock_messages';
const OPEN_KEY = 'mes_ai_dock_open';
const MODE_KEY = 'mes_ai_dock_mode';
const MAX_MESSAGES = 30;

function readMessages() {
  try {
    const stored = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '[]');
    if (!Array.isArray(stored)) {
      return [];
    }
    return stored
      .filter((item) => item && typeof item === 'object' && typeof item.content === 'string')
      .slice(-MAX_MESSAGES)
      .map((item) => ({ ...item, role: item.role === 'user' ? 'user' : item.role || 'assistant' }));
  } catch {
    return [];
  }
}

// 单例状态：多个组件引用同一组 ref，任一组件写入，其他组件立即同步。
export const dockMessages = ref(readMessages());
export const dockSending = ref(false);
export const dockOpen = ref(localStorage.getItem(OPEN_KEY) === 'true');
export const dockMode = ref(localStorage.getItem(MODE_KEY) === 'dock' ? 'dock' : 'workbench');

let abortController;
let seq = 0;

export function persistDockMessages() {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(dockMessages.value.slice(-MAX_MESSAGES)));
  } catch {
    // 浏览器存储不可用时，当前会话仍可继续使用，只是刷新后丢失。
  }
}

function nextId(prefix) {
  seq += 1;
  return `${prefix}-${Date.now()}-${seq}`;
}

export function setDockOpen(value) {
  dockOpen.value = Boolean(value);
  try {
    localStorage.setItem(OPEN_KEY, String(dockOpen.value));
  } catch {
    // 忽略存储失败，仅影响刷新后的展开状态。
  }
}

export function setDockMode(value) {
  dockMode.value = value === 'dock' ? 'dock' : 'workbench';
  try {
    localStorage.setItem(MODE_KEY, dockMode.value);
  } catch {
    // 忽略存储失败。
  }
}

export function clearDockMessages() {
  dockMessages.value = [];
  persistDockMessages();
}

// 把工作台的会话消息并入悬浮窗会话，保留顺序并去重，避免同一句被重复追加。
export function mergeIntoDock(messages = []) {
  const existing = new Set(dockMessages.value.map((item) => `${item.role}|${item.content}`));
  const incoming = messages
    .filter((item) => item && item.content && ['user', 'assistant'].includes(item.role))
    .map((item) => ({
      id: item.id || nextId('dock'),
      role: item.role,
      content: item.content,
      time: item.time || dayjs(item.generatedAt || undefined).format('HH:mm'),
      blocks: Array.isArray(item.blocks) ? item.blocks : [],
      suggestions: Array.isArray(item.suggestions) ? item.suggestions : [],
      intent: item.intent,
      command: item.command || null,
      sequence: item.sequence || null,
      model: item.model,
    }))
    .filter((item) => !existing.has(`${item.role}|${item.content}`));
  if (!incoming.length) {
    return;
  }
  dockMessages.value = [...dockMessages.value, ...incoming].slice(-MAX_MESSAGES);
  persistDockMessages();
}

export const dockBusy = computed(() => dockSending.value);

// 发送一条消息：立即写入用户气泡，再追加助手回答或失败提示。
export async function sendDockMessage(text) {
  const content = String(text || '').trim();
  if (!content || dockSending.value) {
    return;
  }
  dockMessages.value.push({ id: nextId('dock'), role: 'user', content, time: dayjs().format('HH:mm') });
  persistDockMessages();
  dockSending.value = true;
  abortController = new AbortController();
  try {
    const result = await chatWithAi(
      {
        question: content,
        messages: aiHistoryPayload(dockMessages.value.slice(0, -1)),
        module: 'general',
        context: { source: 'ai-dock', capability: 'general' },
      },
      abortController.signal,
    );
    const answer = result.data || {};
    dockMessages.value.push({
      id: nextId('dock'),
      role: 'assistant',
      content: answer.answer || '暂时没有生成可用内容。',
      blocks: Array.isArray(answer.blocks) ? answer.blocks : [],
      suggestions: Array.isArray(answer.suggestions) ? answer.suggestions : [],
      intent: answer.intent,
      command: answer.command || null,
      sequence: answer.sequence || null,
      // 这次没读到的业务数据项，浮窗里也要说清缺什么。
      dataGaps: Array.isArray(answer.businessDataGaps) ? answer.businessDataGaps : [],
      model: answer.model,
      time: dayjs().format('HH:mm'),
    });
  } catch (error) {
    if (error.code !== 'ERR_CANCELED') {
      dockMessages.value.push({
        id: nextId('dock'),
        role: 'system',
        content: error?.displayMessage || '本次请求未完成，请稍后重试。',
        time: dayjs().format('HH:mm'),
      });
    }
  } finally {
    dockSending.value = false;
    abortController = null;
    persistDockMessages();
  }
}

// 只取最近 20 条对话作为上下文，和独立工作台保持一致的裁剪口径。
function aiHistoryPayload(messages) {
  return messages
    .filter((item) => ['user', 'assistant'].includes(item.role) && item.content)
    .slice(-20)
    .map((item) => ({ role: item.role, content: String(item.content).slice(0, 2000) }));
}

export function cancelDockMessage() {
  abortController?.abort();
}
