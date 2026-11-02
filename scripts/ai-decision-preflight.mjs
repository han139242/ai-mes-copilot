import { createHash } from 'node:crypto';
import { readFileSync, realpathSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

// 只读决策原型：消费冻结快照，不连接数据库，不调用模型，不执行业务。
function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

export function fingerprint(snapshot) {
  return createHash('sha256').update(JSON.stringify(canonical(snapshot))).digest('hex');
}

// 与数据库 DECIMAL(…,4) 对齐；数量比较不用浮点数，也不跨单位相加。
export function quantity(value) {
  if (typeof value !== 'string' || !/^\d+(?:\.\d{1,4})?$/.test(value)) {
    throw new Error('数量必须是非负、最多四位小数的十进制字符串');
  }
  const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'));
}

function display(value) {
  const whole = value / 10000n;
  const fraction = (value % 10000n).toString().padStart(4, '0').replace(/0+$/, '');
  return fraction ? `${whole}.${fraction}` : whole.toString();
}

export function evaluate(snapshot, { now = new Date(), maxAgeMs = 300000 } = {}) {
  if (snapshot?.schemaVersion !== 1 || !snapshot.database || !snapshot.capturedAt) {
    throw new Error('缺少快照版本、数据库身份或采集时间');
  }
  const age = now.getTime() - Date.parse(snapshot.capturedAt);
  if (!Number.isFinite(age) || age < 0 || age > maxAgeMs) {
    return { status: 'STALE', canExecute: false, reason: '快照过期或采集时间无效，必须重新取数' };
  }
  if (!Array.isArray(snapshot.requirements) || !Array.isArray(snapshot.components)) {
    throw new Error('缺少当前工单直接用料或组件快照，拒绝按齐套处理');
  }
  const order = snapshot.workOrder;
  if (!order?.id || !order.no || !order.status) throw new Error('缺少工单事实');
  const blockers = [];
  if (!['RELEASED', 'RUNNING'].includes(order.status)) {
    blockers.push({ code: 'WORK_ORDER_STATE', evidence: order.status, message: '当前状态不支持直接开工' });
  }
  if (!snapshot.requirements.length) {
    blockers.push({ code: 'MATERIAL_DATA_MISSING', message: '没有当前工单直接用料明细，不能判定齐套' });
  }
  const materials = snapshot.requirements.map((item) => {
    const required = quantity(item.required);
    const issued = quantity(item.issued);
    if (!['DISCRETE', 'CONTINUOUS'].includes(item.quantityMode)) {
      throw new Error(`物料 ${item.materialId} 缺少有效数量类型`);
    }
    if (item.quantityMode === 'DISCRETE' && (required % 10000n || issued % 10000n)) {
      throw new Error(`计数物料 ${item.materialId} 出现小数，先修复数量数据`);
    }
    const remaining = required > issued ? required - issued : 0n;
    const component = snapshot.components.find((entry) => entry.id === item.sourceWorkOrderId);
    if (remaining > 0n) {
      blockers.push({ code: 'MATERIAL_NOT_ISSUED', materialId: item.materialId,
        message: `${item.materialName || item.materialId} 尚需领用 ${display(remaining)}${item.unit || ''}` });
    }
    if (remaining > 0n && item.supplyType === 'COMPONENT_WORK_ORDER' && (!component || component.status !== 'COMPLETED')) {
      blockers.push({ code: 'COMPONENT_NOT_COMPLETED', materialId: item.materialId,
        componentWorkOrderId: item.sourceWorkOrderId,
        message: component ? `组件工单 ${component.no} 当前为 ${component.status}` : '组件来源工单缺失' });
    }
    return { materialId: item.materialId, name: item.materialName, unit: item.unit,
      remaining: display(remaining), componentWorkOrderId: item.sourceWorkOrderId };
  });
  const inspection = snapshot.inspection;
  let inspectionDecision = null;
  if (inspection) {
    if (!Number.isSafeInteger(inspection.resultCount) || inspection.resultCount < 0) {
      throw new Error('检验结果数量无效');
    }
    inspectionDecision = {
      no: inspection.no, status: inspection.status, recordedResultCount: inspection.resultCount,
      workOrderId: inspection.workOrderId ?? null,
      relatedToSelectedWorkOrder: inspection.workOrderId === order.id,
      decision: inspection.status === 'DRAFT' && inspection.resultCount === 0 ? 'BLOCKED' : 'NEEDS_DOMAIN_VALIDATION',
      nextAction: inspection.resultCount === 0
        ? '分派给有权限的检验人员，录入实际逐件测量结果，再由质量服务验证并提交'
        : '由质量服务核对逐件覆盖、实测值和结论后提交，不能按行数推定合格',
      canAutoPass: false,
    };
  }
  const pending = snapshot.components.filter((component) => component.status !== 'COMPLETED'
    && materials.some((material) => material.componentWorkOrderId === component.id && quantity(material.remaining) > 0n));
  return {
    status: blockers.length ? 'BLOCKED' : 'NEEDS_DOMAIN_VALIDATION', canExecute: false,
    source: { database: snapshot.database, capturedAt: snapshot.capturedAt, fingerprint: fingerprint(snapshot) },
    workOrder: { id: order.id, no: order.no, version: order.version, status: order.status },
    conclusion: pending.length
      ? `整机不能直接开工：${pending.length} 张组件工单尚未完成。应先处理组件供给，再核对领料与资源。`
      : '组件供给没有发现未完成项；仍须核对实际领料、产线、工位、人员和质量状态。',
    blockers, materials, inspection: inspectionDecision,
    alternatives: [
      { name: '直接开工', status: blockers.length ? 'BLOCKED' : 'NEEDS_DOMAIN_VALIDATION', blockers: blockers.map((item) => item.code) },
      { name: '先完成组件再生产整机', status: 'NEEDS_DOMAIN_VALIDATION', steps: [
        ...pending.map((component) => ({ action: 'PREPARE_COMPONENT', workOrderId: component.id,
          no: component.no, currentStatus: component.status,
          checks: ['直接项 BOM', '可用库存及批次', '产线工位及人员', '实测质量结果'] })),
        { action: 'VERIFY_COMPONENT_RECEIPTS', effect: '组件必须经真实检验和入库后才能计入可用供给' },
        { action: 'PREVIEW_MATERIAL_TRANSFER', effect: '重新检查整机领料及线边库存，不重复扣底层采购件' },
        { action: 'PREVIEW_START', effect: '用实时版本重新校验；通过正式业务服务才可执行' },
      ] },
    ],
    unknowns: ['未执行产线周历与工位占用校验', '未执行实时库存分配或事务校验',
      '未校验操作账号权限', '未建立离散事件工时模型，不能预测完工日期',
      '异常中的建单时间不能替代工单实际下达时间'],
  };
}

if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  const path = process.argv[2];
  if (!path) throw new Error('用法：node scripts/ai-decision-preflight.mjs <冻结快照.json>');
  const report = JSON.stringify(evaluate(JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''))), null, 2);
  if (process.argv[3]) {
    writeFileSync(process.argv[3], report, { encoding: 'utf8', flag: 'wx' });
    console.log(`推演报告已保存：${process.argv[3]}`);
  } else {
    console.log(report);
  }
}
