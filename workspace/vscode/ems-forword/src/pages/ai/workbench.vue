<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import dayjs from 'dayjs';
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
import { businessActionGroups } from '@/utils/ai-business-flow';
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
const actionGroups = computed(() => businessActionGroups(catalog.value));
const businessActionEntry = ref(null);
const businessDialogOpen = computed({
  get: () => Boolean(businessActionEntry.value),
  set: (value) => { if (!value) {businessActionEntry.value = null;} },
});

// 目录拉取失败时保持空数组，页面仍可用本地七种工作模式，不弹错打断演示。
// 统一取回目录数组：既兼容 { data: [...] } 信封，也兼容直接返回数组的旧实现。
function pickCatalogList(response) {
  const payload = response?.data;
  if (Array.isArray(payload)) {
    return payload;
  }
  return Array.isArray(response) ? response : [];
}

async function loadCapabilities() {
  try {
    const response = await getAiCapabilities();
    catalog.value = pickCatalogList(response);
  } catch {
    catalog.value = [];
  }
}

// 点某个办理动作时把「动作名 + 空单号」填进输入框，让用户自己补单号再发送，不代填任何业务数据。
// 创建生产订单没有单号可填，参数是产品或客户，改成一个可选表单直接提交，省掉手打编码。
function fillCommand(capability) {
  if (sending.value) {
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
    if (!response.data?.confirmationId) {throw new Error('没有生成有效预览，请重试。');}
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
    if (orderNo && activeConversation.value) {activeConversation.value.workflowOrderNo = orderNo;}
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
  syncFromBackend().then((synced) => {
    if (synced) {
      scrollToLatest();
    }
  });
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

          <div v-if="actionGroups.length" class="wb-group wb-group-catalog">
            <div class="group-head">可办理</div>
            <div v-for="group in actionGroups" :key="group.id" class="catalog-phase">
              <div class="catalog-phase-title">{{ group.label }}</div>
              <div class="catalog-grid">
              <button
                v-for="item in group.items"
                :key="item.flowKey"
                class="command-chip"
                type="button"
                :disabled="sending"
                :title="item.description"
                :aria-label="`办理：${item.label}`"
                @click="fillCommand(item)"
              >
                <AppIcon name="play" :size="11" />
                <span>{{ item.label }}</span>
              </button>
              </div>
            </div>
          </div>

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
          <button class="ghost-button" type="button" @click="loadStatus">重新检查</button>
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
                  <button class="welcome-card-head" type="button" @click="selectCapability(capability.id)">
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

              <div class="welcome-examples">
                <span class="welcome-examples-label">你可以这样问</span>
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
              </div>

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

<style scoped>
/*
 * 视觉基线：中性灰阶 + 单一主色，1px 细边框、小圆角、靠间距建立层次。
 * 所有颜色优先取 tokens.css 的变量，只有本页专有的灰阶在 .ai-workbench 上局部定义，
 * 这样深色主题换肤时不需要改这里。
 */
.ai-workbench {
  --wb-line: var(--mes-border, #e6eaf0);
  --wb-line-soft: #eef1f5;
  --wb-bg: var(--mes-surface-muted, #f7f8fa);
  --wb-panel: var(--mes-surface, #fff);
  --wb-text: var(--mes-text, #10151d);
  --wb-text-2: var(--mes-text-secondary, #4b5563);
  --wb-text-3: var(--mes-text-muted, #8b95a3);
  --wb-hover: #f4f6f9;
  --wb-selected: var(--mes-primary-soft, #eaf2ff);
  --wb-radius: var(--mes-radius, 8px);
  --wb-radius-sm: var(--mes-radius-sm, 6px);
  position: relative;
  display: flex;
  width: 100%;
  min-width: 0;
  flex-direction: column;
  color: var(--wb-text);
  /* layout 的内容区只有 min-height，这里必须自己锁一屏高，
     否则消息一多整页被撑长，底部输入区被顶出视口。
     末尾 46px 是内容区上下内边距（18+28）。 */
  height: max(560px, calc(100dvh - var(--mes-header-height, 60px) - var(--mes-tabs-height, 40px) - 46px));
  overflow: hidden;
  border: 1px solid var(--wb-line);
  border-radius: var(--wb-radius);
  background: var(--wb-panel);
}

/* 小屏侧栏改为抽屉，这个按钮只在 1100px 以下出现。 */
.sidebar-toggle {
  position: absolute;
  z-index: 12;
  top: 10px;
  left: 10px;
  display: none;
  width: 30px;
  height: 30px;
  align-items: center;
  justify-content: center;
  color: var(--wb-text-2);
  border: 1px solid var(--wb-line);
  border-radius: var(--wb-radius-sm);
  background: var(--wb-panel);
  cursor: pointer;
}

.wb-frame {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

/* ---------- 左侧栏 ---------- */

/* 侧栏自身不滚动：中部 wb-body 才是滚动容器，品牌/新建/用量区固定不动。 */
.wb-sidebar {
  display: flex;
  width: 268px;
  min-width: 0;
  min-height: 0;
  flex: none;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--wb-line);
  background: var(--wb-panel);
}

.wb-brand {
  display: flex;
  flex: none;
  align-items: center;
  gap: 9px;
  padding: 10px 14px 10px;
}

.brand-mark {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: var(--wb-radius-sm);
  background: var(--mes-primary-soft, #eaf2ff);
}

.brand-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  line-height: 1.35;
}

.brand-text strong {
  overflow: hidden;
  color: var(--wb-text);
  font-size: 12.5px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-text span {
  color: var(--wb-text-3);
  font-size: 10.5px;
}

.status-dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--wb-text-3);
}

.status-dot.is-ready {
  background: var(--mes-success, #12a150);
}
.status-dot.is-warning {
  background: var(--mes-warning, #d98a00);
}
.status-dot.is-offline {
  background: var(--wb-text-3);
}

.wb-new {
  flex: none;
  padding: 0 14px 8px;
}

.new-conversation {
  display: flex;
  width: 100%;
  height: 34px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #fff;
  font-size: 12.5px;
  font-weight: 500;
  border: 1px solid var(--mes-primary);
  border-radius: var(--wb-radius-sm);
  background: var(--mes-primary);
  cursor: pointer;
  transition: background-color 140ms ease;
}

.new-conversation:hover:not(:disabled) {
  background: var(--mes-primary-hover, #1859c4);
}

.new-conversation:disabled {
  color: var(--wb-text-3);
  border-color: var(--wb-line);
  background: var(--wb-hover);
  cursor: not-allowed;
}

/* 中部内容独立滚动：能力清单 + 可办理 + 会话记录都放这里，
   底部模型用量区常驻，内容再多也不会被顶出视口。 */
.wb-body {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* 各分区不参与收缩：内容多的分区靠 wb-body 整体滚动，不能被压扁后叠在一起。 */
.wb-group {
  display: flex;
  min-height: 0;
  flex: none;
  flex-direction: column;
  padding: 0 8px;
}

/* 会话记录有自己的一层滚动，限高后不再挤占其它分区。 */
.wb-group-history {
  flex: none;
  overflow: hidden;
  margin-top: 4px;
}

.group-head {
  padding: 8px 6px 6px;
  color: var(--wb-text-3);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.capability-list {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 1px;
}

/* 能力项：图标 + 名称 + 一句话描述，选中时左侧竖条 + 浅底 + 主色图标。 */
.capability-item {
  position: relative;
  display: flex;
  width: 100%;
  align-items: flex-start;
  gap: 7px;
  padding: 4px 8px;
  text-align: left;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  cursor: pointer;
  transition: background-color 140ms ease;
}

.capability-item::before {
  position: absolute;
  top: 7px;
  bottom: 7px;
  left: 0;
  width: 2px;
  border-radius: 2px;
  background: transparent;
  content: '';
}

.capability-item:hover:not(:disabled) {
  background: var(--wb-hover);
}

.capability-item.active {
  background: var(--wb-selected);
}

.capability-item.active::before {
  background: var(--mes-primary);
}

.capability-item:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.capability-icon {
  display: inline-flex;
  width: 20px;
  height: 20px;
  flex: none;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
  color: var(--wb-text-3);
  border-radius: 5px;
  background: var(--wb-hover);
}

.capability-item.active .capability-icon {
  color: var(--mes-primary);
  background: var(--wb-panel);
}

.capability-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 1px;
  line-height: 1.4;
}

.capability-name {
  overflow: hidden;
  color: var(--wb-text-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.capability-item.active .capability-name {
  color: var(--wb-text);
  font-weight: 600;
}

.capability-desc {
  display: -webkit-box;
  overflow: hidden;
  color: var(--wb-text-3);
  font-size: 10.5px;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 1;
  line-clamp: 1;
}

/* 「可办理」是动作清单，用胶囊承载，避免和能力项抢层级。 */
.catalog-phase + .catalog-phase { margin-top: 12px; }
.catalog-phase-title { margin-bottom: 6px; color: var(--wb-text-2); font-size: 12px; }
.catalog-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 0 6px 2px;
}

.command-chip {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  padding: 2px 7px;
  color: var(--wb-text-2);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--wb-line);
  border-radius: 20px;
  background: var(--wb-panel);
  cursor: pointer;
  transition:
    border-color 140ms ease,
    color 140ms ease;
}

.command-chip:hover:not(:disabled) {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
}

.command-chip:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

/* 会话列表固定最大高度，超出在自身内部滚动，不参与侧栏伸缩。 */
.history-scroll {
  min-height: 0;
  max-height: 34vh;
  flex: none;
}

.history-sub {
  padding: 6px 6px 3px;
  color: var(--wb-text-3);
  font-size: 10px;
}

.session-row {
  display: flex;
  align-items: center;
  gap: 2px;
  padding-right: 4px;
  border-radius: var(--wb-radius-sm);
  transition: background-color 140ms ease;
}

.session-row:hover {
  background: var(--wb-hover);
}

.session-row.active {
  background: var(--wb-selected);
}

.session-row.is-archived {
  opacity: 0.7;
}

.session-main {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: baseline;
  gap: 6px;
  padding: 6px 8px;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.session-title {
  overflow: hidden;
  flex: 1;
  color: var(--wb-text-2);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-row.active .session-title {
  color: var(--mes-primary);
  font-weight: 500;
}

.session-main small {
  flex: none;
  color: var(--wb-text-3);
  font-size: 10px;
}

/* 会话操作按钮只在 hover / 键盘聚焦时出现，默认列表更干净。 */
.session-actions {
  display: flex;
  flex: none;
  gap: 1px;
  opacity: 0;
  transition: opacity 140ms ease;
}

.session-row:hover .session-actions,
.session-row:focus-within .session-actions {
  opacity: 1;
}

.session-actions button {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  color: var(--wb-text-3);
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
}

.session-actions button:hover {
  color: var(--wb-text);
  background: var(--wb-panel);
}

.history-empty {
  margin: 0;
  padding: 10px 8px;
  color: var(--wb-text-3);
  font-size: 11px;
}

.wb-foot {
  display: flex;
  flex: none;
  flex-direction: column;
  gap: 2px;
  padding: 8px 14px 10px;
  border-top: 1px solid var(--wb-line-soft);
}

.foot-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  font-size: 10.5px;
}

.foot-row span {
  color: var(--wb-text-3);
}

.foot-row b {
  overflow: hidden;
  color: var(--wb-text-2);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ---------- 中间对话区 ---------- */

.wb-chat {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  background: var(--wb-bg);
}

.chat-head {
  display: flex;
  flex: none;
  align-items: center;
  gap: 12px;
  padding: 11px 20px;
  border-bottom: 1px solid var(--wb-line);
  background: var(--wb-panel);
}

.head-main {
  min-width: 0;
  flex: 1;
}

.head-main h1 {
  margin: 0;
  color: var(--wb-text);
  font-size: 13.5px;
  font-weight: 600;
  line-height: 1.4;
}

.head-main p {
  overflow: hidden;
  margin: 1px 0 0;
  color: var(--wb-text-3);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.head-side {
  display: flex;
  flex: none;
  align-items: center;
  gap: 8px;
}

.mode-switch {
  display: inline-flex;
  padding: 2px;
  border: 1px solid var(--wb-line);
  border-radius: var(--wb-radius-sm);
  background: var(--wb-hover);
}

.mode-switch button {
  padding: 2px 9px;
  color: var(--wb-text-3);
  font-size: 11px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  transition:
    color 140ms ease,
    background-color 140ms ease;
}

.mode-switch button:hover {
  color: var(--wb-text-2);
}

.mode-switch button.active {
  color: var(--mes-primary);
  background: var(--wb-panel);
  box-shadow: 0 1px 2px rgb(15 35 60 / 8%);
}

.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  color: var(--wb-text-2);
  font-size: 11px;
  border: 1px solid var(--wb-line);
  border-radius: 20px;
  background: var(--wb-panel);
}

.status-badge i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wb-text-3);
}

.status-badge.is-ready i {
  background: var(--mes-success, #12a150);
}
.status-badge.is-warning i {
  background: var(--mes-warning, #d98a00);
}

.ghost-button {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  color: var(--wb-text-2);
  font-size: 11.5px;
  border: 1px solid var(--wb-line);
  border-radius: var(--wb-radius-sm);
  background: var(--wb-panel);
  cursor: pointer;
  transition:
    border-color 140ms ease,
    color 140ms ease;
}

.ghost-button:hover:not(:disabled) {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
}

.ghost-button:disabled {
  color: var(--wb-text-3);
  cursor: not-allowed;
}

.primary-button {
  padding: 4px 11px;
  color: #fff;
  font-size: 11.5px;
  border: 1px solid var(--mes-primary);
  border-radius: var(--wb-radius-sm);
  background: var(--mes-primary);
  cursor: pointer;
}

.primary-button:hover {
  background: var(--mes-primary-hover, #1859c4);
}

/* 悬浮形态的提示卡片：竖向居中，避免被右下角浮窗压住。 */
.dock-notice {
  display: flex;
  max-width: 400px;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  margin: auto;
  padding: 32px 20px 48px;
  text-align: center;
}

.dock-notice-icon {
  display: inline-flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
  color: var(--mes-primary);
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 12px;
  background: var(--mes-primary-soft, #eaf2ff);
}

.dock-notice-text strong {
  display: block;
  margin-bottom: 4px;
  color: var(--wb-text);
  font-size: 13px;
}

.dock-notice-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.service-notice {
  display: flex;
  flex: none;
  align-items: center;
  gap: 9px;
  padding: 9px 20px;
  color: var(--wb-text-2);
  border-bottom: 1px solid var(--wb-line-soft);
  background: var(--mes-warning-soft, #fdf6e7);
}

.service-notice > div {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: baseline;
  gap: 8px;
}

.service-notice strong {
  flex: none;
  color: var(--wb-text);
  font-size: 11.5px;
}

.service-notice span {
  color: var(--wb-text-2);
  font-size: 11px;
}

.chat-scroll {
  min-height: 0;
  flex: 1 1 auto;
  height: 0;
}

.chat-scroll :deep(.el-scrollbar__view) {
  min-height: 100%;
}

.chat-inner {
  display: flex;
  width: 100%;
  max-width: 1000px;
  min-height: 100%;
  flex-direction: column;
  margin: 0 auto;
  padding: 20px 24px 8px;
}

/* ---------- 欢迎屏 ---------- */

.welcome {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 8px 0 12px;
}

.welcome-head h2 {
  margin: 0;
  color: var(--wb-text);
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.2px;
  line-height: 1.35;
}

.welcome-grid {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

/* 能力卡：头部是能力说明，底部挂两条推荐问句，卡片本身不抢视觉。 */
.welcome-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--wb-line);
  border-radius: var(--wb-radius);
  background: var(--wb-panel);
  transition:
    border-color 140ms ease,
    box-shadow 140ms ease;
}

.welcome-card:hover {
  border-color: var(--mes-primary-border, #bcd3fb);
  box-shadow: 0 1px 3px rgb(15 35 60 / 6%);
}

.welcome-card.active {
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--wb-selected);
}

.welcome-card-head {
  display: flex;
  width: 100%;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 11px 7px;
  text-align: left;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.welcome-card-icon {
  display: inline-flex;
  width: 24px;
  height: 24px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: var(--wb-text-3);
  border-radius: 6px;
  background: var(--wb-hover);
}

.welcome-card.active .welcome-card-icon {
  color: var(--mes-primary);
  background: var(--wb-panel);
}

.welcome-card-text {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.welcome-card-text strong {
  overflow: hidden;
  color: var(--wb-text);
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome-card-text span {
  display: -webkit-box;
  overflow: hidden;
  color: var(--wb-text-3);
  font-size: 10.5px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

.welcome-card-prompts {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 0 7px 8px;
}

.welcome-card-prompts button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 4px;
  overflow: hidden;
  padding: 3px 4px;
  color: var(--wb-text-2);
  font-size: 10.5px;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 0;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  transition:
    color 140ms ease,
    background-color 140ms ease;
}

.welcome-card-prompts button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.welcome-card-prompts button :deep(svg) {
  flex: none;
  color: var(--wb-text-3);
}

.welcome-card-prompts button:hover {
  color: var(--mes-primary);
  background: var(--wb-hover);
}

.welcome-card-prompts button:hover :deep(svg) {
  color: var(--mes-primary);
}

/* 「你可以这样问」：横向胶囊，覆盖更宽的提问口径。 */
.welcome-examples {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.welcome-examples-label {
  color: var(--wb-text-3);
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.4px;
}

.welcome-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.welcome-pills button {
  max-width: 100%;
  overflow: hidden;
  padding: 4px 11px;
  color: var(--wb-text-2);
  font-size: 11.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--wb-line);
  border-radius: 20px;
  background: var(--wb-panel);
  cursor: pointer;
  transition:
    border-color 140ms ease,
    color 140ms ease;
}

.welcome-pills button:hover {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
  background: var(--mes-primary-soft, #eaf2ff);
}

/* ---------- 消息 ---------- */

.message-list {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-bottom: 8px;
}

.msg {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.msg.is-user {
  align-items: flex-end;
}

.msg-user {
  display: flex;
  max-width: 78%;
  align-items: flex-end;
  gap: 8px;
}

.msg-user p {
  margin: 0;
  padding: 9px 13px;
  color: #fff;
  font-size: 12.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  border-radius: 12px 12px 3px 12px;
  background: var(--mes-primary);
}

.msg-user-time {
  flex: none;
  color: var(--wb-text-3);
  font-size: 10px;
  line-height: 1.8;
}

.msg-head {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--wb-text-3);
  font-size: 10.5px;
}

.msg-avatar {
  display: inline-flex;
  width: 22px;
  height: 22px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #fff;
  border-radius: 50%;
  background: var(--mes-primary);
}

.msg-author {
  color: var(--wb-text-2);
  font-size: 11.5px;
  font-weight: 600;
}

.msg-dot {
  color: var(--wb-line);
}

.msg-source {
  color: var(--wb-text-3);
}

.msg-query-time {
  color: var(--wb-text-3);
}

/* 历史回答的查询时间可能已经过期，给一个就地重查入口。 */
.msg-requery {
  margin-left: 2px;
  padding: 0 3px;
  color: var(--mes-primary);
  font-size: 10.5px;
  border: 0;
  background: none;
  cursor: pointer;
}

.msg-requery:hover:not(:disabled) {
  text-decoration: underline;
}

.msg-requery:disabled {
  color: var(--wb-text-3);
  cursor: not-allowed;
}

.msg-body {
  display: flex;
  min-width: 0;
  flex-direction: column;
  padding: 11px 14px;
  border: 1px solid var(--wb-line);
  border-radius: 3px 12px 12px 12px;
  background: var(--wb-panel);
}

.msg-text {
  margin: 0;
  color: var(--wb-text);
  font-size: 12.5px;
  line-height: 1.75;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-suggestions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--wb-line-soft);
}

.msg-task-action {
  margin-top: 12px;
}

.msg-task-action button {
  padding: 5px 14px;
  color: #fff;
  font-size: 11.5px;
  border: 0;
  border-radius: 4px;
  background: var(--mes-primary, #2f6fed);
  cursor: pointer;
}

.msg-task-action button:disabled {
  background: #b9c6da;
  cursor: not-allowed;
}

.suggestions-label {
  color: var(--wb-text-3);
  font-size: 10.5px;
}

.msg-suggestions button {
  max-width: 100%;
  overflow: hidden;
  padding: 3px 10px;
  color: var(--mes-primary);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--mes-primary-border, #bcd3fb);
  border-radius: 20px;
  background: var(--mes-primary-soft, #eaf2ff);
  cursor: pointer;
  transition: background-color 140ms ease;
}

.msg-suggestions button:hover {
  background: var(--wb-panel);
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.msg-disclaimer {
  overflow: hidden;
  flex: 1;
  margin: 0;
  color: var(--wb-text-3);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.msg-data-gaps {
  margin: 8px 0 0;
  color: var(--wb-warning, #b7791f);
  font-size: 11px;
  line-height: 1.5;
}

.msg-actions {
  display: flex;
  flex: none;
  gap: 1px;
  opacity: 0;
  transition: opacity 140ms ease;
}

.msg:hover .msg-actions,
.msg-actions:focus-within {
  opacity: 1;
}

.msg-actions button {
  display: inline-flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  color: var(--wb-text-3);
  border: 1px solid transparent;
  border-radius: 4px;
  background: transparent;
  cursor: pointer;
  transition:
    color 140ms ease,
    background-color 140ms ease;
}

.msg-actions button:hover:not(:disabled) {
  color: var(--wb-text);
  background: var(--wb-hover);
}

.msg-actions button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.msg-error {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  color: var(--mes-danger, #d64545);
  border: 1px solid var(--mes-danger, #d64545);
  border-radius: var(--wb-radius);
  background: var(--mes-danger-soft, #fdeeee);
}

.msg-error svg {
  flex: none;
  margin-top: 1px;
}

.msg-error p {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
}

.msg-error button {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 5px;
  padding: 0;
  color: var(--mes-danger, #d64545);
  font-size: 11px;
  border: 0;
  background: none;
  cursor: pointer;
  text-decoration: underline;
}

/* 等待态：三段式进度，依次点亮，比三个点更能说明在做什么。 */
.thinking-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.thinking-steps .step {
  position: relative;
  padding: 4px 10px 4px 22px;
  color: var(--wb-text-3);
  font-size: 11px;
  border: 1px solid var(--wb-line);
  border-radius: 20px;
  background: var(--wb-panel);
  animation: wb-step 2.4s ease-in-out infinite;
}

.thinking-steps .step::before {
  position: absolute;
  top: 50%;
  left: 9px;
  width: 6px;
  height: 6px;
  margin-top: -3px;
  border-radius: 50%;
  background: var(--wb-line);
  content: '';
  animation: wb-step-dot 2.4s ease-in-out infinite;
}

/* 三步依次点亮：每步错开 0.8s，2.4s 一轮，看起来像在按顺序推进。 */
.thinking-steps .is-2,
.thinking-steps .is-2::before {
  animation-delay: 0.8s;
}

.thinking-steps .is-3,
.thinking-steps .is-3::before {
  animation-delay: 1.6s;
}

@keyframes wb-step {
  0%,
  100% {
    color: var(--wb-text-3);
    border-color: var(--wb-line);
  }
  20%,
  45% {
    color: var(--mes-primary);
    border-color: var(--mes-primary-border, #bcd3fb);
  }
}

@keyframes wb-step-dot {
  0%,
  100% {
    background: var(--wb-line);
  }
  20%,
  45% {
    background: var(--mes-primary);
  }
}

/* ---------- 输入区 ---------- */

.compose {
  flex: none;
  padding: 10px 24px 14px;
  background: var(--wb-bg);
}

.compose-box {
  max-width: 1000px;
  margin: 0 auto;
  padding: 7px 8px 6px 12px;
  border: 1px solid var(--wb-line);
  border-radius: 12px;
  background: var(--wb-panel);
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

/* 聚焦时主色描边 + 极轻外扩，是输入区唯一的强调。 */
.compose-box:focus-within {
  border-color: var(--mes-primary);
  box-shadow: 0 0 0 3px rgb(var(--mes-primary-rgb, 31 111 235) / 12%);
}

.compose-box.disabled {
  background: var(--wb-hover);
}

.compose-box :deep(.el-textarea__inner) {
  padding: 4px 0;
  color: var(--wb-text);
  font-size: 12.5px;
  line-height: 1.7;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.compose-box :deep(.el-textarea__inner::placeholder) {
  color: var(--wb-text-3);
}

.compose-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-top: 5px;
}

.compose-capability {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  color: var(--wb-text-2);
  font-size: 11px;
  border: 1px solid var(--wb-line);
  border-radius: 20px;
  background: transparent;
  cursor: pointer;
  transition:
    color 140ms ease,
    border-color 140ms ease;
}

.compose-capability:hover:not(:disabled) {
  color: var(--mes-primary);
  border-color: var(--mes-primary-border, #bcd3fb);
}

.compose-capability:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

.compose-count {
  flex: 1;
  color: var(--wb-text-3);
  font-size: 10px;
  text-align: right;
}

.send-button {
  display: inline-flex;
  width: 28px;
  height: 28px;
  flex: none;
  align-items: center;
  justify-content: center;
  color: #fff;
  border: 1px solid var(--mes-primary);
  border-radius: 50%;
  background: var(--mes-primary);
  cursor: pointer;
  transition: background-color 140ms ease;
}

.send-button:hover:not(:disabled) {
  background: var(--mes-primary-hover, #1859c4);
}

.send-button:disabled {
  color: var(--wb-text-3);
  border-color: var(--wb-line);
  background: var(--wb-hover);
  cursor: not-allowed;
}

.send-button.is-stop {
  color: var(--wb-text-2);
  border-color: var(--wb-line);
  background: var(--wb-panel);
}

.send-button.is-stop:hover {
  color: var(--mes-danger, #d64545);
  border-color: var(--mes-danger, #d64545);
}

.compose-hint {
  display: flex;
  max-width: 1000px;
  justify-content: space-between;
  gap: 12px;
  margin: 6px auto 0;
  color: var(--wb-text-3);
  font-size: 10px;
}

/* ---------- 响应式 ---------- */

/* 1100px 以下：侧栏收窄，能力描述隐藏，优先保住对话宽度。 */
@media (max-width: 1100px) {
  .wb-sidebar {
    width: 216px;
  }

  .capability-desc {
    display: none;
  }

  .chat-inner,
  .compose-box {
    max-width: 100%;
  }

  .welcome-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

/* 780px 以下：侧栏变抽屉，靠左上角按钮展开；能力卡改单列。 */
@media (max-width: 780px) {
  .sidebar-toggle {
    display: inline-flex;
  }

  .wb-sidebar {
    position: absolute;
    z-index: 11;
    top: 0;
    bottom: 0;
    left: 0;
    width: 252px;
    transform: translateX(-100%);
    box-shadow: 0 8px 24px rgb(15 35 60 / 14%);
    transition: transform 180ms ease;
  }

  .wb-frame.is-sidebar-open .wb-sidebar {
    transform: translateX(0);
  }

  .chat-head {
    padding: 10px 14px 10px 48px;
  }

  .head-main p {
    display: none;
  }

  .chat-inner {
    padding: 16px 14px 8px;
  }

  .compose {
    padding: 8px 14px 12px;
  }

  .welcome-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .msg-user {
    max-width: 90%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ai-workbench *,
  .ai-workbench *::before,
  .ai-workbench *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
