/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 */
// 顺序仅组织已注册能力，执行仍使用后端真实预览、权限与领域校验。
const phases = [
  [
    'order',
    '1 接单与工单',
    [
      ['CREATE_PRODUCTION_ORDER', '创建订单', '创建一个生产订单'],
      ['SCHEDULE_PRODUCTION_ORDER', '安排排产', '安排刚才这张订单的排产'],
      ['GENERATE_WORK_ORDER', '生成工单', '给这张订单生成工单'],
      ['RELEASE_WORK_ORDER', '下达工单', '先下达整机，再下达准备领用的组件工单'],
    ],
  ],
  [
    'stock',
    '2 领料与库存组件',
    [
      ['GENERATE_MATERIAL_ISSUE', '生成领料单', '组件默认从半成品仓领用；整机领本级原料和辅料'],
      ['SUBMIT_INSPECTION', '领用复验', '填写这张领料单关联的逐件检验结果'],
      ['TRANSFER_TO_LINE_SIDE', '领料转线边', '将检验合格的领料单转到线边，组件不重复生产'],
    ],
  ],
  [
    'production',
    '3 整机装配与报工',
    [
      ['GENERATE_DISPATCH_ORDER', '生成派工单', '组件已到线边后，为整机生成派工单'],
      ['RELEASE_DISPATCH_ORDER', '下达派工单', '下达派工单并生成工序任务'],
      ['START_WORK_ORDER', '开始生产', '核对齐套和任务后开始整机生产'],
      ['START_TASK', '开工工序任务', '按冻结工序顺序开工，前序未完成时先处理前序'],
      ['SUBMIT_WORK_REPORT', '提交报工', '填写本工序实际合格数和不良数'],
      ['AUDIT_WORK_REPORT', '审核报工', '核对实际用料、数量后审核报工'],
      ['SUBMIT_INSPECTION', '生产检验', '过程检和终检均填写逐件实际结果'],
    ],
  ],
  [
    'inbound',
    '4 成品入库',
    [
      ['GENERATE_PRODUCTION_INBOUND', '生成成品入库单', '为已终检放行的整机批次生成入库单'],
      ['CONFIRM_INBOUND', '确认入库', '确认入库单，让成品进入可用库存'],
    ],
  ],
  [
    'shipment',
    '5 出货与发运',
    [
      ['GENERATE_SALES_OUTBOUND', '生成发货单', '为这张已完成生产的订单生成发货单'],
      ['CREATE_OUTGOING_INSPECTION', '生成出货检验单', '为发货单生成出货检验单'],
      ['SUBMIT_INSPECTION', '填写出货检验', '录入本次发货的逐件检验结果'],
      ['CONFIRM_SALES_OUTBOUND', '确认发货', '出货检验合格后确认发货'],
    ],
  ],
  ['other', '其他库存操作', [['CONFIRM_OUTBOUND', '普通出库确认', '普通出库确认；生产领料请使用“领料转线边”']]],
];

export function businessActionGroups(catalog) {
  const available = new Map(catalog.filter((x) => x.kind === 'command').map((x) => [x.code || x.action, x]));
  return phases
    .map(([id, label, entries]) => ({
      id,
      label,
      items: entries
        .filter(([code]) => available.has(code))
        .map(([code, text, prompt]) => ({
          ...available.get(code),
          code,
          label: text,
          prompt,
          flowKey: `${id}-${code}`,
        })),
    }))
    .filter((group) => group.items.length);
}

// 开发预览只展示同一套业务入口，不代表当前账号获授权，也不开放办理。
export function businessActionPreviewGroups() {
  const catalog = [...new Set(phases.flatMap(([, , entries]) => entries.map(([code]) => code)))].map((code) => ({
    code,
    kind: 'command',
    previewOnly: true,
  }));
  return businessActionGroups(catalog);
}

export function filterWorkflowCandidates(candidates, progress, action) {
  if (!progress) {
    return candidates;
  }
  const allowed = new Set([
    progress.order?.productionOrderNo,
    ...(progress.documents || []).map((x) => x.docNo),
    ...(progress.workOrders || []).map((x) => x.workOrderNo),
  ]);
  const roots = new Set(
    (progress.workOrders || []).filter((x) => x.workOrderType === 'ROOT').map((x) => x.workOrderId),
  );
  const rootLots = new Set(
    (progress.documents || []).filter((x) => x.type === 'PRODUCT_LOT' && roots.has(x.workOrderId)).map((x) => x.docNo),
  );
  return candidates.filter(
    (x) => allowed.has(x.bizNo) && (action !== 'GENERATE_PRODUCTION_INBOUND' || rootLots.has(x.bizNo)),
  );
}

export function preferredIssueWarehouse(warehouses, workOrderType) {
  const type = workOrderType === 'COMPONENT' ? 'SEMI_FINISHED' : 'RAW';
  const matches = warehouses.filter((x) => x.warehouseType === type && x.status === 'ENABLE');
  return matches.length === 1 ? matches[0].warehouseId : null;
}

export function workflowTargetNumbers(progress) {
  if (!progress) {
    return null;
  }
  return [
    ...new Set(
      [
        progress.order?.productionOrderNo,
        ...(progress.workOrders || []).map((item) => item.workOrderNo),
        ...(progress.documents || []).map((item) => item.docNo),
      ].filter(Boolean),
    ),
  ];
}
