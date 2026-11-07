<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import AppIcon from '@/components/common/AppIcon.vue';
import AiResultBlocks from '@/components/ai/AiResultBlocks.vue';
import AiCommandCard from '@/components/ai/AiCommandCard.vue';
import AiCommandSequenceCard from '@/components/ai/AiCommandSequenceCard.vue';
import AiActionButtons from '@/components/ai/AiActionButtons.vue';
import AiCandidatePicker from '@/components/ai/AiCandidatePicker.vue';
import AiCreateOrderForm from '@/components/ai/AiCreateOrderForm.vue';
import AiProductionFlowForm from '@/components/ai/AiProductionFlowForm.vue';
import AiBusinessActionForm from '@/components/ai/AiBusinessActionForm.vue';
import { businessActionGroups, businessActionPreviewGroups } from '@/utils/ai-business-flow';
import PieceInspectionDialog from '@/components/business/PieceInspectionDialog.vue';
import { formatAiAnswer } from '@/utils/ai-results';
import { resolveOrderContext } from '@/utils/ai-order-context';
import { notifyDataChanged } from '@/utils/app-events';
import { appName } from '@/config/branding';
import { getAiCapabilities, getAiStatus } from '@/api/ai';
import { previewAgentCommand, previewStructuredCommand, getAgentSequence } from '@/api/ai/agent';
import { createTaskFromStartPlan } from '@/api/ai/tasks';
import { getResourceDetail } from '@/api/resource';
import request from '@/api/request';
import { isFrameworkPreview } from '@/utils/auth';
import { aiStatusPresentation, failedAiStatus, shouldRecheckAiStatus } from '@/utils/ai-status';
import {
  activeConversation,
  activeConversationId,
  activeMessages,
  appendCommandMessage,
  archivedConversations,
  archiveConversation,
  cancelSend,
  clearActiveConversation,
  dockOpen,
  historySynced,
  mode,
  newConversation as createConversation,
  persistConversations,
  regenerateMessage as regenerateAnswer,
  removeConversation,
  restoreConversation,
  selectConversation,
  sendMessage,
  sending,
  sessionGroups,
  setConversationCapability,
  setDockOpen,
  setMode,
  syncFromBackend,
} from '@/stores/aiConversation';

// 会话状态全部来自 @/stores/aiConversation，工作台和浮窗只是同一份数据的两种渲染位置。
const previewMode = isFrameworkPreview();
const router = useRouter();
const conversationScroll = ref();
const composer = ref();
const question = ref('');
const pieceInspectionOpen = ref(false);
const pieceInspection = ref(null);
const pieceInspectionMessage = ref(null);
const taskBuilding = ref(false);
const status = ref({
  enabled: false,
  configured: false,
  available: false,
  state: 'CHECKING',
  provider: 'DEEPSEEK',
  model: null,
});
// 用量只在接口成功返回时展示，失败保持 null 且不打扰用户。
const usage = ref(null);
// 复制成功提示的短暂状态，避免为一次复制引入全局消息组件。
const copiedMessageId = ref('');
// 小屏下侧栏改为抽屉，默认收起。
const sidebarOpen = ref(false);
let copiedTimer;
let statusRetryTimer;
let statusLoading = false;
let disposed = false;

// 每种能力定义标题、图标、模块上下文、数据范围和快捷问题。
// 能力现在只决定提问的上下文标签，不再切分会话。
const capabilities = [
  {
    id: 'general',
    label: '业务问答',
    shortLabel: '通用问答',
    icon: 'bot',
    endpoint: 'chat',
    module: 'general',
    description: '围绕当前业务问题进行分析和解释，必要时给出待确认的办理卡片。',
    dataScope: '按当前账号权限查询业务数据，以表格和图表展示；办业务先出待确认卡片',
    quickPrompts: ['本周排程和产能怎么样？', '本周不良主要出在哪里？'],
  },
  {
    id: 'dashboard',
    label: '运营看板解读',
    shortLabel: '看板解读',
    icon: 'dashboard',
    endpoint: 'dashboard',
    module: 'dashboard',
    description: '汇总生产、质量、库存和设备状态，识别优先事项。',
    dataScope: '生产、质量、库存和设备的后端汇总数据',
    quickPrompts: [
      '解读当前运营看板，列出最需要处理的三项风险。',
      '生产、质量、库存和设备之间有哪些需要联动关注的问题？',
    ],
  },
  {
    id: 'scheduling',
    label: '排程风险解读',
    shortLabel: '排程建议',
    icon: 'route',
    endpoint: 'module',
    module: 'production',
    description: '解释产能、交期和资源风险，不调整或确认排程。',
    dataScope: '按账号权限展示已确认排程、班次周历与正式计划占用',
    quickPrompts: ['请说明当前生产的交期、产能和排程风险，不要修改排程。', '哪些生产风险应该优先在排产工作台中核对？'],
  },
  {
    id: 'maintenance',
    label: '设备保养建议',
    shortLabel: '设备保养',
    icon: 'wrench',
    endpoint: 'module',
    module: 'equipment',
    description: '基于设备状态给出保养核查建议，不创建正式计划。',
    dataScope: '设备运行、停机、故障和保养状态汇总',
    quickPrompts: ['根据当前设备状态，给出本周保养核查优先级。', '哪些设备异常可能影响近期生产，请说明核查顺序。'],
  },
  {
    id: 'quality',
    label: '质量风险分析',
    shortLabel: '质量分析',
    icon: 'shield-check',
    endpoint: 'module',
    module: 'quality',
    description: '解释质量统计变化，辅助确定核查方向。',
    dataScope: '后端质量统计汇总数据',
    quickPrompts: ['分析当前质量风险，并给出优先核查方向。', '质量数据中有哪些异常趋势需要跟进？'],
  },
  {
    id: 'inventory',
    label: '库存异常分析',
    shortLabel: '库存分析',
    icon: 'warehouse',
    endpoint: 'module',
    module: 'inventory',
    description: '识别库存预警及其可能对生产的影响。',
    dataScope: '库存预警相关的后端汇总数据',
    quickPrompts: ['当前库存预警可能对生产造成哪些影响？', '请给出需要优先核对的库存异常清单。'],
  },
  {
    id: 'writing',
    label: '业务智能写作',
    shortLabel: '业务写作',
    icon: 'clipboard',
    endpoint: 'chat',
    module: 'general',
    description: '生成日报、交接摘要和通知草稿，供人工修改确认。',
    dataScope: '仅使用您在对话中提供的信息，不自动下发或保存业务单据',
    quickPrompts: ['请根据我接下来提供的信息，生成一份班组交接摘要草稿。', '请帮我整理一份生产异常跟进通知草稿。'],
  },
];

// 根据能力ID找到对应配置，找不到时回退到通用问答。
function capabilityById(id) {
  return capabilities.find((item) => item.id === id) || capabilities[0];
}

// 当前会话选择的能力；能力只是提问上下文，切换不影响消息。
const activeCapability = computed(() => capabilityById(activeConversation.value?.capabilityId));
// 当前会话没有任何消息时显示欢迎页。
const showWelcome = computed(() => !activeMessages.value.length);
// 模型标识：规则路由命中时后端返回 RULE_ROUTER，需要翻译成人能读的说明。
const modelLabel = computed(() => {
  const model = status.value.model || usage.value?.model;
  if (!model) {
    return '规则路由';
  }
  if (/^rule/i.test(model)) {
    return '规则路由';
  }
  return model;
});
// 将AI状态编码转换成中文服务状态。
const statusPresentation = computed(() => aiStatusPresentation(status.value));
const statusLabel = computed(() => statusPresentation.value.label);
// 将AI状态转换成界面使用的颜色语义。
const statusTone = computed(() => statusPresentation.value.tone);
// 对话或查询任一通道可用就算能发问，供发送前判断复用。
const serviceUsable = computed(() => Boolean(status.value.available || status.value.queryAvailable));
// 服务可用时使用当前能力的推荐问题，否则提示检查服务。
const inputPlaceholder = computed(() =>
  status.value.available || status.value.queryAvailable
    ? activeCapability.value.quickPrompts[0]
    : 'AI 服务暂不可用，请先检查服务状态',
);
// 欢迎页底部的示例问句，覆盖查询与办理两类，让用户知道能问到什么粒度、能办到什么程度。
const exampleQuestions = [
  '我可以让你办哪些业务',
  'L200 工单的报工情况',
  '本周产量与不良率趋势',
  '成品仓当前可发库存',
  '帮我下达工单 WO-',
];
// 能力卡不再挂问句，统一收到底部一排：当前能力前两条 + 四条通用示例，去重后最多六条。
const welcomePrompts = computed(() => {
  const merged = [...activeCapability.value.quickPrompts.slice(0, 2), ...exampleQuestions];
  return [...new Set(merged)];
});

// 后端统一登记的能力目录：左侧「可办理」一栏按这份清单渲染，避免前后端各写一套动作名。
const catalog = ref([]);
const catalogLoading = ref(false);
const catalogError = ref(false);
const actionGroups = computed(() => previewMode ? businessActionPreviewGroups() : businessActionGroups(catalog.value));
const businessActionEntry = ref(null);
const businessDialogOpen = computed({
  get: () => Boolean(businessActionEntry.value),
  set: (value) => {
    if (!value) {
      businessActionEntry.value = null;
    }
  },
});

// 正式会话只使用服务端目录；预览入口不可执行，不把本地样例视为权限。
// 统一取回目录数组：既兼容 { data: [...] } 信封，也兼容直接返回数组的旧实现。
function pickCatalogList(response) {
  const payload = response?.data;
  if (Array.isArray(payload)) {
    return payload;
  }
  return Array.isArray(response) ? response : [];
}

async function loadCapabilities() {
  if (previewMode || catalogLoading.value) return;
  catalogLoading.value = true;
  catalogError.value = false;
  try {
    const response = await getAiCapabilities();
    catalog.value = pickCatalogList(response);
  } catch {
    catalog.value = [];
    catalogError.value = true;
  } finally {
    catalogLoading.value = false;
  }
}

// 点某个办理动作时把「动作名 + 空单号」填进输入框，让用户自己补单号再发送，不代填任何业务数据。
// 创建生产订单没有单号可填，参数是产品或客户，改成一个可选表单直接提交，省掉手打编码。
function fillCommand(capability) {
  if (sending.value || previewMode || capability?.previewOnly) {
    return;
  }
  const code = capability?.code || capability?.action;
  if (code === 'CREATE_PRODUCTION_ORDER') {
    createOrderDialog.value = true;
    return;
  }
  if (code === 'SCHEDULE_PRODUCTION_ORDER') {
    flowDialogAction.value = code;
    return;
  }
  businessActionEntry.value = capability;
}

// 创建生产订单表单：从「可办理」打开时用弹窗，补问气泡里则直接内嵌在消息下方。
const createOrderDialog = ref(false);

// 排产/下达表单的弹窗动作编码，空串表示不显示。
const flowDialogAction = ref('');
const flowDialogOpen = computed({
  get: () => Boolean(flowDialogAction.value),
  set: (value) => {
    if (!value) {
      flowDialogAction.value = '';
    }
  },
});

// 计划器在缺参数时只回一段文字补问，这里按内容判断是不是「创建生产订单」缺参数，
// 是的话直接在气泡里摆出产品、客户、数量和交期的选择项，而不是让用户再打一遍。
function isCreateOrderClarify(message) {
  const intent = String(message?.intent || '').trim();
  if (intent !== 'clarify') {
    return false;
  }
  const text = String(message?.content || '');
  return text.includes('创建生产订单') || text.includes('生产订单草稿');
}

// 表单拿到预览命令后与其它办理入口走同一条路径：只插卡片，不直接建单。
function onCreateOrderPreviewed(command) {
  createOrderDialog.value = false;
  if (!command?.confirmationId) {
    return;
  }
  onActionPreview(command);
}

// 排产、下达工单同理：计划器回「请提供生产订单号 / 工单号」时，把库里真实可办的单据摊成下拉，
// 用户不用去别的页面抄单号。同一句里既提排产又提下达时，两个选择器都摆出来。
function isClarifyMessage(message) {
  return String(message?.intent || '').trim() === 'clarify';
}

// 补问文案是固定模板：「1）要排产的生产订单号（PO-...）；2）要下达的生产工单号（WO-...）」。
// 只认这几个特征词，不能用「排产」两字泛匹配——建单补问里「草稿不等于已排产」也含这两个字，
// 会让创建订单的气泡下面凭空多出一个排产表单。
function needsScheduleForm(message) {
  if (!isClarifyMessage(message)) {
    return false;
  }
  const text = String(message?.content || '');
  return text.includes('要排产') || /生产订单号[（(]\s*PO/.test(text);
}

function needsReleaseForm(message) {
  if (!isClarifyMessage(message)) {
    return false;
  }
  const text = String(message?.content || '');
  return text.includes('要下达') || /工单号[（(]\s*WO/.test(text);
}

// 上下文里的生产订单号：从最近的对话内容里取，用来给排产下拉做预选，
// 这样刚建完订单接着说「排产」时就不用再挑一次。
const recentOrderNo = computed(() => resolveOrderContext(activeMessages.value,
  activeConversation.value?.workflowOrderNo || ''));

function onBusinessPreviewed(command) {
  businessActionEntry.value = null;
  onActionPreview(command);
}

function askRecovery(step) {
  businessActionEntry.value = null;
  question.value = step.question;
  sendQuestion();
}

async function recheckCommand(command) {
  try {
    const response = await previewStructuredCommand({ ...command.params, action: command.action,
      targetNo: command.targetNo, conversationId: activeConversationId.value, source: 'WORKBENCH' });
    if (!response.data?.confirmationId) {
      throw new Error('没有生成有效预览，请重试。');
    }
    onActionPreview(response.data);
  } catch (error) { onActionError(error?.displayMessage || error.message); }
}

// 排产/下达表单拿到预览命令后与建单表单完全同一条路径。
function onFlowPreviewed(command) {
  flowDialogAction.value = '';
  onCreateOrderPreviewed(command);
}

// 只切能力，不动会话：能力是提问上下文，不再是会话边界。
function selectCapability(id) {
  setConversationCapability(id);
  nextTick(() => composer.value?.focus?.());
}

// 新建对话复用空白草稿，避免连续点击堆出一串空会话。
function newConversation() {
  createConversation(activeCapability.value.id);
  question.value = '';
  nextTick(() => composer.value?.focus?.());
}

// 清空是真删除当前会话的消息（带确认），不再是新建对话。
async function clearConversation() {
  if (sending.value) {
    return;
  }
  try {
    await ElMessageBox.confirm('清空后当前会话的历史消息和后台记录都会被删除，无法恢复。', '清空当前会话', {
      confirmButtonText: '清空',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  await clearActiveConversation();
  question.value = '';
}

// 切换会话：归档中的会话被点开时自动恢复。
function openConversation(id) {
  selectConversation(id);
  question.value = '';
  scrollToLatest();
}

// 二次确认后删除会话；最后一个会话删掉后 store 会自动补一条空草稿。
async function deleteConversation(id) {
  const target =
    archivedConversations.value.find((item) => item.id === id) ||
    sessionGroups.value.flatMap((group) => group.items).find((item) => item.id === id);
  try {
    await ElMessageBox.confirm(`确定删除“${target?.title || '该会话'}”吗？删除后无法恢复。`, '删除会话', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning',
    });
  } catch {
    return;
  }
  await removeConversation(id);
}

// 今天的会话显示时分，更早的会话显示月日。
function conversationTime(value) {
  const time = dayjs(value);
  if (!time.isValid()) {
    return '';
  }
  return time.isSame(dayjs(), 'day') ? time.format('HH:mm') : time.format('MM-DD');
}

// 把快捷问题填入输入框并聚焦，仍由用户确认发送。
function selectQuickPrompt(prompt) {
  question.value = prompt;
  nextTick(() => composer.value?.focus?.());
}

// 命令预览卡片本身已经带「确认执行 / 取消」按钮，底部再重复一组建议只会让人误点，所以滤掉。
function visibleSuggestions(message) {
  const list = message?.suggestions || [];
  if (!message?.command) {
    return list;
  }
  return list.filter((item) => !/^(?:确认执行|取消|执行|撤销)$/.test(String(item).trim()));
}

// 处置方案只是「先做什么」的建议，人一走开就断了。这里给一个入口把它变成可跟踪的任务。
function canBuildTask(message) {
  return message?.intent === 'work_order_start_plan' && Boolean(startPlanWorkOrderNo(message));
}

// 工单号从答案正文里取：后端答案固定以「开工处置方案 <工单号>」开头。
// 单号带客户前缀（如 C1-WO-20261031-007），只用 /WO-…/ 会把前缀截掉，
// 后端按残缺单号查不到，会报「未找到工单」。这里连前缀一起取。
function startPlanWorkOrderNo(message) {
  const matched = /[A-Za-z0-9]+-WO-[A-Za-z0-9-]+/.exec(String(message?.content || ''));
  if (matched) {
    return matched[0];
  }
  const bare = /WO-[A-Za-z0-9-]+/.exec(String(message?.content || ''));
  return bare ? bare[0] : '';
}

async function buildTaskFromPlan(message) {
  const workOrderNo = startPlanWorkOrderNo(message);
  if (!workOrderNo || taskBuilding.value) {
    return;
  }
  taskBuilding.value = true;
  try {
    const result = await createTaskFromStartPlan(workOrderNo);
    const data = result.data || {};
    ElMessage.success(`已建成草稿任务 ${data.definitionNo || ''}，共 ${data.stepCount || 0} 步，确认后再启用`);
  } catch (error) {
    ElMessage.error(error?.displayMessage || '生成处置任务失败，请稍后重试');
  } finally {
    taskBuilding.value = false;
  }
}

// 内部路由标签不给普通用户看：后端返回的 PLANNER+DATABASE / RULE_ROUTER 要翻译成业务语言。
// 业务 intent 用能力目录里的中文名，跟左侧能力卡保持一致。
const INTENT_LABELS = {
  RULE_ROUTER: '规则匹配',
  'PLANNER+DATABASE': '实时数据',
  PLANNER: '实时数据',
  DATABASE: '实时数据',
  LLM: '模型生成',
  FALLBACK: '降级回答',
  work_order_list: '工单列表',
  work_order_detail: '工单全息',
  production_daily: '日产量与不良',
  defect_analysis: '不良分析',
  batch_trace: '批次追溯',
  schedule_capacity: '排程与产能',
  order_progress: '订单进度',
  equipment_impact: '设备故障影响',
  capacity_feasibility: '追加产能测算',
  inventory_alert: '库存异常',
  inventory_stock: '库存现有量',
  inventory_below_safety: '低于安全库存',
  customer_order: '客户订单',
  inspection_pending: '待检清单',
  business_writing: '业务智能写作',
  work_order_readiness: '开工条件核查',
  work_order_start_plan: '开工处置方案',
  // 补问和多步/单步预览原来直接显示英文，界面上要能看懂这一步在干什么。
  CLARIFY: '待补充信息',
  COMMAND_PREVIEW: '待确认办理',
};

function intentLabel(message) {
  const raw = String(message?.intent || message?.model || '').trim();
  if (!raw) {
    return '规则匹配';
  }
  const key = raw.toUpperCase();
  if (INTENT_LABELS[key]) {
    return INTENT_LABELS[key];
  }
  // 业务 intent 是后端小写下划线命名，与能力目录一致，先按原样查一次。
  if (INTENT_LABELS[raw]) {
    return INTENT_LABELS[raw];
  }
  if (/^RULE/i.test(raw)) {
    return '规则匹配';
  }
  if (/^PLANNER/i.test(raw)) {
    return '实时数据';
  }
  return raw;
}

// 正文里的长小数按展示口径压到两位，避免出现 1409.6668、0.0000 这类原始读数。
function answerText(message) {
  return formatAiAnswer(message?.content || '');
}

// 历史消息要标明查询时间，口径才对得上「重新查询」。
function queryTimeText(message) {
  if (!message?.queryTime) {
    return '';
  }
  const time = dayjs(message.queryTime);
  return time.isValid() ? time.format('MM-DD HH:mm') : '';
}

// 从后端详情、请求拦截器消息和默认文案中整理AI错误说明。
async function loadStatus() {
  // 查询后端AI开关、配置和模型可用性；预览模式明确不连接模型。
  if (previewMode) {
    status.value = { ...status.value, available: false, queryAvailable: false, state: 'PREVIEW' };
    return;
  }
  if (statusLoading || disposed) {
    return;
  }
  clearTimeout(statusRetryTimer);
  statusLoading = true;
  const recovering = shouldRecheckAiStatus(status.value);
  try {
    const result = await getAiStatus();
    if (!result?.data || typeof result.data.available !== 'boolean') {
      throw new Error('Invalid AI status response');
    }
    if (disposed) {
      return;
    }
    if (recovering) {
      await syncFromBackend();
      if (disposed) {
        return;
      }
      loadUsage();
      loadCapabilities();
    }
    status.value = result.data;
  } catch (error) {
    if (!disposed) {
      status.value = failedAiStatus(status.value, error);
    }
  } finally {
    statusLoading = false;
    if (!disposed && shouldRecheckAiStatus(status.value)) {
      statusRetryTimer = setTimeout(loadStatus, 15000);
    }
  }
}

// 读取后端累计调用量与估算 Token，仅用于侧栏底部信息条。
async function loadUsage() {
  // 用量是锦上添花的信息，接口失败时保持隐藏，不弹错误提示。
  if (previewMode) {
    return;
  }
  try {
    const result = await request.get('/api/ai/usage', { suppressErrorMessage: true });
    usage.value = result?.data || null;
  } catch {
    usage.value = null;
  }
}

// 把消息正文和结果表格拼成纯文本，复制到剪贴板。
async function copyMessage(message) {
  // 表格按 TSV 输出，粘到 Excel 里能自动分列。
  const lines = [message.content || ''];
  (message.blocks || []).forEach((block) => {
    if (!block?.columns?.length || !block?.rows?.length) {
      return;
    }
    lines.push('', block.title || '');
    lines.push(block.columns.map((column) => column.label).join('\t'));
    block.rows.forEach((row) => {
      lines.push(block.columns.map((column) => row[column.prop] ?? '').join('\t'));
    });
  });
  try {
    await navigator.clipboard.writeText(lines.filter((line) => line !== undefined).join('\n'));
    copiedMessageId.value = message.id;
    clearTimeout(copiedTimer);
    copiedTimer = setTimeout(() => {
      copiedMessageId.value = '';
    }, 1600);
  } catch {
    // 浏览器拒绝剪贴板权限时静默失败，用户仍可手动选中复制。
  }
}

// 命令执行结束后：记录状态，成功时广播一次数据变更，让列表页和待办面板重新拉数据。
function onCommandResolved(message, payload) {
  const commandStatus = String(payload?.status || '').toUpperCase();
  message.commandStatus = commandStatus || 'DONE';
  if (commandStatus === 'SUCCEEDED') {
    message.commandResult = payload?.result || payload;
    const orderNo = resolveOrderContext([message]);
    if (orderNo && activeConversation.value) {
      activeConversation.value.workflowOrderNo = orderNo;
    }
    persistConversations();
  }
  if (commandStatus === 'SUCCEEDED' || commandStatus === 'PARTIAL_FAILED') {
    notifyDataChanged({ action: message.command?.action, bizNo: message.command?.targetNo });
  }
}

function onCommandRestored(message, payload) {
  if (String(payload?.status || '').toUpperCase() === 'SUCCEEDED') {
    message.commandStatus = 'SUCCEEDED';
    message.commandResult = payload.result || {};
    persistConversations();
  }
}

async function openPieceInspection(message, command) {
  try {
    const response = await getResourceDetail('/api/qms/inspection-order', command.targetId);
    const inspection = response?.data;
    if (!inspection?.inspectionId || inspection.inspectionNo !== command.targetNo) {
      throw new Error('检验单已变化，请重新查询后办理');
    }
    if (inspection.status !== 'DRAFT') {
      throw new Error('检验单已不是待检状态，请重新查询');
    }
    pieceInspection.value = inspection;
    pieceInspectionMessage.value = message;
    pieceInspectionOpen.value = true;
  } catch (error) {
    ElMessage.warning(error?.message || '检验单读取失败，请稍后重试');
  }
}

function onPieceInspectionCompleted() {
  if (pieceInspectionMessage.value) {
    pieceInspectionMessage.value.pieceInspectionCompleted = true;
    persistConversations();
  }
  notifyDataChanged({ action: 'SUBMIT_PIECE_INSPECTION', bizNo: pieceInspection.value?.inspectionNo });
}

// 多步执行结束：效果同单步，部分失败也算「已经办了业务」，必须刷新列表与待办。
function onSequenceResolved(message, payload) {
  const sequenceStatus = String(payload?.status || '').toUpperCase();
  message.sequenceStatus = sequenceStatus || 'DONE';
  if (sequenceStatus === 'SUCCEEDED' || sequenceStatus === 'PARTIAL_FAILED') {
    notifyDataChanged({ planId: message.sequence?.planId, action: 'SEQUENCE' });
  }
  // 无论成败都按 planId 回读一次，卡片上每一步的结果以服务端记录为准。
  refreshSequence(message);
}

// 按 planId 回读多步计划，失败静默：卡片保留已有状态，用户仍可从失败步骤继续。
async function refreshSequence(message) {
  const planId = message?.sequence?.planId;
  if (!planId) {
    return;
  }
  try {
    const response = await getAgentSequence(planId);
    const data = response?.data;
    if (data && typeof data === 'object') {
      message.sequence = { ...message.sequence, ...data };
    }
  } catch {
    // 回读失败不打扰用户。
  }
}

// 回答里的办理按钮点开后拿到预览命令，作为一条新消息插进当前会话，卡片由 AiCommandCard 渲染。
function onActionPreview(command) {
  if (!command) {
    return;
  }
  appendCommandMessage(command, { capabilityId: activeConversation.value?.capabilityId || 'general' });
  scrollToLatest();
}

// 按钮没带单号时先弹候选列表：用户自己选一张，再用「动作名 + 单号」去要预览。
const candidateCapability = ref('');
const candidateActionLabel = ref('');

function onNeedCandidates(payload) {
  candidateCapability.value = payload?.capability || '';
  candidateActionLabel.value = payload?.actionLabel || '';
}

function onCandidatePicked(payload) {
  const candidate = payload?.candidate;
  candidateCapability.value = '';
  if (!candidate?.bizNo) {
    return;
  }
  const label = payload?.actionLabel || '';
  if (!label) {
    return;
  }
  requestPreviewFor(`${label} ${candidate.bizNo}`, payload.capability);
}

// 与 AiActionButtons 内部同一条路径：先预览、再插卡片，绝不直接执行。
async function requestPreviewFor(text, capability) {
  try {
    const response = await previewAgentCommand({
      text,
      conversationId: activeConversationId.value || undefined,
      source: 'WORKBENCH',
      context: { capability: capability || '' },
    });
    const command = response?.data || {};
    if (!command.confirmationId) {
      onActionError(command.message || '没能生成办理预览，请到对应页面手工办理。');
      return;
    }
    onActionPreview(command);
  } catch (error) {
    onActionError(error?.displayMessage || error?.data?.message || '办理预览暂不可用，请稍后重试。');
  }
}

// 预览拉取失败只在提示层说一句，不在对话里新增一条错误气泡，避免把正常回答冲掉。
function onActionError(text) {
  ElMessage.warning(text || '办理预览暂不可用，请到对应页面手工办理。');
}

// 重新生成：沿用这条回答前面最近的一条用户提问，再请求一次。
function regenerateMessage(message) {
  if (sending.value || !(status.value.available || status.value.queryAvailable)) {
    return;
  }
  const previousQuestion = regenerateAnswer(message.id);
  if (!previousQuestion) {
    return;
  }
  question.value = previousQuestion;
  sendQuestion();
}

// 读取失败消息保存的原问题并重新发送。
// 重试要带上原来的 clientMessageId：后端据此判断这条提问已经落过库，不会再加一条重复的历史。
function retryMessage(message) {
  if (sending.value || !serviceUsable.value || !message.retryQuestion) {
    return;
  }
  question.value = message.retryQuestion;
  sendQuestion({ clientMessageId: message.retryClientMessageId });
}

// 小屏抽屉开关；大屏下侧栏常驻，该状态不影响布局。
function toggleSidebar() {
  sidebarOpen.value = !sidebarOpen.value;
}

// 形态切换：工作台内嵌 / 右下角悬浮窗，两边共用同一份 store 数据。
const showDockNotice = computed(() => mode.value === 'dock');

function switchMode(next) {
  if (next === 'dock') {
    setMode('dock');
    setDockOpen(true);
    return;
  }
  setMode('workbench');
  // 必须同时收起浮窗：浮窗是 fixed 定位，留着会压住底部输入区，发送按钮点不到。
  setDockOpen(false);
  nextTick(() => composer.value?.focus?.());
}

// 悬浮形态下切回工作台：消息本来就在同一个 store 里，只需切形态。
function backToWorkbench() {
  setMode('workbench');
  setDockOpen(false);
  nextTick(() => scrollToLatest());
}

// 发送一轮问答：消息追加、接口调用、失败兜底都在 store 里完成。
async function sendQuestion(options = {}) {
  const content = question.value.trim();
  if (!content || sending.value || !serviceUsable.value) {
    return;
  }
  const capability = activeCapability.value;
  question.value = '';
  scrollToLatest();
  await sendMessage(content, {
    capabilityId: capability.id,
    module: capability.module,
    source: 'ai-workbench',
    clientMessageId: options.clientMessageId,
  });
  scrollToLatest();
}

// 取消当前请求：取消后不追加失败消息。
function cancelRequest() {
  cancelSend();
}

// 处理输入框键盘操作：Enter发送，Shift+Enter换行，输入法组合时不触发。
function handleKeydown(event) {
  // 中文输入法组合阶段不提交；Enter发送，Shift+Enter保留为换行。
  if (event.isComposing || event.shiftKey || event.key !== 'Enter') {
    return;
  }
  event.preventDefault();
  sendQuestion();
}

// Vue更新消息DOM后，把Element Plus会话滚动区移动到最底部。
function scrollToLatest() {
  nextTick(() => {
    const wrap =
      conversationScroll.value?.wrapRef || conversationScroll.value?.$el?.querySelector('.el-scrollbar__wrap');
    if (wrap) {
      wrap.scrollTop = wrap.scrollHeight;
    }
  });
}

// 挂载时先用本地缓存渲染，再让后端历史接管；接口不可用则保持本机记录。
onMounted(() => {
  persistConversations();
  loadStatus();
  loadUsage();
  loadCapabilities();
  if (!previewMode) {
    syncFromBackend().then((synced) => {
      if (synced) {
        scrollToLatest();
      }
    });
  }
});

// 离开页面时取消仍在进行的模型请求。
onBeforeUnmount(() => {
  disposed = true;
  clearTimeout(statusRetryTimer);
  cancelRequest();
  clearTimeout(copiedTimer);
});
</script>

<template>
  <!-- 左侧管理能力与会话，中间展示消息，底部输入区负责发送、停止和重试。 -->
  <section class="ai-workbench">
    <button
      class="sidebar-toggle"
      type="button"
      :aria-expanded="sidebarOpen ? 'true' : 'false'"
      aria-label="打开会话列表"
      @click="toggleSidebar"
    >
      <AppIcon name="menu" :size="16" />
    </button>

    <div class="wb-frame" :class="{ 'is-sidebar-open': sidebarOpen }">
      <!-- 侧栏：品牌、新建对话、能力清单、按时间分组的会话记录、模型用量。 -->
      <aside class="wb-sidebar">
        <div class="wb-brand">
          <span class="brand-mark"><AppIcon name="factory" :size="15" /></span>
          <div class="brand-text">
            <strong>{{ appName }}</strong>
            <span>AI 数据助手</span>
          </div>
          <span class="status-dot" :class="`is-${statusTone}`" :title="statusLabel" />
        </div>

        <div class="wb-new">
          <button class="new-conversation" type="button" :disabled="sending" @click="newConversation">
            <AppIcon name="plus" :size="14" />新建对话
          </button>
        </div>

        <!-- 中部内容独立滚动：底部模型用量区常驻，不被内容挤出视口。 -->
        <div class="wb-body">
          <div class="wb-group wb-group-catalog" :aria-busy="catalogLoading">
            <div class="group-head">快捷办理</div>
            <p v-if="previewMode" class="catalog-message">当前仅预览入口，登录后按账号权限办理。</p>
            <p v-else-if="catalogLoading" class="catalog-message" role="status">正在加载快捷入口…</p>
            <div v-else-if="catalogError" class="catalog-message" role="status">
              <p>快捷入口未能加载，请检查后端连接。</p>
              <button class="ghost-button" type="button" @click="loadCapabilities">重新加载入口</button>
            </div>
            <p v-else-if="!actionGroups.length" class="catalog-message">当前账号暂无可办理入口。</p>
            <div v-for="group in actionGroups" :key="group.id" class="catalog-phase">
              <div class="catalog-phase-title">{{ group.label }}</div>
              <div class="catalog-grid">
                <button
                  v-for="item in group.items"
                  :key="item.flowKey"
                  class="command-chip"
                  type="button"
                  :disabled="sending || previewMode"
                  :title="previewMode ? '界面预览：连接后端并登录后，按账号权限开放办理' : item.description"
                  :aria-label="`办理：${item.label}`"
                  @click="fillCommand(item)"
                >
                  <AppIcon name="play" :size="11" />
                  <span>{{ item.label }}</span>
                </button>
              </div>
            </div>
          </div>
          <div class="wb-group wb-group-history">
            <div class="group-head">会话记录</div>
            <el-scrollbar class="history-scroll">
              <template v-for="group in sessionGroups" :key="group.key">
                <div class="history-sub">{{ group.label }}</div>
                <div
                  v-for="conversation in group.items"
                  :key="conversation.id"
                  class="session-row"
                  :class="{ active: activeConversationId === conversation.id }"
                >
                  <button class="session-main" type="button" @click="openConversation(conversation.id)">
                    <span class="session-title">{{ conversation.title }}</span>
                    <small>{{ conversationTime(conversation.updatedAt) }}</small>
                  </button>
                  <div class="session-actions">
                    <el-tooltip content="归档会话" placement="top">
                      <button type="button" aria-label="归档会话" @click.stop="archiveConversation(conversation.id)">
                        <AppIcon name="package" :size="12" />
                      </button>
                    </el-tooltip>
                    <el-tooltip content="删除会话" placement="top">
                      <button type="button" aria-label="删除会话" @click.stop="deleteConversation(conversation.id)">
                        <AppIcon name="trash" :size="12" />
                      </button>
                    </el-tooltip>
                  </div>
                </div>
              </template>

              <template v-if="archivedConversations.length">
                <div class="history-sub">已归档</div>
                <div
                  v-for="conversation in archivedConversations"
                  :key="conversation.id"
                  class="session-row is-archived"
                  :class="{ active: activeConversationId === conversation.id }"
                >
                  <button class="session-main" type="button" @click="openConversation(conversation.id)">
                    <span class="session-title">{{ conversation.title }}</span>
                    <small>{{ conversationTime(conversation.updatedAt) }}</small>
                  </button>
                  <div class="session-actions">
                    <el-tooltip content="恢复会话" placement="top">
                      <button type="button" aria-label="恢复会话" @click.stop="restoreConversation(conversation.id)">
                        <AppIcon name="refresh" :size="12" />
                      </button>
                    </el-tooltip>
                    <el-tooltip content="删除会话" placement="top">
                      <button type="button" aria-label="删除会话" @click.stop="deleteConversation(conversation.id)">
                        <AppIcon name="trash" :size="12" />
                      </button>
                    </el-tooltip>
                  </div>
                </div>
              </template>

              <p v-if="!sessionGroups.length && !archivedConversations.length" class="history-empty">还没有对话记录</p>
            </el-scrollbar>
          </div>

          <details class="wb-group">
            <summary class="group-head">AI 解读</summary>
            <div class="capability-list">
              <button
                v-for="capability in capabilities"
                :key="capability.id"
                class="capability-item"
                :class="{ active: activeCapability.id === capability.id }"
                type="button"
                :disabled="sending"
                :title="capability.description"
                @click="selectCapability(capability.id)"
              >
                <span class="capability-icon"><AppIcon :name="capability.icon" :size="15" /></span>
                <span class="capability-text">
                  <span class="capability-name">{{ capability.label }}</span>
                  <span class="capability-desc">{{ capability.description }}</span>
                </span>
              </button>
            </div>
          </details>

        </div>

        <div class="wb-foot">
          <div class="foot-row">
            <span>模型</span><b>{{ modelLabel }}</b>
          </div>
          <div v-if="usage" class="foot-row">
            <span>累计调用</span><b>{{ usage.totalCalls ?? 0 }}</b>
          </div>
          <div v-if="usage" class="foot-row">
            <span>估算 Token</span><b>{{ usage.estimatedTokens ?? 0 }}</b>
          </div>
          <div class="foot-row">
            <span>历史</span><b>{{ historySynced ? '后台记录' : '本机记录' }}</b>
          </div>
        </div>
      </aside>

      <section class="wb-chat">
        <header class="chat-head">
          <div class="head-main">
            <h1>{{ activeCapability.label }}</h1>
            <p>{{ activeCapability.dataScope }}</p>
          </div>
          <div class="head-side">
            <!-- 形态切换：工作台内嵌 / 右下角悬浮窗，两种形态共用同一份会话数据。 -->
            <div class="mode-switch" role="group" aria-label="AI 助手形态">
              <button
                type="button"
                :class="{ active: mode === 'workbench' }"
                :aria-pressed="mode === 'workbench' ? 'true' : 'false'"
                @click="switchMode('workbench')"
              >
                工作台
              </button>
              <button
                type="button"
                :class="{ active: mode === 'dock' }"
                :aria-pressed="mode === 'dock' ? 'true' : 'false'"
                @click="switchMode('dock')"
              >
                悬浮
              </button>
            </div>
            <span class="status-badge" :class="`is-${statusTone}`"><i />{{ statusLabel }}</span>
            <el-tooltip content="清空当前会话的历史消息" placement="top">
              <button
                class="ghost-button"
                type="button"
                :disabled="sending || !activeMessages.length"
                @click="clearConversation"
              >
                <AppIcon name="trash" :size="13" />清空
              </button>
            </el-tooltip>
          </div>
        </header>

        <!-- 悬浮形态：对话在右下角浮窗里继续，页面只保留说明和切回入口。 -->
        <div v-if="showDockNotice" class="dock-notice">
          <span class="dock-notice-icon"><AppIcon name="bot" :size="18" /></span>
          <div class="dock-notice-text">
            <strong>对话已迁移到右下角悬浮助手</strong>
          </div>
          <div class="dock-notice-actions">
            <button class="primary-button" type="button" @click="setDockOpen(true)">打开浮窗</button>
            <button class="ghost-button" type="button" @click="backToWorkbench">切回工作台</button>
          </div>
        </div>

        <div
          v-else-if="!(status.available || status.queryAvailable)"
          class="service-notice"
          :class="`is-${statusTone}`"
        >
          <AppIcon name="alert" :size="15" />
          <div>
            <strong>{{ statusLabel }}</strong>
            <span>{{ statusPresentation.detail }}</span>
          </div>
          <button
            class="ghost-button"
            type="button"
            @click="previewMode ? router.push({ path: '/login', query: { redirect: '/ai/workbench' } }) : loadStatus()"
          >{{ previewMode ? '连接并登录' : '重新检查' }}</button>
        </div>

        <el-scrollbar v-if="!showDockNotice" ref="conversationScroll" class="chat-scroll">
          <main class="chat-inner">
            <section v-if="showWelcome" class="welcome">
              <div class="welcome-head">
                <h2>用一句话查询或办理业务</h2>
              </div>

              <!-- 能力卡片：每张卡挂两条推荐问句，点卡片只切上下文，点问句只填输入框。 -->
              <div class="welcome-grid">
                <article
                  v-for="capability in capabilities"
                  :key="capability.id"
                  class="welcome-card"
                  :class="{ active: activeCapability.id === capability.id }"
                >
                  <button class="welcome-card-head" type="button" :aria-pressed="activeCapability.id === capability.id" @click="selectCapability(capability.id)">
                    <span class="welcome-card-icon"><AppIcon :name="capability.icon" :size="15" /></span>
                    <span class="welcome-card-text">
                      <strong>{{ capability.label }}</strong>
                      <span>{{ capability.description }}</span>
                    </span>
                  </button>
                  <div class="welcome-card-prompts">
                    <button
                      v-for="prompt in capability.quickPrompts.slice(0, 2)"
                      :key="prompt"
                      type="button"
                      @click="selectQuickPrompt(prompt)"
                    >
                      <AppIcon name="arrow" :size="11" />
                      <span>{{ prompt }}</span>
                    </button>
                  </div>
                </article>
              </div>

              <details class="welcome-examples">
                <summary class="welcome-examples-label">更多提问示例</summary>
                <div class="welcome-pills">
                  <button
                    v-for="example in welcomePrompts"
                    :key="example"
                    type="button"
                    @click="selectQuickPrompt(example)"
                  >
                    {{ example }}
                  </button>
                </div>
              </details>

            </section>

            <section v-else class="message-list">
              <article v-for="message in activeMessages" :key="message.id" class="msg" :class="`is-${message.role}`">
                <template v-if="message.role === 'user'">
                  <div class="msg-user">
                    <p>{{ message.content }}</p>
                    <span class="msg-user-time">{{ message.time }}</span>
                  </div>
                </template>

                <template v-else-if="message.role === 'assistant'">
                  <div class="msg-head">
                    <span class="msg-avatar"><AppIcon name="bot" :size="13" /></span>
                    <span class="msg-author">智能助手</span>
                    <span class="msg-dot">·</span>
                    <span class="msg-source">{{ intentLabel(message) }}</span>
                    <span v-if="queryTimeText(message)" class="msg-dot">·</span>
                    <span v-if="queryTimeText(message)" class="msg-query-time"
                      >数据时间 {{ queryTimeText(message) }}</span
                    >
                    <button
                      v-if="message.fromHistory"
                      class="msg-requery"
                      type="button"
                      :disabled="sending"
                      @click="regenerateMessage(message)"
                    >
                      重新查询
                    </button>
                  </div>
                  <div class="msg-body">
                    <p class="msg-text">{{ answerText(message) }}</p>
                    <AiResultBlocks v-if="message.blocks?.length" :blocks="message.blocks" />
                    <!-- 有业务数据没读到时直接说明缺哪一项，避免用户把不完整的结论当成全量结论。 -->
                    <p v-if="message.dataGaps?.length" class="msg-data-gaps">
                      未取到：{{ message.dataGaps.join('、') }}
                    </p>
                    <!-- 创建生产订单缺参数时不再让用户手打编码，直接把可选产品和客户摆出来。 -->
                    <AiCreateOrderForm
                      v-if="isCreateOrderClarify(message)"
                      :conversation-id="activeConversationId"
                      :disabled="sending"
                      @previewed="onCreateOrderPreviewed"
                      @error="onActionError"
                    />

                    <!-- 排产、下达工单缺单据号时同理：把库里真实可办的单据摊成下拉，用户不用抄单号。 -->
                    <AiProductionFlowForm
                      v-if="needsScheduleForm(message)"
                      :key="`schedule-${message.id}`"
                      action="SCHEDULE_PRODUCTION_ORDER"
                      :conversation-id="activeConversationId"
                      :disabled="sending"
                      :default-order-no="recentOrderNo"
                      @previewed="onFlowPreviewed"
                      @error="onActionError"
                    />
                    <AiProductionFlowForm
                      v-if="needsReleaseForm(message)"
                      :key="`release-${message.id}`"
                      action="RELEASE_WORK_ORDER"
                      :conversation-id="activeConversationId"
                      :disabled="sending"
                      @previewed="onFlowPreviewed"
                      @error="onActionError"
                    />

                    <!-- 写命令预览只渲染卡片，真正执行必须由用户点「确认执行」。 -->
                    <AiCommandCard
                      v-if="message.command"
                      :command="message.command"
                      :inspection-completed="Boolean(message.pieceInspectionCompleted)"
                      @resolved="(payload) => onCommandResolved(message, payload)"
                      @restored="(payload) => onCommandRestored(message, payload)"
                      @piece-inspection="(command) => openPieceInspection(message, command)"
                      @recheck="recheckCommand"
                      @recover="askRecovery"
                    />

                    <!-- 一句话连着办几件事时渲染多步卡片：确认后按顺序执行，某步失败可从那里续跑。 -->
                    <AiCommandSequenceCard
                      v-if="message.sequence && !message.command"
                      :sequence="message.sequence"
                      @resolved="(payload) => onSequenceResolved(message, payload)"
                    />

                    <!-- 后端在回答里给了能办的动作时列在正文下方；办理类只生成预览，仍需人工确认。 -->
                    <AiActionButtons
                      v-if="message.actions?.length && !message.command && !message.sequence"
                      :actions="message.actions"
                      :conversation-id="activeConversationId"
                      :disabled="sending"
                      @preview="(command) => onActionPreview(command)"
                      @need-candidates="onNeedCandidates"
                      @error="onActionError"
                    />

                    <!-- 处置方案只是建议，给一个入口把它变成可跟踪的任务，人走开也不会断。 -->
                    <div v-if="canBuildTask(message)" class="msg-task-action">
                      <button type="button" :disabled="taskBuilding" @click="buildTaskFromPlan(message)">
                        {{ taskBuilding ? '生成中…' : '把这几步建成任务' }}
                      </button>
                    </div>

                    <div v-if="visibleSuggestions(message).length" class="msg-suggestions">
                      <span class="suggestions-label">继续追问</span>
                      <button
                        v-for="suggestion in visibleSuggestions(message)"
                        :key="suggestion"
                        type="button"
                        @click="selectQuickPrompt(suggestion)"
                      >
                        {{ suggestion }}
                      </button>
                    </div>

                    <div class="msg-foot">
                      <p class="msg-disclaimer">{{ message.disclaimer || activeCapability.dataScope }}</p>
                      <div class="msg-actions">
                        <el-tooltip :content="copiedMessageId === message.id ? '已复制' : '复制回答'" placement="top">
                          <button type="button" aria-label="复制回答" @click="copyMessage(message)">
                            <AppIcon :name="copiedMessageId === message.id ? 'check' : 'clipboard'" :size="13" />
                          </button>
                        </el-tooltip>
                        <el-tooltip content="重新生成" placement="top">
                          <button
                            type="button"
                            aria-label="重新生成"
                            :disabled="sending"
                            @click="regenerateMessage(message)"
                          >
                            <AppIcon name="refresh" :size="13" />
                          </button>
                        </el-tooltip>
                      </div>
                    </div>
                  </div>
                </template>

                <template v-else>
                  <div class="msg-error">
                    <AppIcon name="alert" :size="14" />
                    <div>
                      <p>{{ message.content }}</p>
                      <button v-if="message.retryQuestion" type="button" @click="retryMessage(message)">
                        <AppIcon name="refresh" :size="12" />重新尝试
                      </button>
                    </div>
                  </div>
                </template>
              </article>

              <article v-if="sending" class="msg is-assistant">
                <div class="msg-head">
                  <span class="msg-avatar"><AppIcon name="bot" :size="13" /></span>
                  <span class="msg-author">智能助手</span>
                  <span class="msg-dot">·</span>
                  <span class="msg-source">处理中</span>
                </div>
                <ol class="thinking-steps">
                  <li class="step is-1">正在解析问题</li>
                  <li class="step is-2">检索业务数据</li>
                  <li class="step is-3">生成结论</li>
                </ol>
              </article>
            </section>
          </main>
        </el-scrollbar>

        <footer v-if="!showDockNotice" class="compose">
          <div class="compose-box" :class="{ disabled: !(status.available || status.queryAvailable) }">
            <el-input
              ref="composer"
              v-model="question"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 5 }"
              maxlength="500"
              resize="none"
              :disabled="!(status.available || status.queryAvailable) || sending"
              :placeholder="inputPlaceholder"
              @keydown="handleKeydown"
            />
            <div class="compose-bar">
              <el-dropdown trigger="click" :disabled="sending" @command="selectCapability">
                <button class="compose-capability" type="button" :disabled="sending">
                  <AppIcon :name="activeCapability.icon" :size="13" />
                  <span>{{ activeCapability.shortLabel }}</span>
                  <AppIcon name="chevron" :size="11" />
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="capability in capabilities" :key="capability.id" :command="capability.id">
                      {{ capability.label }}
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
              <span class="compose-count">{{ question.length }}/500</span>
              <el-tooltip :content="sending ? '停止生成' : '发送问题'" placement="top">
                <button
                  v-if="sending"
                  class="send-button is-stop"
                  type="button"
                  aria-label="停止生成"
                  @click="cancelRequest"
                >
                  <AppIcon name="close" :size="15" />
                </button>
                <button
                  v-else
                  class="send-button"
                  type="button"
                  aria-label="发送问题"
                  :disabled="!(status.available || status.queryAvailable) || !question.trim()"
                  @click="sendQuestion"
                >
                  <AppIcon name="arrow" :size="15" />
                </button>
              </el-tooltip>
            </div>
          </div>
          <p class="compose-hint">
            <span>Enter 发送 / Shift+Enter 换行</span>
          </p>
        </footer>
      </section>
    </div>

    <!-- 没带单号的办理按钮弹这里：先列候选，用户挑一张再走预览。 -->
    <AiCandidatePicker
      :capability="candidateCapability"
      :action-label="candidateActionLabel"
      @pick="onCandidatePicked"
      @close="candidateCapability = ''"
    />

    <PieceInspectionDialog
      v-model="pieceInspectionOpen"
      :inspection="pieceInspection"
      @completed="onPieceInspectionCompleted"
    />

    <!-- 「可办理」里点创建生产订单时弹这个表单：选产品、客户、数量、交期，选完生成预览。 -->
    <el-dialog v-model="businessDialogOpen" :title="businessActionEntry?.label || '办理业务'" width="560px" append-to-body>
      <AiBusinessActionForm v-if="businessActionEntry" :key="businessActionEntry.flowKey"
        :entry="businessActionEntry" :order-no="recentOrderNo" :conversation-id="activeConversationId"
        @previewed="onBusinessPreviewed" @question="(text) => askRecovery({ question: text })" />
    </el-dialog>
    <el-dialog v-model="createOrderDialog" title="创建生产订单" width="560px" append-to-body>
      <AiCreateOrderForm
        v-if="createOrderDialog"
        :conversation-id="activeConversationId"
        :disabled="sending"
        @previewed="onCreateOrderPreviewed"
        @error="onActionError"
      />
    </el-dialog>

    <!-- 「可办理」里点排产或下达工单时弹这个：单据从库里真实候选里选，不手填单号。 -->
    <el-dialog
      v-model="flowDialogOpen"
      :title="flowDialogAction === 'SCHEDULE_PRODUCTION_ORDER' ? '安排生产订单' : '下达生产工单'"
      width="560px"
      append-to-body
    >
      <AiProductionFlowForm
        v-if="flowDialogOpen && flowDialogAction"
        :key="`dialog-${flowDialogAction}`"
        :action="flowDialogAction"
        :conversation-id="activeConversationId"
        :disabled="sending"
        :default-order-no="recentOrderNo"
        @previewed="onFlowPreviewed"
        @error="onActionError"
      />
    </el-dialog>
  </section>
</template>

<style scoped src="./workbench.css"></style>
