// 只把用户明确提及的单号或成功创建的订单作为会话上下文。
const ORDER_PATTERN = /\b[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*-PO-\d{8}-\d+\b/gi;

export function resolveOrderContext(messages, fallback = '') {
  for (const message of [...(messages || [])].reverse()) {
    const value = message.role === 'user' ? userOrderContext(message.content) : successfulOrderContext(message);
    if (value !== null) {
      return value;
    }
  }
  return fallback;
}

function userOrderContext(content) {
  const text = String(content || '');
  const numbers = [...new Set((text.match(ORDER_PATTERN) || []).map((value) => value.toUpperCase()))];
  if (numbers.length > 1) {
    return '';
  }
  if (numbers.length === 1) {
    return numbers[0];
  }
  return /第[一二三四五六七八九十\d]+[个张单]|前一个|上一张|另一张|其他订单|不是.*(?:这|刚才)/.test(text) ? '' : null;
}

function successfulOrderContext(message) {
  if (message.role !== 'assistant' || message.commandStatus !== 'SUCCEEDED') {
    return null;
  }
  const action = message.command?.action;
  if (!['CREATE_PRODUCTION_ORDER', 'SCHEDULE_PRODUCTION_ORDER', 'GENERATE_WORK_ORDER'].includes(action)) {
    return null;
  }
  const number = String(
    action === 'CREATE_PRODUCTION_ORDER' ? message.commandResult?.bizNo || '' : message.command?.targetNo || '',
  ).trim();
  return number && number.match(ORDER_PATTERN)?.[0]?.length === number.length ? number : null;
}

export function orderResultHistory(message) {
  if (message?.commandStatus !== 'SUCCEEDED') {
    return '';
  }
  const action = message.command?.action;
  const result = String(message.commandResult?.bizNo || '').trim();
  const target = String(message.command?.targetNo || '').trim();
  const safe = (value) => /^[A-Za-z0-9_-]{2,100}$/.test(value);
  if (action === 'CREATE_PRODUCTION_ORDER') {
    if (!result || result.match(ORDER_PATTERN)?.[0]?.length !== result.length) {
      return '';
    }
    return `已成功创建生产订单 ${result}。`;
  }
  const labels = {
    SCHEDULE_PRODUCTION_ORDER: '安排生产订单',
    GENERATE_WORK_ORDER: '生成生产工单',
    RELEASE_WORK_ORDER: '下达生产工单',
    GENERATE_DISPATCH_ORDER: '生成派工单',
    START_WORK_ORDER: '开始生产',
    GENERATE_MATERIAL_ISSUE: '生成领料单',
    TRANSFER_TO_LINE_SIDE: '领料转线边',
    CONFIRM_OUTBOUND: '确认出库',
    CONFIRM_INBOUND: '确认入库',
    RELEASE_DISPATCH_ORDER: '下达派工单',
    START_TASK: '开工工序任务',
    SUBMIT_WORK_REPORT: '提交报工',
    GENERATE_PRODUCTION_INBOUND: '生成成品入库单',
    GENERATE_SALES_OUTBOUND: '生成销售出库单',
    CREATE_OUTGOING_INSPECTION: '生成出货检验单',
    CONFIRM_SALES_OUTBOUND: '确认发货',
    SUBMIT_INSPECTION: '提交检验',
    AUDIT_WORK_REPORT: '审核报工',
  };
  if (!labels[action] || !safe(target)) {
    return '';
  }
  return `已成功${labels[action]}，目标单据 ${target}${safe(result) ? `，结果单据 ${result}` : ''}。`;
}

// 将界面里实际展示的单据集合带给模型。仅作为历史定位依据，执行前仍需现查。
export function businessResultHistory(message) {
  if (message?.role !== 'assistant') {
    return '';
  }
  const parts = [];
  for (const block of message.blocks || []) {
    const rows = [];
    for (const row of (block.rows || []).slice(0, 12)) {
      const values = Object.entries(row).filter(
        ([key, value]) =>
          ['productionOrderNo', 'workOrderNo', 'dispatchOrderNo', 'taskNo', 'outboundNo', 'inboundNo',
            'inspectionNo', 'lotNo', 'status', 'workOrderType', 'productName', 'action', 'actionLabel'].includes(key)
          && ['string', 'number'].includes(typeof value),
      );
      if (values.length) {
        rows.push(Object.fromEntries(values));
      }
    }
    if (rows.length) {
      parts.push(`${String(block.title || '').slice(0, 60)}：${JSON.stringify(rows)}`);
    }
  }
  for (const step of (message.sequence?.steps || []).slice(0, 12)) {
    parts.push(
      JSON.stringify({
        action: step.action,
        targetNo: step.targetNo,
        status: step.status,
        result: step.result?.bizNo,
        error: step.errorMessage,
      }),
    );
  }
  return parts.length ? `【历史业务结果，仅用于定位；当前状态须重新查询】\n${parts.join('\n')}`.slice(0, 1400) : '';
}

// 把预览中真实返回的未通过原因带入下一轮；它是当时的检查结果，不能当作当前库存或执行成功。
export function commandPreviewHistory(message) {
  const command = message?.command;
  if (!command || command.canExecute !== false || !Array.isArray(command.preconditions)) {
    return '';
  }
  const failed = command.preconditions.filter((item) => item && item.passed === false).slice(0, 8);
  if (!failed.length) {
    return '';
  }
  const safeText = (value) =>
    String(value || '')
      .replace(/[\r\n]+/g, ' ')
      .slice(0, 500);
  const label = safeText(command.actionLabel || '业务办理');
  const target = safeText(command.targetNo);
  const reasons = failed
    .map((item) => [safeText(item.label), safeText(item.detail)].filter(Boolean).join('：'))
    .join('；');
  return `此前「${label}」${target ? `目标 ${target}` : ''}的预览未通过，未执行；当时的原因：${reasons}。这是历史检查结果，办理前应重新核查实际状态。`;
}
