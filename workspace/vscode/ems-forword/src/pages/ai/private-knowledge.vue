<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import GroundedAnswer from '@/components/ai/GroundedAnswer.vue';
import KnowledgeGraph from '@/components/ai/KnowledgeGraph.vue';
import ReviewNotebook from '@/components/ai/ReviewNotebook.vue';
import ScanImportDialog from '@/components/ai/ScanImportDialog.vue';
import DeepSeekToolsDialog from '@/components/ai/DeepSeekToolsDialog.vue';
import { sliceUtf16 } from '@/utils/text';
import { privateAction as dispatchPrivate, uploadPrivateFile } from '@/api/ai/private';
import {
  historySessions,
  orderedAnswerCitations,
  knowledgeModes,
  knowledgeGenerationState,
  knowledgePreferencesSnapshot,
  knowledgePreferencesChanged,
  knowledgeState,
  savePrivateDownload,
  validateKnowledgeFile,
} from '@/utils/private-knowledge';
import { authRevision, getToken, isFrameworkPreview } from '@/utils/auth';

const router = useRouter(),
  preview = isFrameworkPreview();
const tab = ref('ask'),
  sideTab = ref('documents'),
  filter = ref(''),
  sourcePanel = ref(null);
const filesCollapsed = ref(true);
const scanOpen = ref(false);
const scanDocument = ref(null);
const toolsOpen = ref(false);
const toolsMode = ref('web');
const spokenAnswer = ref('');
const spokenHistoryId = ref(null);
const notebook = ref(),
  notesOpened = ref(false);
const expandedSources = ref(new Set());
const dragDepth = ref(0);
const filesInput = ref(),
  replaceInput = ref(),
  replaceTarget = ref(null),
  questionInput = ref(),
  jobsPanel = ref(),
  thread = ref();
const documents = ref([]),
  jobs = ref([]),
  histories = ref([]),
  service = ref(null),
  budget = ref(null);
const loading = ref(false),
  uploading = ref(false),
  sending = ref(false),
  historyClearing = ref(false),
  graphLoading = ref(false),
  prefsBusy = ref(false);
const serviceError = ref(''),
  actionError = ref(''),
  graphError = ref(''),
  sourceError = ref(''),
  sourceLoading = ref(false);
const messages = ref([]),
  question = ref(''),
  mode = ref('auto'),
  session = ref(crypto.randomUUID());
const replacedQuestion = ref(null),
  previousConversation = ref(null);
const canReturnPrevious = computed(
  () => Boolean(previousConversation.value) && !messages.value.length && !question.value.trim() && !sending.value,
);
const graph = ref({ nodes: [], edges: [], ready: false }),
  usedRelationships = ref([]);
const prefs = reactive({
  role: '通用岗位',
  level: '入门',
  length: '简洁',
  product: '',
  modelConsent: false,
  graphEnabled: false,
});
const prefsDirty = ref(false);
let syncPrefs = false,
  timer,
  queryController,
  refreshSequence = 0,
  historySequence = 0,
  querySequence = 0,
  graphSequence = 0,
  mounted = true;
let graphChain = Promise.resolve(),
  graphRequestedSearch,
  graphRequestedRevision;
let idleTicks = 0;
const initialToken = getToken();
function privateAction(action, payload = {}, options = {}) {
  return dispatchPrivate(action, payload, { ...options, expectedAuthToken: initialToken });
}
function checkSession() {
  if (getToken() === initialToken) return;
  historySequence++;
  notebook.value?.clearForAuthChange();
  notesOpened.value = false;
  expandedSources.value = new Set();
  queryController?.abort();
  documents.value = [];
  messages.value = [];
  question.value = '';
  replacedQuestion.value = null;
  previousConversation.value = null;
  histories.value = [];
  closeSource();
  graph.value = { nodes: [], edges: [], ready: false };
  window.location.reload();
}
watch(authRevision, checkSession);
watch(
  () => prefs.modelConsent,
  (allowed) => {
    if (!allowed) prefs.graphEnabled = false;
  },
);
const visibleDocs = computed(() =>
  documents.value.filter((doc) => doc.name.toLocaleLowerCase().includes(filter.value.trim().toLocaleLowerCase())),
);
const sessions = computed(() => historySessions(histories.value));
const pending = computed(() => jobs.value.some((job) => ['queued', 'running'].includes(job.state)));
const readyCount = computed(() => documents.value.filter((doc) => doc.vector_version > 0 && doc.active_hash).length);
const relevantJobs = computed(() => jobs.value.filter((job) => job.state !== 'superseded').slice(0, 3));
const stageLabel = computed(() =>
  serviceError.value ? '连接待恢复' : !service.value ? '正在连接' : service.value.ragReady ? '文档可用' : '等待资料',
);
const generation = computed(() => knowledgeGenerationState(service.value, budget.value));
watch(
  prefs,
  () => {
    if (!syncPrefs) prefsDirty.value = knowledgePreferencesChanged(prefs, service.value);
  },
  { deep: true, flush: 'sync' },
);

function failureText(failure, fallback) {
  return failure?.displayMessage || fallback;
}
async function refresh(quiet = false) {
  if (preview) return;
  const sequence = ++refreshSequence;
  if (!quiet) loading.value = true;
  try {
    const [listing, status] = await Promise.all([privateAction('documents.list'), privateAction('status')]);
    if (!mounted || sequence !== refreshSequence) return;
    const nextDocuments = listing.documents || [];
    if (JSON.stringify(documents.value) !== JSON.stringify(nextDocuments)) documents.value = nextDocuments;
    jobs.value = listing.jobs || [];
    service.value = listing;
    budget.value = status.budget;
    serviceError.value = '';
    if (!prefsDirty.value) {
      syncPrefs = true;
      Object.assign(prefs, knowledgePreferencesSnapshot(listing));
      syncPrefs = false;
    }
  } catch (failure) {
    if (sequence === refreshSequence && mounted)
      serviceError.value = failureText(failure, '暂时无法连接私有资料服务，请稍后重试。');
  } finally {
    if (sequence === refreshSequence) loading.value = false;
  }
}
async function loadHistory() {
  if (preview) return;
  const sequence = ++historySequence;
  try {
    const result = await privateAction('history');
    if (mounted && sequence === historySequence) histories.value = result;
  } catch (failure) {
    if (mounted && sequence === historySequence) actionError.value = failureText(failure, '历史问答暂不可用，请重试。');
  }
}
function newConversation() {
  if (sending.value) stopWaiting();
  if (messages.value.length || question.value.trim()) {
    previousConversation.value = {
      id: session.value,
      messages: [...messages.value],
      question: question.value,
      mode: mode.value,
      replacedQuestion: replacedQuestion.value,
      revision: service.value?.revision,
      answerRevision: service.value?.answer_revision,
    };
  }
  session.value = crypto.randomUUID();
  messages.value = [];
  question.value = '';
  replacedQuestion.value = null;
  closeSource();
  scrollThread();
  nextTick(() => questionInput.value?.focus({ preventScroll: true }));
}
function returnPreviousConversation() {
  if (!canReturnPrevious.value) return;
  const previous = previousConversation.value;
  const fresh =
    previous.revision === service.value?.revision && previous.answerRevision === service.value?.answer_revision;
  previousConversation.value = null;
  session.value = previous.id;
  messages.value = previous.messages.map((message) =>
    message.role === 'assistant' && !fresh ? { ...message, valid: false } : message,
  );
  question.value = previous.question;
  mode.value = previous.mode;
  replacedQuestion.value = previous.replacedQuestion;
  closeSource();
  scrollThread();
  nextTick(() => questionInput.value?.focus({ preventScroll: true }));
}
function openConversation(value) {
  if (sending.value) stopWaiting();
  previousConversation.value = null;
  session.value = value.id;
  messages.value = [...value.rows].reverse().flatMap((row) => [
    { role: 'user', text: row.question, key: 'q-' + row.id },
    {
      role: 'assistant',
      ...row.response,
      historyId: row.id,
      question: row.question,
      valid: row.valid,
      key: 'a-' + row.id,
    },
  ]);
  tab.value = 'ask';
  closeSource();
  scrollThread();
}
async function clearHistory() {
  if (historyClearing.value || sending.value) return;
  historyClearing.value = true;
  try {
    try {
      await ElMessageBox.confirm('清除已保存的知识问答，保留正在编辑的问题和资料。', '清除问答历史', {
        confirmButtonText: '清除历史',
        cancelButtonText: '保留',
      });
    } catch {
      return;
    }
    await privateAction('history.clear');
    if (!mounted || getToken() !== initialToken) return;
    historySequence++;
    histories.value = [];
    previousConversation.value = null;
    session.value = crypto.randomUUID();
    messages.value = [];
    closeSource();
    scrollThread();
    ElMessage.success('历史问答已清除');
  } catch (failure) {
    actionError.value = failureText(failure, '清除失败，请重试。');
  } finally {
    historyClearing.value = false;
  }
}
async function scrollThread() {
  await nextTick();
  thread.value?.scrollTo({ top: messages.value.length ? thread.value.scrollHeight : 0, behavior: 'auto' });
}
async function sendQuestion(text) {
  const value = (typeof text === 'string' ? text : question.value).trim();
  if (!value || sending.value || historyClearing.value || preview || !service.value || serviceError.value) return;
  if (value.length < 2) {
    actionError.value = '问题至少需要 2 个字';
    return;
  }
  actionError.value = '';
  const sequence = ++querySequence,
    currentSession = session.value;
  queryController = new AbortController();
  messages.value.push({ role: 'user', text: value, key: crypto.randomUUID() });
  previousConversation.value = null;
  if (typeof text !== 'string') {
    question.value = '';
    replacedQuestion.value = null;
  }
  sending.value = true;
  scrollThread();
  try {
    const result = await privateAction(
      'question',
      { question: value, mode: mode.value, sessionId: currentSession },
      { signal: queryController.signal },
    );
    if (sequence !== querySequence || !mounted || session.value !== currentSession) return;
    messages.value.push({ role: 'assistant', ...result, question: value, valid: true, key: crypto.randomUUID() });
    loadHistory();
    refresh(true);
  } catch (failure) {
    if (sequence !== querySequence || !mounted || failure.code === 'ERR_CANCELED') return;
    messages.value.push({
      role: 'error',
      text: failureText(failure, '这次问答未完成，请重试。'),
      retry: value,
      key: crypto.randomUUID(),
    });
  } finally {
    if (sequence === querySequence) sending.value = false;
    scrollThread();
  }
}
function stopWaiting() {
  querySequence += 1;
  queryController?.abort();
  sending.value = false;
  messages.value.push({
    role: 'notice',
    text: '已停止等待。服务端若已生成结果，可稍后在历史问答中查看。',
    key: crypto.randomUUID(),
  });
}
function composerKey(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault();
    sendQuestion();
  }
}
function pickedFiles(event, replacing = false) {
  const chosen = [...(event.target.files || [])];
  event.target.value = '';
  return uploadChosenFiles(chosen, replacing);
}
async function showProcessing() {
  sideTab.value = 'documents';
  filesCollapsed.value = false;
  await nextTick();
  if (jobsPanel.value) {
    jobsPanel.value.open = true;
    jobsPanel.value.scrollIntoView({ block: 'center' });
  }
}
function dragEnter(event) {
  if (!event.dataTransfer?.types.includes('Files')) return;
  event.preventDefault();
  if (!preview && !uploading.value) dragDepth.value += 1;
}
function dragOver(event) {
  if (!event.dataTransfer?.types.includes('Files')) return;
  event.preventDefault();
  event.dataTransfer.dropEffect = preview || uploading.value ? 'none' : 'copy';
}
function droppedFiles(event) {
  if (!event.dataTransfer?.types.includes('Files')) return;
  event.preventDefault();
  dragDepth.value = 0;
  if (preview || uploading.value) return;
  return uploadChosenFiles([...event.dataTransfer.files]);
}
async function uploadChosenFiles(chosen, replacing = false) {
  if (!chosen.length || uploading.value) return;
  filesCollapsed.value = false;
  uploading.value = true;
  actionError.value = '';
  let completed = 0;
  const uploadToken = initialToken;
  const failures = [];
  for (const file of chosen) {
    if (!mounted || getToken() !== uploadToken) break;
    const invalid = validateKnowledgeFile(file);
    if (invalid) {
      failures.push(file.name + '：' + invalid);
      continue;
    }
    try {
      const result = await uploadPrivateFile(file, replacing ? replaceTarget.value?.id : undefined, uploadToken);
      completed += 1;
      if (result.unchanged) ElMessage.info(result.restored ? '原件已恢复' : '内容相同，已保留当前版本');
    } catch (failure) {
      failures.push(file.name + '：' + failureText(failure, '上传未完成，请重试。'));
    }
  }
  uploading.value = false;
  replaceTarget.value = null;
  if (completed) ElMessage.success('已接收 ' + completed + ' 份资料，处理状态可在列表中查看');
  if (failures.length) actionError.value = failures.join('\n');
  await refresh(true);
}
function chooseReplacement(doc) {
  replaceTarget.value = doc;
  replaceInput.value?.click();
}
async function deleteDocument(doc) {
  try {
    await ElMessageBox.confirm(
      '删除“' + doc.name + '”后，该文件及历史版本会从私有库移除，不再用于问答；相关图谱需要更新。',
      '删除私有资料',
      { confirmButtonText: '确认删除', cancelButtonText: '保留', type: 'warning' },
    );
  } catch {
    return;
  }
  try {
    await privateAction('documents.delete', { documentId: doc.id });
    if (sourcePanel.value?.id === doc.id) closeSource();
    graph.value = { nodes: [], edges: [], ready: false };
    ElMessage.success('资料已撤销，后续问答不再使用它');
    await refresh(true);
    await loadHistory();
    messages.value = messages.value.map((message) =>
      message.role === 'assistant' ? { ...message, valid: false } : message,
    );
  } catch (failure) {
    actionError.value = failureText(failure, '删除未完成，请重试。');
  }
}
async function download(doc) {
  try {
    savePrivateDownload(
      await privateAction('documents.download', { documentId: doc.documentId || doc.id, version: doc.version }),
    );
  } catch (failure) {
    actionError.value = failureText(failure, '下载未完成，请重试。');
  }
}
let sourceSequence = 0;
const scanPageUrl = ref('');
const scanPageLoading = ref(false);
const scanPageError = ref('');
const scanPageType = ref('');
function clearScanPage() {
  if (scanPageUrl.value) URL.revokeObjectURL(scanPageUrl.value);
  scanPageUrl.value = '';
  scanPageLoading.value = false;
  scanPageError.value = '';
  scanPageType.value = '';
}
async function loadScanPage(piece) {
  clearScanPage();
  const sequence = ++sourceSequence;
  const source = sourcePanel.value;
  scanPageLoading.value = true;
  try {
    const result = await privateAction('scans.page', {
      documentId: source.id, version: source.version, locator: piece.locator,
    });
    if (sequence !== sourceSequence || !mounted) return;
    if (!['application/pdf', 'image/png', 'image/jpeg'].includes(result.type)) throw new Error();
    const bytes = Uint8Array.from(atob(result.content), (char) => char.charCodeAt(0));
    scanPageType.value = result.type;
    scanPageUrl.value = URL.createObjectURL(new Blob([bytes], { type: result.type }));
  } catch (failure) {
    if (sequence === sourceSequence && mounted) scanPageError.value = failureText(failure, '扫描原页暂时无法打开。');
  } finally {
    if (sequence === sourceSequence) scanPageLoading.value = false;
  }
}
function closeSource() {
  sourceSequence += 1;
  clearScanPage();
  sourcePanel.value = null;
  sourceLoading.value = false;
  sourceError.value = '';
}
async function openSource(source) {
  clearScanPage();
  const sequence = ++sourceSequence;
  sourceLoading.value = true;
  sourceError.value = '';
  sourcePanel.value = { id: source.documentId || source.id, name: source.name, version: source.version, pieces: [] };
  try {
    const result = await privateAction('documents.preview', {
      documentId: source.documentId || source.id,
      version: source.version,
      locator: source.locator,
    });
    if (sequence === sourceSequence && mounted) sourcePanel.value = result;
  } catch (failure) {
    if (sequence === sourceSequence && mounted)
      sourceError.value = failureText(failure, '这份来源暂时无法打开，可能已被删除或更新。');
  } finally {
    if (sequence === sourceSequence) sourceLoading.value = false;
  }
}
function openScan(doc = null) {
  scanDocument.value = doc;
  scanOpen.value = true;
}
function openTools(mode, message = null) {
  toolsMode.value = mode;
  spokenAnswer.value = message?.answer || '';
  spokenHistoryId.value = message ? Number(message.historyId) : null;
  toolsOpen.value = true;
}
async function onScanPublished(value) {
  ElMessage.success(value.unchanged ? '已保留当前资料版本' : '扫描件已入库，正在建立检索索引');
  await refresh(true);
}
function onVoiceDraft(text) {
  if (question.value.trim().length + text.length + (question.value.trim() ? 1 : 0) > 1000) {
    ElMessage.warning('加入这段文字会超过 1000 字，请先精简现有问题或转写内容');
    return false;
  }
  fillQuestion([question.value.trim(), text].filter(Boolean).join('\n'));
  return true;
}
async function retryJob(job) {
  try {
    await privateAction('jobs.retry', { jobId: job.id });
    await refresh(true);
  } catch (failure) {
    actionError.value = failureText(failure, '任务暂时无法重试。');
  }
}
async function cancelJob(job) {
  try {
    await privateAction('jobs.cancel', { jobId: job.id });
    await refresh(true);
  } catch (failure) {
    actionError.value = failureText(failure, '取消未完成，请刷新任务状态。');
  }
}
async function savePreferences() {
  if (prefsBusy.value || preview || !prefsDirty.value || !service.value || serviceError.value) return;
  prefsBusy.value = true;
  actionError.value = '';
  try {
    const result = await privateAction('preferences.save', { ...prefs });
    syncPrefs = true;
    // The POST result is authoritative even if the following background refresh fails.
    service.value = {
      ...service.value,
      preferences: result.preferences,
      model_consent: result.modelConsent,
      graph_enabled: result.graphEnabled,
    };
    Object.assign(prefs, result.preferences, { modelConsent: result.modelConsent, graphEnabled: result.graphEnabled });
    prefsDirty.value = false;
    syncPrefs = false;
    ElMessage.success('已保存');
    await refresh(true);
  } catch (failure) {
    actionError.value = failureText(failure, '设置尚未保存，请重试。');
  } finally {
    prefsBusy.value = false;
  }
}
function resetPreferences() {
  if (prefsBusy.value || !service.value) return;
  syncPrefs = true;
  Object.assign(prefs, knowledgePreferencesSnapshot(service.value));
  prefsDirty.value = false;
  syncPrefs = false;
}
async function loadGraph(search = '') {
  if (preview || !mounted) return;
  const revision = service.value?.revision;
  if (graphLoading.value && search === graphRequestedSearch && revision === graphRequestedRevision) return graphChain;
  const sequence = ++graphSequence;
  graphRequestedSearch = search;
  graphRequestedRevision = revision;
  graphLoading.value = true;
  graphError.value = '';
  const request = graphChain.then(async () => {
    if (!mounted || sequence !== graphSequence) return;
    try {
      const value = await privateAction('graph', { search });
      if (!mounted || sequence !== graphSequence) return;
      if (value.ready && value.revision !== service.value?.revision) {
        graph.value = { nodes: [], edges: [], ready: false };
        graphError.value = '资料已变化，请刷新后查看最新图谱。';
        return;
      }
      graph.value = value;
    } catch (failure) {
      if (mounted && sequence === graphSequence)
        graphError.value = failureText(failure, '图谱暂时无法读取，请稍后重试。');
    } finally {
      if (sequence === graphSequence) graphLoading.value = false;
    }
  });
  // The backend intentionally runs one graph worker. Let it finish instead of
  // overlapping requests; skipped intermediate searches never reach the API.
  graphChain = request.catch(() => {});
  return request;
}
async function buildGraph() {
  if (!service.value?.model_consent) {
    tab.value = 'settings';
    actionError.value = '请先选择是否允许模型处理资料片段，再构建图谱。';
    return;
  }
  try {
    await privateAction('graph.build');
    ElMessage.success('已加入图谱处理任务，文档问答可继续使用');
    await refresh(true);
  } catch (failure) {
    graphError.value = failureText(failure, '暂时无法启动图谱构建，请重试。');
  }
}
function showRelationships(message) {
  usedRelationships.value = message.graphEvidence?.relationships || [];
  tab.value = 'graph';
  loadGraph();
}
function toBusiness(text) {
  if (String(text || '').length > 500) ElMessage.info('问题较长，已带入前500字，可在业务助手中调整后发送。');
  router.push({ path: '/ai/workbench', query: text ? { prefill: sliceUtf16(text, 500) } : {} });
}
async function saveReview(message) {
  if (!message.historyId || message.valid === false) return;
  notesOpened.value = true;
  tab.value = 'notes';
  closeSource();
  await nextTick();
  await notebook.value?.capture(message.historyId);
}
function toggleSources(key) {
  const value = new Set(expandedSources.value);
  if (value.has(key)) value.delete(key);
  else value.add(key);
  expandedSources.value = value;
}
function fillQuestion(text, retrieval = mode.value) {
  const value = sliceUtf16(String(text || ''), 1000);
  if (value !== question.value || retrieval !== mode.value) {
    replacedQuestion.value =
      question.value.trim() || retrieval !== mode.value ? { text: question.value, mode: mode.value } : null;
    question.value = value;
    mode.value = retrieval;
  }
  nextTick(() => questionInput.value?.focus());
}
function undoQuestionReplacement() {
  const previous = replacedQuestion.value;
  if (!previous) return;
  replacedQuestion.value = null;
  question.value = previous.text;
  mode.value = previous.mode;
  nextTick(() => questionInput.value?.focus());
}
function useGraphQuestion(text) {
  fillQuestion(text, 'fusion');
  tab.value = 'ask';
  closeSource();
}
watch(question, (value) => {
  if (!value) replacedQuestion.value = null;
});
watch(tab, (value) => {
  if (value === 'notes') notesOpened.value = true;
  if (value === 'graph') loadGraph();
  if (value === 'ask') scrollThread();
});
watch(sideTab, (value) => {
  if (value === 'history') loadHistory();
});
watch(
  () => [service.value?.revision, service.value?.answer_revision],
  ([value, published], [old, oldPublished]) => {
    if (old !== undefined && (value !== old || published !== oldPublished)) {
      messages.value = messages.value.map((message) =>
        message.role === 'assistant' ? { ...message, valid: false } : message,
      );
      graph.value = { nodes: [], edges: [], ready: false };
      usedRelationships.value = [];
      if (sourcePanel.value) {
        const doc = documents.value.find((item) => item.id === sourcePanel.value.id);
        if (!doc) {
          closeSource();
        } else if (doc.vector_version !== sourcePanel.value.version)
          sourcePanel.value = { ...sourcePanel.value, active: false, publication: 'historical' };
      }
      if (tab.value === 'graph' && service.value.graphReady) loadGraph();
    }
  },
);
onMounted(() => {
  window.addEventListener('storage', checkSession);
  refresh();
  timer = setInterval(() => {
    idleTicks += 1;
    if (mounted && !document.hidden && (pending.value || sending.value || idleTicks % 5 === 0)) refresh(true);
  }, 3000);
});
onBeforeUnmount(() => {
  mounted = false;
  querySequence += 1;
  refreshSequence += 1;
  graphSequence += 1;
  closeSource();
  queryController?.abort();
  clearInterval(timer);
  window.removeEventListener('storage', checkSession);
});
</script>

<template>
  <section
    class="private-knowledge"
    :class="{ 'is-ask-view': tab === 'ask', 'is-chat-focused': tab === 'ask' && filesCollapsed && !sourcePanel }"
    @dragenter="dragEnter"
    @dragover="dragOver"
    @dragleave="dragDepth = Math.max(0, dragDepth - 1)"
    @drop="droppedFiles"
  >
    <div v-if="dragDepth" class="knowledge-drop-target" role="status">
      <AppIcon name="upload" :size="32" /><strong>松开文件，添加到我的私有资料</strong
      ><span>PDF、DOCX、XLSX、TXT、MD · 每份不超过 8 MB</span>
    </div>
    <h1 class="knowledge-page-title">我的知识</h1>
    <input
      ref="filesInput"
      type="file"
      hidden
      multiple
      accept=".pdf,.docx,.xlsx,.txt,.md"
      aria-label="上传私有资料"
      @change="pickedFiles($event)"
    />
    <input
      ref="replaceInput"
      type="file"
      hidden
      accept=".pdf,.docx,.xlsx,.txt,.md"
      aria-label="更新私有资料版本"
      @change="pickedFiles($event, true)"
    />
    <div class="knowledge-navigation">
      <nav aria-label="知识工作区">
        <button
          v-for="item in [
            { value: 'ask', label: '资料与问答' },
            { value: 'graph', label: '知识图谱' },
            { value: 'notes', label: '研判工作单' },
            { value: 'settings', label: '偏好设置' },
          ]"
          :key="item.value"
          type="button"
          :class="{ active: tab === item.value }"
          :aria-current="tab === item.value ? 'page' : undefined"
          @click="tab = item.value"
        >
          {{ item.label }}
        </button>
      </nav>
      <span class="knowledge-connection" :class="{ warning: serviceError }" :title="stageLabel" role="status">{{
        preview ? '登录后使用' : stageLabel
      }}</span>
      <div class="knowledge-toolbar-actions">
        <el-button @click="toBusiness()"><AppIcon name="bot" :size="16" />业务助手</el-button>
        <el-button :disabled="preview || !service || uploading" @click="openScan()">
          <AppIcon name="scan" :size="16" />扫描件识别
        </el-button><el-button
          type="primary"
          :disabled="preview || uploading || !service || !!serviceError"
          :loading="uploading"
          @click="filesInput?.click()"
          ><AppIcon name="plus" :size="16" />上传资料</el-button
        >
      </div>
      <button
        type="button"
        class="icon-action"
        :disabled="loading || preview"
        aria-label="刷新私有资料状态"
        @click="refresh()"
      >
        <AppIcon name="refresh" :size="16" />
      </button>
    </div>
    <div v-if="preview" class="knowledge-banner">
      <AppIcon name="lock" :size="18" /><span>当前为框架预览，私有资料需登录后使用。</span
      ><RouterLink :to="{ path: '/login', query: { redirect: '/ai/knowledge' } }">连接并登录</RouterLink>
    </div>
    <div v-if="serviceError" class="knowledge-banner is-error" role="alert">
      <AppIcon name="alert" :size="17" /><span>{{ serviceError }}</span
      ><button type="button" @click="refresh()">重新连接</button>
    </div>
    <div v-if="actionError" class="knowledge-banner is-error" role="alert">
      <span>{{ actionError }}</span
      ><button type="button" @click="actionError = ''" aria-label="关闭操作提示">
        <AppIcon name="close" :size="15" />
      </button>
    </div>

    <button
      v-if="tab === 'ask' && !sourcePanel"
      type="button"
      class="knowledge-mobile-files"
      aria-controls="private-files"
      :aria-expanded="!filesCollapsed"
      @click="filesCollapsed = !filesCollapsed"
    >
      <span
        >{{ filesCollapsed ? '查看资料与历史' : '收起资料，回到问答' }} ·
        {{ service ? `${documents.length} 份资料` : '连接待恢复' }}</span
      ><AppIcon name="chevron" :size="15" />
    </button>
    <div
      v-if="tab === 'ask'"
      class="knowledge-workspace"
      :class="{ 'with-source': sourcePanel, 'files-collapsed': filesCollapsed }"
    >
      <aside id="private-files" class="knowledge-files">
        <div class="files-switch" role="group" aria-label="私有资料与历史问答">
          <button type="button" :class="{ active: sideTab === 'documents' }" @click="sideTab = 'documents'">
            我的资料 <span>{{ service ? documents.length : '—' }}</span></button
          ><button type="button" :class="{ active: sideTab === 'history' }" @click="sideTab = 'history'">
            历史问答
          </button>
        </div>
        <template v-if="sideTab === 'documents'">
          <el-input v-model="filter" clearable placeholder="搜索资料名称" aria-label="搜索我的资料"
            ><template #prefix><AppIcon name="search" :size="15" /></template
          ></el-input>
          <div class="files-list" :aria-busy="loading">
            <p v-if="!service && serviceError" class="files-empty">资料连接待恢复。重新连接后可查看你的文件。</p>
            <p v-else-if="!documents.length && !loading && service" class="files-empty">
              还没有私有资料。上传 PDF、Word、Excel 或文本后，即可检索原文。
            </p>
            <p v-else-if="!visibleDocs.length && !loading" class="files-empty" role="status">
              没有匹配的文件。<button class="text-action" type="button" @click="filter = ''">清除搜索</button>
            </p>
            <article v-for="doc in visibleDocs" :key="doc.id" class="private-file">
              <button type="button" class="file-open" @click="openSource(doc)">
                <AppIcon name="clipboard" :size="17" /><span
                  ><strong>{{ doc.name }}</strong
                  ><small
                    >版本 {{ doc.version }} · {{ doc.name.split('.').pop().toUpperCase()
                    }}<template v-if="doc.vector_version > 0 && doc.vector_version !== doc.version">
                      · 沿用 v{{ doc.vector_version }}</template
                    ></small
                  ></span
                >
              </button>
              <div class="file-state">
                <span :class="{ ready: doc.vector_state === 'ready', failed: doc.vector_state === 'failed' }">{{
                  knowledgeState(doc.vector_state)
                }}</span
                ><span>图谱 {{ knowledgeState(doc.graph_state) }}</span>
              </div>
              <p v-if="doc.vector_error" class="file-error">{{ doc.vector_error }}</p>
              <div class="file-actions">
                <button type="button" @click="download(doc)">下载</button
                ><button type="button" :disabled="uploading" @click="chooseReplacement(doc)">更新版本</button
                ><button v-if="/\.(pdf|png|jpe?g)$/i.test(doc.name) && service?.ocrAvailable" type="button"
                  :disabled="uploading" @click="openScan(doc)">用扫描件更新</button
                ><button type="button" class="danger-text" @click="deleteDocument(doc)">删除</button>
              </div>
            </article>
          </div>
          <details class="files-footnote">
            <summary>格式与隐私</summary>
            <p>PDF、DOCX、XLSX、TXT、MD，每份最多8MB。原件与索引按账号隔离。</p>
          </details>
          <details v-if="relevantJobs.length" ref="jobsPanel" class="knowledge-jobs" :open="pending">
            <summary>
              处理任务 <span>{{ pending ? '进行中' : '查看结果' }}</span>
            </summary>
            <div v-for="job in relevantJobs" :key="job.id" class="knowledge-job">
              <strong>{{ knowledgeState(job.state) }}</strong
              ><small>文档 {{ knowledgeState(job.vector_state) }} · 图谱 {{ knowledgeState(job.graph_state) }}</small>
              <p v-if="job.error">{{ job.error }}</p>
              <button
                v-if="
                  ['failed', 'partial', 'cancelled'].includes(job.state) &&
                  job.attempts < 3 &&
                  job.revision === service?.revision
                "
                type="button"
                @click="retryJob(job)"
              >
                重试任务</button
              ><button v-if="['running', 'queued'].includes(job.state)" type="button" @click="cancelJob(job)">
                停止后续处理
              </button>
            </div>
          </details>
        </template>
        <template v-else>
          <div class="history-tools">
            <span>最近问答</span
            ><button type="button" :disabled="!histories.length || historyClearing || sending" @click="clearHistory">
              {{ historyClearing ? '正在清除…' : '清除历史' }}
            </button>
          </div>
          <p v-if="!sessions.length" class="files-empty">还没有保存的问答。你可以先上传资料，再提出一个问题。</p>
          <button
            v-for="item in sessions"
            :key="item.id"
            type="button"
            class="knowledge-session"
            :class="{ selected: session === item.id }"
            @click="openConversation(item)"
          >
            <strong>{{ item.title }}</strong
            ><span>{{ dayjs(item.created).format('MM-DD HH:mm') }} · {{ item.rows.length }} 次问答</span>
          </button>
        </template>
      </aside>

      <main class="knowledge-chat">
        <header class="knowledge-chat-head">
          <div>
            <h2>资料问答</h2>
            <span
              >{{ service ? `${readyCount} 份文档可用` : '正在连接资料'
              }}<span v-if="service?.graphReady"> · 图谱已就绪</span></span
            >
          </div>
          <div class="knowledge-chat-actions">
            <button v-if="canReturnPrevious" type="button" class="text-action" @click="returnPreviousConversation">
              返回上一会话
            </button>
            <button type="button" class="text-action" :disabled="preview" @click="newConversation">
              <AppIcon name="plus" :size="15" />新对话
            </button>
          </div>
        </header>
        <div
          ref="thread"
          class="knowledge-thread"
          :class="{ 'is-empty': !messages.length }"
          role="log"
          aria-label="私有知识问答记录"
          aria-live="polite"
        >
          <div v-if="!messages.length" class="knowledge-welcome">
            <h3>
              {{
                !service && !preview
                  ? serviceError
                    ? '资料连接待恢复'
                    : '正在读取你的资料'
                  : readyCount
                    ? '今天，你想核对什么？'
                    : documents.length
                      ? pending
                        ? '资料正在处理中'
                        : '资料暂未就绪'
                      : '先放入一份你要用的资料'
              }}
            </h3>
            <p v-if="!readyCount">
              {{
                !service && !preview
                  ? serviceError
                    ? '可先使用原业务工作台，连接恢复后再查看资料。'
                    : '正在核对文件、版本和索引状态。'
                  : readyCount
                    ? '查询参数、整理步骤，或追查跨文件关系。每条引用都能打开原文核对。'
                    : documents.length
                      ? pending
                        ? '资料已经接收，正在建立索引。就绪后即可提问；处理任务中可查看进度。'
                        : '请查看处理任务中的结果与原因。失败任务可重试，也可以更新原文件后重新处理。'
                      : '说明书、SOP、检验标准都可以。资料就绪后，在这里直接提问。'
              }}
            </p>
            <p v-if="readyCount" class="knowledge-welcome-intro">从作业步骤到变更影响，让每个结论都有出处。</p>
            <div v-if="readyCount" class="knowledge-suggestions">
              <button
                v-for="item in [
                  { title: '整理操作步骤', text: '请整理这些资料中的关键操作步骤，并给出依据。' },
                  { title: '追查资料关联', text: '资料中的产品、物料、工序和检验标准有哪些关联？' },
                  {
                    title: '核对变更影响',
                    text: '我想核对一项物料变更是否已经批准，以及影响哪些工序和检验规范。请先告诉我需要补充哪些信息。',
                  },
                ]"
                :key="item.title"
                type="button"
                @click="fillQuestion(item.text)"
              >
                <span
                  ><strong>{{ item.title }}</strong></span
                ><AppIcon name="arrow" :size="15" />
              </button>
            </div>
            <el-button v-else-if="serviceError" :loading="loading" @click="refresh()">重新连接</el-button>
            <el-button v-else-if="documents.length" @click="showProcessing">查看处理任务</el-button>
            <el-button v-else-if="service || preview" :disabled="preview || uploading" @click="filesInput?.click()"
              >上传第一份资料</el-button
            >
          </div>
          <article
            v-for="message in messages"
            :key="message.key"
            class="knowledge-message"
            :class="'is-' + message.role"
          >
            <template v-if="message.role === 'user'"
              ><span class="message-who">你</span>
              <p>{{ message.text }}</p></template
            >
            <template v-else-if="message.role === 'assistant'"
              ><div class="message-who">
                <AppIcon name="bot" :size="15" />资料助手<span>{{
                  message.businessHandoff
                    ? '业务引导'
                    : `${knowledgeModes.find((item) => item.value === message.mode)?.label || '文档'}检索`
                }}</span>
              </div>
              <p v-if="message.valid === false" class="message-warning">
                资料已变化，此回答仅作为历史记录，不再用于后续问答。
              </p>
              <GroundedAnswer :text="message.answer" :citations="message.citations" @source="openSource" />
              <p v-if="message.notice" class="message-notice">{{ message.notice }}</p>
              <div v-if="message.citations?.length" class="message-sources">
                <button
                  v-for="citation in expandedSources.has(message.key)
                    ? orderedAnswerCitations(message.answer, message.citations)
                    : orderedAnswerCitations(message.answer, message.citations).slice(0, 3)"
                  :key="citation.id"
                  type="button"
                  @click="openSource(citation)"
                >
                  <span>{{ citation.id }}</span
                  >{{ citation.name }}<small>{{ citation.locator }} · v{{ citation.version }}</small>
                </button>
              </div>
              <button
                v-if="message.citations?.length > 3"
                type="button"
                class="text-action more-sources"
                @click="toggleSources(message.key)"
              >
                {{ expandedSources.has(message.key) ? '收起依据' : `查看全部 ${message.citations.length} 处依据` }}
              </button>
              <details class="message-route">
                <summary>检索方式与关系线索</summary>
                <span>{{ message.routeReason }}</span
                ><button
                  v-if="message.graphEvidence?.relationships?.length"
                  type="button"
                  @click="showRelationships(message)"
                >
                  查看本次关系线索
                </button>
              </details>
              <div class="message-next-actions">
                <button v-if="!preview && service?.model_consent && message.historyId && message.answer && message.valid !== false && !message.filtered"
                  class="text-action" type="button" @click="openTools('read', message)">朗读答复</button>
                <button
                  v-if="message.historyId && message.citations?.length && message.valid !== false"
                  type="button"
                  class="message-save-note"
                  @click="saveReview(message)"
                >
                  <AppIcon name="clipboard" :size="15" />存为工作单</button
                ><button type="button" class="message-business" @click="toBusiness(message.question)">
                  转到业务助手<AppIcon name="arrow" :size="14" />
                </button>
              </div>
            </template>
            <template v-else
              ><p :role="message.role === 'error' ? 'alert' : 'status'">{{ message.text }}</p>
              <button v-if="message.retry" type="button" class="text-action" @click="sendQuestion(message.retry)">
                重新提问
              </button></template
            >
          </article>
          <div v-if="sending" class="knowledge-thinking" role="status">
            <AppIcon name="search" :size="16" /><span>正在检索资料并整理回答…</span
            ><button type="button" @click="stopWaiting">停止等待</button>
          </div>
        </div>
        <form class="knowledge-composer" @submit.prevent="sendQuestion()">
          <label class="sr-only" for="knowledge-question">向私有资料提问</label>
          <textarea
            id="knowledge-question"
            ref="questionInput"
            v-model="question"
            maxlength="1000"
            rows="3"
            :disabled="preview || !service || !!serviceError"
            placeholder="输入问题，例如：更换驱动板后要复核什么？"
            @keydown="composerKey"
          />
          <div class="composer-bottom">
            <div class="retrieval-switch" role="group" aria-label="检索方式">
              <button
                v-for="item in knowledgeModes"
                :key="item.value"
                type="button"
                :class="{ active: mode === item.value }"
                :aria-pressed="mode === item.value"
                @click="mode = item.value"
              >
                {{ item.label }}
              </button>
            </div>
            <span v-if="question.length" class="composer-count">{{ question.length }}/1000</span
            ><button
              type="submit"
              class="knowledge-send"
              :disabled="preview || !service || !question.trim() || sending || historyClearing || !!serviceError"
              aria-label="发送知识问题"
            >
              <AppIcon name="arrow" :size="18" />
            </button>
          </div>
          <div class="composer-note">
            <button v-if="!preview && service" type="button" class="text-action" @click="openTools('web')">公开资料</button>
            <button v-if="!preview && service" type="button" class="text-action" @click="openTools('voice')">语音输入</button>
            <button type="button" class="generation-entry" :title="generation.detail" @click="tab = 'settings'">
              <AppIcon :name="generation.available ? 'bot' : 'search'" :size="13" />{{ generation.label }}
              <AppIcon name="chevron" :size="12" />
            </button>
            <button v-if="replacedQuestion" type="button" class="text-action" @click="undoQuestionReplacement">
              撤销填入
            </button>
            <span>Enter 发送 · Shift+Enter 换行</span>
          </div>
        </form>
      </main>
      <aside v-if="sourcePanel" class="knowledge-source" aria-label="原文来源预览">
        <header>
          <div>
            <h2>{{ sourcePanel.name }}</h2>
            <span
              >版本 {{ sourcePanel.version
              }}<template v-if="sourcePanel.publication === 'pending'"> · 尚未用于问答</template
              ><template v-else-if="sourcePanel.active === false"> · 历史版本</template></span
            >
          </div>
          <button type="button" class="icon-action" aria-label="关闭原文预览" @click="closeSource">
            <AppIcon name="close" :size="16" />
          </button>
        </header>
        <p v-if="sourceLoading" role="status">正在读取原文…</p>
        <p v-if="sourceError" class="source-error" role="alert">{{ sourceError }}</p>
        <div class="source-pieces">
          <section v-for="(piece, index) in sourcePanel.pieces" :key="piece.locator + '-' + index">
            <h3>{{ piece.locator }}</h3>
            <p v-if="piece.method === 'deepseek-ocr'" class="scan-source-label">经核对的 OCR 文字</p>
            <p>{{ piece.text }}</p>
            <button v-if="piece.method === 'deepseek-ocr'" type="button" class="text-action"
              :disabled="scanPageLoading" @click="loadScanPage(piece)">查看扫描原页</button>
          </section>
          <p v-if="scanPageLoading" role="status">正在读取扫描原页…</p>
          <p v-if="scanPageError" class="source-error" role="alert">{{ scanPageError }}</p>
          <template v-if="scanPageUrl">
            <img v-if="scanPageType.startsWith('image/')" class="scan-source-image" :src="scanPageUrl" alt="引用对应的原始扫描图像" />
            <a :href="scanPageUrl" target="_blank" rel="noopener noreferrer">打开扫描原页 ↗</a>
          </template>
        </div>
        <footer v-if="sourcePanel.pieces?.length">
          <span v-if="sourcePanel.total > 200">当前显示前 200 个位置。</span
          ><button type="button" class="text-action" @click="download(sourcePanel)">下载这个版本的原件</button>
        </footer>
      </aside>
    </div>

    <ScanImportDialog v-model="scanOpen" :document="scanDocument" :available="Boolean(service?.ocrAvailable)"
      @published="onScanPublished" />
    <DeepSeekToolsDialog v-model="toolsOpen" :mode="toolsMode" :conversation="session" :answer="spokenAnswer"
      :history-id="spokenHistoryId" :apply-transcript="onVoiceDraft" />

    <ReviewNotebook
      v-if="notesOpened"
      v-show="tab === 'notes'"
      ref="notebook"
      :active="tab === 'notes'"
      :request="privateAction"
      @source="
        (source) => {
          tab = 'ask';
          openSource(source);
        }
      "
      @business="toBusiness"
    />
    <section v-if="tab === 'graph'" class="knowledge-graph-panel">
      <header>
        <div>
          <h2>知识图谱</h2>
        </div>
        <div>
          <el-button :loading="graphLoading" :disabled="preview" @click="loadGraph()">刷新图谱</el-button
          ><el-button type="primary" :disabled="preview || !documents.length || pending" @click="buildGraph">{{
            service?.graphReady ? '更新图谱' : '构建图谱'
          }}</el-button>
        </div>
      </header>
      <p v-if="graphError" class="source-error" role="alert">{{ graphError }}</p>
      <p v-if="!service?.graphReady" class="graph-availability">
        {{ graph.reason || '图谱尚未就绪。已就绪的文档可以继续问答；建图会将资料片段发送给当前配置的模型服务。' }}
      </p>
      <KnowledgeGraph
        :value="graph"
        :documents="documents"
        :loading="graphLoading"
        :used-relationships="usedRelationships"
        @source="
          (source) => {
            tab = 'ask';
            openSource(source);
          }
        "
        @expand="loadGraph"
        @search="loadGraph"
        @question="useGraphQuestion"
      />
    </section>

    <section v-if="tab === 'settings'" class="knowledge-settings">
      <div class="settings-copy">
        <h2>回答偏好</h2>
        <span v-if="service && !prefsDirty && !prefsBusy" class="settings-saved" role="status"
          ><AppIcon name="check" :size="15" />已保存</span
        >
        <span v-else-if="prefsDirty" class="settings-pending" role="status">未保存</span>
      </div>
      <el-form
        label-position="top"
        class="knowledge-preferences"
        :disabled="preview || prefsBusy || !service"
        @submit.prevent="savePreferences"
      >
        <div class="preference-columns">
          <el-form-item label="岗位偏好"
            ><el-select v-model="prefs.role" filterable allow-create default-first-option
              ><el-option
                v-for="role in ['通用岗位', '操作工', '质检员', '仓管员', '班组长', '系统管理员']"
                :key="role"
                :label="role"
                :value="role" /></el-select></el-form-item
          ><el-form-item label="熟悉程度"
            ><el-select v-model="prefs.level"
              ><el-option
                v-for="level in ['入门', '熟悉', '专业']"
                :key="level"
                :label="level"
                :value="level" /></el-select></el-form-item
          ><el-form-item label="回答篇幅"
            ><el-select v-model="prefs.length"
              ><el-option v-for="length in ['简洁', '详细']" :key="length" :label="length" :value="length" /></el-select
          ></el-form-item>
        </div>
        <el-form-item label="常用产品"
          ><el-input v-model="prefs.product" clearable maxlength="80" placeholder="可选，如 L100"
        /></el-form-item>
        <section class="processing-options">
          <h3>AI与资料</h3>
          <div class="processing-option">
            <label for="knowledge-cloud">允许云模型处理资料片段</label>
            <el-switch id="knowledge-cloud" v-model="prefs.modelConsent" aria-label="允许云模型处理资料片段" />
          </div>
          <div class="processing-option">
            <label for="knowledge-auto-graph">资料更新后自动建图</label>
            <el-switch
              id="knowledge-auto-graph"
              v-model="prefs.graphEnabled"
              :disabled="!prefs.modelConsent"
              aria-label="资料更新后自动建图"
            />
          </div>
          <details class="settings-help">
            <summary>资料如何处理</summary>
            <p>
              原件与向量保存在本地私有库。开启云模型后，问答发送命中片段，建图发送待抽取片段；请求按模型平台额度计费。关闭自动建图时仍会建立本地索引，可手动建图。
            </p>
            <p>岗位偏好只改变回答方式，不改变账号权限。</p>
            <p v-if="budget">
              累计请求 {{ budget.used }} 次<span v-if="budget.limit > 0"> · 本地上限 {{ budget.limit }} 次</span>。
            </p>
          </details>
          <p v-if="service && !service.modelConfigured" class="settings-warning">
            模型服务尚未配置，本地原文检索仍可使用。
          </p>
        </section>
        <div v-if="prefsDirty || prefsBusy" class="settings-save">
          <el-button :disabled="prefsBusy" @click="resetPreferences">撤销更改</el-button>
          <el-button native-type="submit" type="primary" :loading="prefsBusy" :disabled="preview || !!serviceError"
            >保存更改</el-button
          >
        </div>
      </el-form>
      <div class="settings-security">
        <AppIcon name="shield-check" :size="20" />
        <div>
          <h3>账号安全</h3>
        </div>
        <RouterLink to="/account/security">管理人脸<AppIcon name="arrow" :size="15" /></RouterLink>
      </div>
    </section>
  </section>
</template>

<style scoped src="./private-knowledge.css"></style>
