import { test } from 'node:test'
import assert from 'node:assert/strict'
import { aiHistory, formatAiCell, formatAiNumber, normalizeAiBlocks } from '../src/utils/ai-results.js'

test('chart renderer accepts data and discards executable options', () => {
  const [result] = normalizeAiBlocks([{ title: '本周排程', columns: [{ prop: 'line', label: '产线' }], rows: [{ line: '总装线' }],
    chart: { type: 'bar', categories: ['总装线'], tooltip: { formatter: '<script>bad()</script>' },
      series: [{ name: '分钟', data: [120], itemStyle: { color: 'javascript:bad()' } }] } }])
  assert.equal(result.chart.series[0].data[0], 120)
  assert.equal(result.chart.tooltip.renderMode, 'richText')
  assert.equal(result.chart.tooltip.formatter, undefined)
  assert.equal(result.chart.series[0].itemStyle, undefined)
})
test('history excludes local error and system messages', () => {
  assert.deepEqual(aiHistory([{ role: 'system', content: 'error' }, { role: 'user', content: '本周排程' }, { role: 'assistant', content: '结果' }]),
    [{ role: 'user', content: '本周排程' }, { role: 'assistant', content: '结果' }])
})
test('invalid or empty blocks render without exceptions', () => {
  assert.deepEqual(normalizeAiBlocks(null), [])
  assert.equal(normalizeAiBlocks([{ chart: { type: 'html', categories: ['x'] } }])[0].chart, null)
})

test('AI chart tooltips show readable minutes without changing calculation data', () => {
  const [result] = normalizeAiBlocks([{ chart: { type: 'bar', categories: ['控制底座组件线'],
    series: [{ name: '占用工时(分钟)', data: [217.4167] }] } }])
  const series = result.chart.series[0]
  assert.equal(series.data[0], 217.4167)
  assert.equal(series.tooltip.valueFormatter(series.data[0]), '217.42 分钟（约 3 小时 37 分）')
  assert.equal(result.chart.yAxis.axisLabel.formatter(1000000), '1,000,000')
})

test('AI numeric display removes trailing zeros and preserves small nonzero quantities', () => {
  assert.equal(formatAiNumber('10.0000'), '10')
  assert.equal(formatAiNumber(1234567.4167), '1,234,567.42')
  assert.equal(formatAiNumber(0.004167), '0.0042')
  assert.equal(formatAiNumber(null), '—')
  assert.equal(formatAiNumber(Infinity), '—')
})

test('AI table formatting preserves codes, versions and underlying values', () => {
  const row = { loadMinutes: '217.4167', materialCode: '001234', version: '1.2345' }
  assert.equal(formatAiCell(row, { property: 'loadMinutes', label: '占用工时(分钟)' }, row.loadMinutes), '217.42')
  assert.equal(formatAiCell(row, { property: 'materialCode', label: '物料编码' }, row.materialCode), '001234')
  assert.equal(formatAiCell(row, { property: 'version', label: '版本' }, row.version), '1.2345')
  assert.equal(row.loadMinutes, '217.4167')
})
