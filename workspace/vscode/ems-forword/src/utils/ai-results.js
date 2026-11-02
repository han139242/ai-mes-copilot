/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
// 只格式化显示文本，不改变接口数据、图表数据或复制导出的精度。
export function formatAiNumber(value) {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const number = Number(value);
  if (!Number.isFinite(number)) {
    return '—';
  }
  // 很小的非零值保留有效数字，避免把实际消耗或比率显示为零。
  const options =
    number !== 0 && Math.abs(number) < 0.01 ? { maximumSignificantDigits: 2 } : { maximumFractionDigits: 2 };
  return new Intl.NumberFormat('zh-CN', options).format(number);
}

// 正文里的长小数按展示口径压到两位：后端回答会带出 1409.6668 这类原始读数，直接显示不专业。
const LONG_DECIMAL = /(?<int>\d+)\.(?<dec>\d{3,})/g;

export function formatAiAnswer(text) {
  if (typeof text !== 'string' || !text) {
    return '';
  }
  return text.replace(LONG_DECIMAL, (...args) => {
    const groups = args[args.length - 1];
    const value = Number(`${groups.int}.${groups.dec}`);
    return Number.isFinite(value) ? formatAiNumber(value) : args[0];
  });
}

export function formatAiChartValue(value, name = '') {
  const text = formatAiNumber(value);
  if (text === '—' || !/分钟/.test(name)) {
    return text;
  }
  const number = Number(value);
  if (Math.abs(number) < 60) {
    return `${text} 分钟`;
  }
  const minutes = Math.round(Math.abs(number));
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `${text} 分钟（约 ${number < 0 ? '-' : ''}${hours} 小时${rest ? ` ${rest} 分` : ''}）`;
}

export function formatAiCell(row, column, value) {
  const prop = column.property || column.prop || '';
  // 编码、单号及版本属于标识，不能添加分隔符或截掉小数。
  if (/(?:Id|Code|No|Version)$|^(?:id|code|version)$/.test(prop) || /编码|编号|单号|版本/.test(column.label || '')) {
    return value ?? '—';
  }
  if (typeof value === 'number') {
    return formatAiNumber(value);
  }
  if (
    typeof value === 'string' &&
    /^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(value) &&
    /quantity|count|minutes|rate|amount|stock|capacity|数量|工时|分钟|负荷|库存|产能|金额|比率/i.test(
      `${prop} ${column.label || ''}`,
    )
  ) {
    return formatAiNumber(value);
  }
  return value ?? '—';
}

// 图表只接受业务数据，不能透传模型给出的 HTML、脚本或 ECharts 配置。
export function normalizeAiBlocks(value) {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .slice(0, 12)
    .filter((block) => block && typeof block === 'object')
    .map((block) => ({
      title: String(block.title || '查询结果'),
      columns: (Array.isArray(block.columns) ? block.columns : [])
        .filter((col) => col && typeof col.prop === 'string')
        .slice(0, 30)
        .map((col) => ({ prop: col.prop, label: String(col.label || col.prop) })),
      rows: (Array.isArray(block.rows) ? block.rows : []).filter((row) => row && typeof row === 'object').slice(0, 200),
      chart: chartOption(block.chart),
    }));
}

function chartOption(chart) {
  if (!chart || !['bar', 'line'].includes(chart.type) || !Array.isArray(chart.categories)) {
    return null;
  }
  const categories = chart.categories.slice(0, 200).map(String);
  const series = (Array.isArray(chart.series) ? chart.series : []).slice(0, 6).map((item) => ({
    name: String(item.name || ''),
    type: chart.type,
    tooltip: { valueFormatter: (value) => formatAiChartValue(value, String(item.name || '')) },
    data: (Array.isArray(item.data) ? item.data : [])
      .slice(0, categories.length)
      .map((value) => (value !== null && value !== '' && Number.isFinite(Number(value)) ? Number(value) : null)),
  }));
  if (!categories.length || !series.length) {
    return null;
  }
  return {
    tooltip: { trigger: 'axis', renderMode: 'richText' },
    legend: { bottom: 0 },
    grid: { top: 20, bottom: 70, left: 60, right: 20 },
    xAxis: { type: 'category', data: categories, axisLabel: { hideOverlap: true } },
    yAxis: { type: 'value', axisLabel: { formatter: formatAiNumber } },
    series,
  };
}

export function aiHistory(messages) {
  return messages
    .filter((message) => ['user', 'assistant'].includes(message.role) && message.content)
    .slice(-20)
    .map((message) => ({ role: message.role, content: String(message.content).slice(0, 2000) }));
}
