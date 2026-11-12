/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import { computed, ref, watch } from 'vue';
import dayjs from 'dayjs';
import { chatWithAi } from '@/api/ai';
import {
  createConversation as createRemoteConversation,
  deleteConversation as deleteRemoteConversation,
  getConversationDetail,
  listConversations,
  renameConversation as renameRemoteConversation,
  setConversationArchived,
} from '@/api/ai/conversations';
import { authRevision, getCurrentUser } from '@/utils/auth';
import { sliceUtf16 } from '../utils/text.js';
import { businessResultHistory, commandPreviewHistory, orderResultHistory } from '@/utils/ai-order-context';
import { sanitizeAiPageContext } from '../utils/ai-page-context.js';

/*
 * AI 会话的唯一状态源：工作台和右下角浮窗都只读写这里，切换形态只是换渲染位置，
 * 不再复制消息，因此不会出现「两边各一份、去重靠文字」的问题。
 * 后端可用时以后端会话为准，localStorage 只做首屏兜底和离线缓存。
 */
const CONVERSATIONS_BASE = 'mes_ai_conversations_v2';
const ACTIVE_BASE = 'mes_ai_active_v2';
const MODE_KEY = 'mes_ai_mode_v2';
const DOCK_OPEN_KEY = 'mes_ai_dock_open_v2';
const MAX_CONVERSATIONS = 24;
const MAX_MESSAGES = 60;

// 存储 key 带账号前缀：同一浏览器换账号登录时不会读到上一个人的会话。
function accountScope() {
  try {
    const user = getCurrentUser() || {};
    const raw = user.userId || user.id || user.username || user.userName || '';
    const text = String(raw || '').trim();
    return text ? text.replace(/[^\w-]/g, '_').slice(0, 40) : 'anonymous';
  } catch {
    return 'anonymous';
  }
}

function storageKey(base) {
  return `${base}__${accountScope()}`;
}

function readJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    if (!raw) {
      return fallback;
    }
    const parsed = JSON.parse(raw);
    return parsed ?? fallback;
  } catch {
    return fallback;
  }
}

function writeJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // 存储不可用只影响刷新后的历史，当前会话仍可继续使用。
  }
}

function newId(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function parseJsonField(raw, fallback) {
  if (raw === null || raw === undefined || raw === '') {
    return fallback;
  }
  if (typeof raw !== 'string') {
    return raw;
  }
  try {
    const parsed = JSON.parse(raw);
    return parsed ?? fallback;
  } catch {
    return fallback;
  }
}

function timeText(value) {
  const time = dayjs(value);
  return time.isValid() ? time.format('HH:mm') : '';
}

// 本地会话：id 永远由前端生成，remoteId 只是「已同步到后端」的标记，
// 这样首次发消息把会话写进后端时不用改 id，活动会话也不会跳走。
function createLocalConversation(capabilityId = 'general') {
  const now = dayjs().toISOString();
  return {
    id: newId('conversation'),
    remoteId: null,
    title: '新对话',
    capabilityId,
    createdAt: now,
    updatedAt: now,
    archived: false,
    messages: [],
  };
}

function normalizeLocalConversation(item) {
  return {
    id: item.id || newId('conversation'),
    remoteId: item.remoteId || null,
    title: item.title || '未命名对话',
    capabilityId: item.capabilityId || 'general',
    createdAt: item.createdAt || dayjs().toISOString(),
    updatedAt: item.updatedAt || item.createdAt || dayjs().toISOString(),
    archived: Boolean(item.archived),
    workflowOrderNo: item.workflowOrderNo || '',
    messages: Array.isArray(item.messages) ? item.messages.slice(-MAX_MESSAGES) : [],
  };
}

function loadLocalConversations() {
  const stored = readJson(storageKey(CONVERSATIONS_BASE), []);
  if (!Array.isArray(stored)) {
    return [createLocalConversation()];
  }
  const valid = stored
    .filter((item) => item && typeof item === 'object' && Array.isArray(item.messages))
    .slice(0, MAX_CONVERSATIONS)
    .map(normalizeLocalConversation);
  // 空会话只保留一个草稿，避免反复点「新建对话」堆出一串空记录。
  let draftKept = false;
  const compacted = valid.filter((item) => {
    if (item.messages.length) {
      return true;
    }
    if (item.archived || draftKept) {
      return false;
    }
    draftKept = true;
    return true;
  });
  return compacted.length ? compacted : [createLocalConversation()];
}

export const conversations = ref(loadLocalConversations());
export const activeConversationId = ref(localStorage.getItem(storageKey(ACTIVE_BASE)) || '');
export const sending = ref(false);
export const historyChanging = ref(false);
export const mode = ref(localStorage.getItem(MODE_KEY) === 'dock' ? 'dock' : 'workbench');
export const dockOpen = ref(localStorage.getItem(DOCK_OPEN_KEY) === 'true');
// 后端历史是否已接管：false 时页面顶部提示「当前为本机记录」，避免把缓存当成后台数据。
export const historySynced = ref(false);

let abortController;
let messageRevision = 0;
let sessionRevision = 0;
let ownerScope = accountScope();
const questionDrafts = ref({});
const replacedDrafts = ref({});

export const activeConversation = computed(
  () => conversations.value.find((item) => item.id === activeConversationId.value) || conversations.value[0],
);
export const activeMessages = computed(() => activeConversation.value?.messages || []);
export const hasMessages = computed(() => activeMessages.value.length > 0);

function draftKey(conversation) {
  return conversation?.id || '';
}

export function conversationDraft(conversation) {
  return questionDrafts.value[draftKey(conversation)] || '';
}

// 输入只保留在本次登录的内存中，工作台和浮窗共用，不写入历史或存储。
export const activeQuestion = computed({
  get: () => conversationDraft(activeConversation.value),
  set: (value) => {
    const key = draftKey(ensureActiveConversation());
    const text = sliceUtf16(String(value || ''), 500);
    if (text) questionDrafts.value[key] = text;
    else clearQuestion(activeConversation.value);
  },
});
export const hasQuestionDrafts = computed(() => Object.values(questionDrafts.value).some((value) => value.trim()));
export const canUndoQuestion = computed(() => replacedDrafts.value[draftKey(activeConversation.value)] !== undefined);

export function replaceQuestion(text) {
  const value = sliceUtf16(String(text || ''), 500);
  if (value === activeQuestion.value) return;
  const key = draftKey(ensureActiveConversation());
  if (activeQuestion.value.trim()) replacedDrafts.value[key] = activeQuestion.value;
  activeQuestion.value = value;
}

export function undoQuestionReplacement() {
  const key = draftKey(activeConversation.value);
  if (!Object.hasOwn(replacedDrafts.value, key)) return;
  activeQuestion.value = replacedDrafts.value[key];
  delete replacedDrafts.value[key];
}

function clearQuestion(conversation) {
  const key = draftKey(conversation);
  delete questionDrafts.value[key];
  delete replacedDrafts.value[key];
}

function currentVersion(conversation) {
  return conversations.value.find(
    (item) => item.id === conversation.id || (conversation.remoteId && item.remoteId === conversation.remoteId),
  );
}

async function changeConversation(conversation, remoteChange, applyChange) {
  if (!conversation || sending.value || historyChanging.value) return false;
  const revision = sessionRevision;
  historyChanging.value = true;
  messageRevision++;
  try {
    if (conversation.remoteId) await remoteChange(conversation.remoteId);
    if (revision !== sessionRevision) return false;
    const current = currentVersion(conversation);
    if (!current) return false;
    applyChange(current);
    persistConversations();
    return true;
  } finally {
    if (revision === sessionRevision) {
      messageRevision++;
      historyChanging.value = false;
    }
  }
}

// 今天 / 更早分组，供侧栏会话记录使用。
export const sessionGroups = computed(() => {
  const recent = conversations.value
    .filter((item) => !item.archived && (item.messages.length || item.remoteId || conversationDraft(item).trim()))
    .sort((left, right) => dayjs(right.updatedAt).valueOf() - dayjs(left.updatedAt).valueOf());
  return [
    { key: 'today', label: '今天', items: recent.filter((item) => dayjs(item.updatedAt).isSame(dayjs(), 'day')) },
    { key: 'earlier', label: '更早', items: recent.filter((item) => !dayjs(item.updatedAt).isSame(dayjs(), 'day')) },
  ].filter((group) => group.items.length);
});

export const archivedConversations = computed(() =>
  conversations.value
    .filter((item) => item.archived && (item.messages.length || conversationDraft(item).trim()))
    .sort((left, right) => dayjs(right.updatedAt).valueOf() - dayjs(left.updatedAt).valueOf()),
);

export function persistConversations() {
  writeJson(storageKey(CONVERSATIONS_BASE), conversations.value.slice(0, MAX_CONVERSATIONS));
  try {
    localStorage.setItem(storageKey(ACTIVE_BASE), activeConversationId.value || '');
  } catch {
    // 忽略存储失败。
  }
}

export function setMode(value) {
  mode.value = value === 'dock' ? 'dock' : 'workbench';
  try {
    localStorage.setItem(MODE_KEY, mode.value);
  } catch {
    // 忽略存储失败，只影响刷新后的形态。
  }
}

export function setDockOpen(value) {
  dockOpen.value = Boolean(value);
  try {
    localStorage.setItem(DOCK_OPEN_KEY, String(dockOpen.value));
  } catch {
    // 忽略存储失败。
  }
}

export async function selectConversation(id) {
  const target = conversations.value.find((item) => item.id === id);
  if (!target) {
    return;
  }
  if (target.archived) {
    if (!(await restoreConversation(id))) return false;
  }
  activeConversationId.value = id;
  persistConversations();
  await loadActiveMessages();
  return true;
}

export function ensureActiveConversation(capabilityId = 'general') {
  if (!activeConversation.value) {
    const next = createLocalConversation(capabilityId);
    conversations.value.unshift(next);
    activeConversationId.value = next.id;
  }
  return activeConversation.value;
}

// 只切能力，不动会话：能力现在只是「问句分类」，不再是会话边界。
export function setConversationCapability(capabilityId) {
  const conversation = ensureActiveConversation(capabilityId);
  conversation.capabilityId = capabilityId;
  conversation.updatedAt = dayjs().toISOString();
  persistConversations();
}

export function newConversation(capabilityId = 'general') {
  // 远端列表里的 messages=[] 表示尚未加载，不能当作空白草稿复用。
  messageRevision += 1;
  const draft = conversations.value.find(
    (item) => !item.remoteId && !item.archived && !item.messages.length && !conversationDraft(item).trim(),
  );
  if (draft) {
    clearQuestion(draft);
    draft.capabilityId = capabilityId;
    draft.updatedAt = dayjs().toISOString();
    activeConversationId.value = draft.id;
  } else {
    const next = createLocalConversation(capabilityId);
    conversations.value.unshift(next);
    activeConversationId.value = next.id;
  }
  persistConversations();
}

// 真删除：后端会话一并删除，本地只留一个空草稿，避免历史里残留旧记录。
export function clearActiveConversation() {
  const conversation = ensureActiveConversation();
  const draft = conversationDraft(conversation);
  return changeConversation(conversation, deleteRemoteConversation, (current) => {
    if (conversationDraft(current) === draft) clearQuestion(current);
    current.messages = [];
    current.title = '新对话';
    current.remoteId = null;
    current.updatedAt = dayjs().toISOString();
  });
}

export function removeConversation(id) {
  const conversation = conversations.value.find((item) => item.id === id);
  return changeConversation(conversation, deleteRemoteConversation, (current) => {
    clearQuestion(current);
    conversations.value = conversations.value.filter((item) => item.id !== current.id);
    if (!conversations.value.length) conversations.value.push(createLocalConversation());
    if (activeConversationId.value === current.id) activeConversationId.value = conversations.value[0].id;
  });
}

export function archiveConversation(id) {
  const conversation = conversations.value.find((item) => item.id === id);
  return changeConversation(
    conversation,
    (remoteId) => setConversationArchived(remoteId, true),
    (current) => {
      current.archived = true;
      current.updatedAt = dayjs().toISOString();
      if (activeConversationId.value === current.id) newConversation();
    },
  );
}

export function restoreConversation(id) {
  const conversation = conversations.value.find((item) => item.id === id);
  return changeConversation(
    conversation,
    (remoteId) => setConversationArchived(remoteId, false),
    (current) => {
      current.archived = false;
      current.updatedAt = dayjs().toISOString();
      activeConversationId.value = current.id;
    },
  );
}

// 后端消息行转成页面消息：blocks / suggestions / command 都是 JSON 字符串，
// 解析失败按空处理，不能因为一条历史脏数据把整页打挂。
function remoteRole(value) {
  if (value === 'user') {
    return 'user';
  }
  if (value === 'system') {
    return 'system';
  }
  return 'assistant';
}

function mapRemoteMessage(row) {
  // 历史消息的时间优先用数据查询时间，其次才是落库时间，这样「重新查询」提示才对得上口径。
  const queryTime = row.queryTime || row.createdTime || null;
  return {
    id: `msg-${row.messageId}`,
    remoteId: row.messageId,
    role: remoteRole(row.role),
    content: row.content || '',
    blocks: parseJsonField(row.blocksJson, []),
    suggestions: parseJsonField(row.suggestionsJson, []),
    // 后端会话表暂时没存 actions，读不到就给空数组，气泡里的按钮区不渲染。
    actions: parseJsonField(row.actionsJson, []),
    command: parseJsonField(row.commandJson, null),
    commandId: row.commandId || null,
    // 多步办理的计划快照：有它才能在历史消息里还原多步卡片并按 planId 回读执行状态。
    sequence: parseJsonField(row.sequenceJson, null),
    intent: row.intent || null,
    model: row.model || null,
    queryTime,
    time: timeText(queryTime),
    fromHistory: true,
  };
}

function mapRemoteConversation(record) {
  const updated = record.lastMessageTime || record.updatedTime || record.createdTime;
  return {
    id: `conversation-remote-${record.conversationId}`,
    remoteId: record.conversationId,
    title: record.title || '未命名对话',
    capabilityId: record.capability || 'general',
    createdAt: record.createdTime || dayjs().toISOString(),
    updatedAt: updated || record.createdTime || dayjs().toISOString(),
    archived: Number(record.archived) === 1,
    messages: [],
  };
}

async function recentRemoteMessages(remoteId, response) {
  const data = response?.data || {};
  let rows = Array.isArray(data.messages) ? data.messages : [];
  // 服务端按时间正序分页；首页是最早消息，长对话恢复必须取末页。
  const lastPage = Math.max(1, Number(data.pages) || 1);
  if (lastPage > 1) {
    const latest = await getConversationDetail(remoteId, { current: lastPage, size: 100 });
    rows = Array.isArray(latest?.data?.messages) ? latest.data.messages : [];
    if (rows.length < MAX_MESSAGES) {
      const previous =
        lastPage === 2 ? response : await getConversationDetail(remoteId, { current: lastPage - 1, size: 100 });
      rows = [...(Array.isArray(previous?.data?.messages) ? previous.data.messages : []), ...rows];
    }
  }
  return rows;
}

// 拉当前会话的消息；失败时保留本地缓存，页面照常可用。
export async function loadActiveMessages() {
  const conversation = activeConversation.value;
  if (!conversation?.remoteId || sending.value || historyChanging.value) {
    return false;
  }
  const revision = messageRevision;
  try {
    const response = await getConversationDetail(conversation.remoteId, { current: 1, size: 100 });
    if (sending.value || revision !== messageRevision || activeConversation.value !== conversation) {
      return false;
    }
    const rows = await recentRemoteMessages(conversation.remoteId, response);
    if (sending.value || revision !== messageRevision || activeConversation.value !== conversation) {
      return false;
    }
    if (!rows.length) {
      return false;
    }
    conversation.messages = rows.map(mapRemoteMessage).slice(-MAX_MESSAGES);
    persistConversations();
    return true;
  } catch {
    return false;
  }
}

// 首屏先用本地缓存渲染，再尝试让后端接管会话列表与消息。
export async function syncFromBackend() {
  if (sending.value || historyChanging.value) {
    return false;
  }
  const revision = messageRevision;
  try {
    const response = await listConversations({ current: 1, size: 30, archived: false });
    if (sending.value || revision !== messageRevision) {
      return false;
    }
    const records = Array.isArray(response?.data?.records) ? response.data.records : [];
    const loaded = new Map(conversations.value.filter((item) => item.remoteId).map((item) => [item.remoteId, item]));
    const remote = records.map((record) => {
      const mapped = mapRemoteConversation(record);
      const previous = loaded.get(record.conversationId);
      if (previous) mapped.id = previous.id;
      if (previous?.messages?.length) {
        mapped.messages = previous.messages;
        mapped.workflowOrderNo = previous.workflowOrderNo || '';
      }
      return mapped;
    });
    // 本地尚未同步到后端的会话（接口不可用时创建的）要留着，否则内容会凭空消失。
    const remoteIds = new Set(remote.map((item) => item.remoteId));
    const localOnly = conversations.value.filter(
      (item) =>
        (!item.remoteId &&
          (item.messages.length || item.id === activeConversationId.value || conversationDraft(item).trim())) ||
        (item.remoteId && !remoteIds.has(item.remoteId) && (item.archived || conversationDraft(item).trim())),
    );
    if (remote.length || localOnly.length) {
      const previousId = activeConversationId.value;
      const previousRemote = conversations.value.find((item) => item.id === previousId)?.remoteId;
      const merged = [...localOnly, ...remote];
      const withDrafts = merged.filter((item) => conversationDraft(item).trim());
      const withoutDrafts = merged.filter((item) => !conversationDraft(item).trim());
      conversations.value = [
        ...withDrafts,
        ...withoutDrafts.slice(0, Math.max(0, MAX_CONVERSATIONS - withDrafts.length)),
      ];
      const matched = conversations.value.find(
        (item) => item.id === previousId || (previousRemote && item.remoteId === previousRemote),
      );
      activeConversationId.value = matched?.id || conversations.value[0]?.id || '';
      persistConversations();
    }
    historySynced.value = remote.length > 0;
    await loadActiveMessages();
    return historySynced.value;
  } catch {
    historySynced.value = false;
    return false;
  }
}

// 首次发消息时把会话登记到后端，失败则本次仍按本地会话继续，不阻断提问。
async function ensureRemoteConversation(conversation) {
  if (conversation.remoteId) {
    return conversation.remoteId;
  }
  try {
    const response = await createRemoteConversation({
      title: conversation.title === '新对话' ? undefined : conversation.title,
      capability: conversation.capabilityId,
    });
    const record = response?.data || {};
    if (record.conversationId) {
      conversation.remoteId = record.conversationId;
      return record.conversationId;
    }
  } catch {
    // 后端会话接口不可用：本轮问答仍然照常发出，只是不落库。
  }
  return null;
}

function historyPayload(messages) {
  return messages
    .filter((item) => ['user', 'assistant'].includes(item.role) && item.content)
    .slice(-20)
    .map((item) => ({
      role: item.role,
      content: [
        commandPreviewHistory(item),
        orderResultHistory(item),
        businessResultHistory(item),
        String(item.content),
      ]
        .filter(Boolean)
        .join('\n')
        .slice(0, 2000),
    }));
}

function resolveErrorMessage(error) {
  const detail = error?.data?.message;
  if (typeof detail === 'string' && detail.trim()) {
    return detail.trim();
  }
  if (typeof error?.displayMessage === 'string' && error.displayMessage.trim()) {
    return error.displayMessage.trim();
  }
  return '本次分析未完成，请稍后重试。';
}

function appendUserMessage(conversation, text, clientMessageId, requestContext) {
  messageRevision += 1;
  const sentAt = dayjs();
  conversation.messages.push({
    id: newId('message'),
    role: 'user',
    content: text,
    // 后端按这个标识判重：同一条提问重试时复用同一个值，历史里就不会出现两条一样的提问。
    clientMessageId,
    requestContext,
    time: sentAt.format('HH:mm'),
  });
  if (conversation.title === '新对话') {
    conversation.title = text.slice(0, 24);
  }
  conversation.updatedAt = sentAt.toISOString();
}

function appendAnswer(conversation, answer) {
  messageRevision += 1;
  conversation.messages.push({
    id: newId('message'),
    role: 'assistant',
    content: answer.answer || '暂时没有生成可用内容。',
    blocks: Array.isArray(answer.blocks) ? answer.blocks : [],
    suggestions: Array.isArray(answer.suggestions) ? answer.suggestions : [],
    // 后端在回答里带了操作入口，落进消息对象，气泡下方才能渲染按钮。
    actions: Array.isArray(answer.actions) ? answer.actions : [],
    disclaimer: answer.disclaimer || '智能建议，仅供参考；关键业务操作需人工确认。',
    model: answer.model || null,
    intent: answer.intent || null,
    command: answer.command || null,
    commandId: answer.command?.commandId || null,
    // 一句话连着办几件事时，这里带着 planId 和各步前置检查结果，
    // 工作台据此渲染多步卡片；丢了它这条回答就只剩一段文字。
    sequence: answer.sequence || null,
    // 这次没读到的业务数据项，气泡下方要明确提示，不能让用户以为结论覆盖了全部模块。
    dataGaps: Array.isArray(answer.businessDataGaps) ? answer.businessDataGaps : [],
    queryTime: answer.generatedAt || dayjs().toISOString(),
    time: dayjs(answer.generatedAt || undefined).format('HH:mm'),
  });
  // 后端会把标题留在会话记录里，这里同步一次，刷新后侧栏标题才和后台一致。
  if (conversation.remoteId && conversation.messages.length === 2) {
    renameRemoteConversation(conversation.remoteId, conversation.title).catch(() => {});
  }
}

function appendFailure(conversation, error, text, clientMessageId) {
  // 用户主动取消不算失败，不往会话里塞一条错误消息。
  if (error.code === 'ERR_CANCELED') {
    return;
  }
  messageRevision += 1;
  conversation.messages.push({
    id: newId('message'),
    role: 'system',
    content: resolveErrorMessage(error),
    retryQuestion: text,
    // 重试要复用提问的标识，后端才知道这是同一条提问的再次尝试。
    retryClientMessageId: clientMessageId,
    time: dayjs().format('HH:mm'),
  });
}

// 追加一条「办理预览」消息：气泡按钮点开后由工作台调用，卡片仍由 AiCommandCard 渲染，
// 用户不点确认就不会有任何业务动作。
export function appendCommandMessage(command, options = {}) {
  if (historyChanging.value) return false;
  if (!command) {
    return false;
  }
  messageRevision += 1;
  const conversation = ensureActiveConversation(options.capabilityId || 'general');
  conversation.messages.push({
    id: newId('message'),
    role: 'assistant',
    content:
      options.content || `已生成「${command.actionLabel || command.action || '业务办理'}」的预览，确认后才执行。`,
    blocks: [],
    suggestions: [],
    actions: [],
    disclaimer: options.disclaimer || '智能建议，仅供参考；关键业务操作需人工确认。',
    model: null,
    intent: null,
    command,
    commandId: command.commandId || null,
    queryTime: dayjs().toISOString(),
    time: dayjs().format('HH:mm'),
  });
  conversation.updatedAt = dayjs().toISOString();
  persistConversations();
  return true;
}

// 发送一轮问答：工作台和浮窗共用，消息只在这里追加一次。
// options.clientMessageId 由重试入口传入：同一条提问重试时必须复用原标识，
// 否则后端会把它当成新提问再落一条库。
export async function sendMessage(content, options = {}) {
  const text = String(content || '').trim();
  if (!text || sending.value || historyChanging.value) {
    return false;
  }
  const capabilityId = options.capabilityId || 'general';
  const conversation = ensureActiveConversation(capabilityId);
  const revision = sessionRevision;
  if (!options.preserveDraft) clearQuestion(conversation);
  const clientMessageId = options.clientMessageId || newId('client');
  const previousRequest = options.clientMessageId
    ? [...conversation.messages]
        .reverse()
        .find((item) => item.role === 'user' && item.clientMessageId === clientMessageId)
    : null;
  const requestContext = sanitizeAiPageContext(previousRequest?.requestContext ?? options.context);
  appendUserMessage(conversation, text, clientMessageId, requestContext);
  sending.value = true;
  persistConversations();

  const controller = new AbortController();
  abortController = controller;
  try {
    const remoteId = await ensureRemoteConversation(conversation);
    if (revision !== sessionRevision || controller.signal.aborted) return false;
    const result = await chatWithAi(
      {
        question: text,
        messages: historyPayload(conversation.messages.slice(0, -1)),
        module: options.module || 'general',
        conversationId: remoteId || undefined,
        clientMessageId,
        context: { ...requestContext, source: options.source || 'ai-workbench', capability: capabilityId },
      },
      controller.signal,
    );
    if (revision === sessionRevision) appendAnswer(conversation, result.data || {});
  } catch (error) {
    if (revision === sessionRevision) appendFailure(conversation, error, text, clientMessageId);
  } finally {
    if (revision === sessionRevision) {
      conversation.updatedAt = dayjs().toISOString();
      sending.value = false;
      if (abortController === controller) abortController = null;
      persistConversations();
    }
  }
  return true;
}

export function cancelSend() {
  abortController?.abort();
}

// 重新生成：找到这条回答前面最近的一条用户提问，重新走一遍发送流程。
export function regenerateMessage(messageId) {
  if (sending.value) {
    return false;
  }
  const messages = activeMessages.value;
  const index = messages.findIndex((item) => item.id === messageId);
  const previousQuestion = [...messages.slice(0, index < 0 ? messages.length : index)]
    .reverse()
    .find((item) => item.role === 'user')?.content;
  if (!previousQuestion) {
    return false;
  }
  return previousQuestion;
}

// 退出登录时清掉所有 AI 相关缓存（由 auth.js 调用，避免把上一账号的记录带给下一账号）。
export function resetAiStorage() {
  try {
    const keys = [];
    for (let index = 0; index < localStorage.length; index += 1) {
      const key = localStorage.key(index);
      if (key && key.startsWith('mes_ai_')) {
        keys.push(key);
      }
    }
    keys.forEach((key) => localStorage.removeItem(key));
  } catch {
    // 忽略清理失败。
  }
  resetMemory();
}

function resetMemory() {
  sessionRevision++;
  messageRevision++;
  abortController?.abort();
  abortController = null;
  questionDrafts.value = {};
  replacedDrafts.value = {};
  conversations.value = [createLocalConversation()];
  activeConversationId.value = conversations.value[0].id;
  sending.value = false;
  historyChanging.value = false;
  historySynced.value = false;
  dockOpen.value = false;
  mode.value = 'workbench';
}

const stopAuthWatch = watch(
  authRevision,
  () => {
    const nextScope = accountScope();
    if (ownerScope === nextScope) return;
    ownerScope = nextScope;
    resetMemory();
    conversations.value = loadLocalConversations();
    activeConversationId.value = conversations.value[0]?.id || '';
    try {
      activeConversationId.value = localStorage.getItem(storageKey(ACTIVE_BASE)) || activeConversationId.value;
    } catch {
      // 当前会话在内存中继续使用。
    }
  },
  { flush: 'sync' },
);
if (import.meta.hot) import.meta.hot.dispose(stopAuthWatch);
