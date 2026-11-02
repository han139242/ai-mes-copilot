/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';

// 资源配置文件里的相对导入不带 .js 后缀（Vite 能解析，Node 不能）。
// 这里只补这一个解析规则，保证测试读到的就是真实配置文件，不是复制品。
registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier.startsWith('./') && !/\.[cm]?js$/.test(specifier)) {
      return nextResolve(`${specifier}.js`, context);
    }
    return nextResolve(specifier, context);
  },
});

const mfgPath = fileURLToPath(new URL('../src/config/resources/mfg.js', import.meta.url));
const { default: mfg } = await import(pathToFileURL(mfgPath).href);
const { contextPayloadFields } = await import('../src/utils/resource-page-state.js');

function resourceOf(key) {
  const found = mfg.find((item) => item.key === key);
  assert.ok(found, `资源 ${key} 必须存在`);
  return found;
}

test('报工表单隐藏的 taskId 仍随请求提交，否则后端判为缺少生产任务', () => {
  const config = resourceOf('mfg-work-report');
  const taskField = config.fields.find((item) => item.prop === 'taskId');
  assert.equal(taskField.form, false, 'taskId 不应显示在报工表单里');
  assert.deepEqual(contextPayloadFields(config), ['taskId']);
  assert.equal(config.contextCreateOnly, true, '生产报工只能从生产任务行带上下文进入');
});

test('报工允许不带用料提交，整机工序的用料已在领料环节扣减', () => {
  assert.equal(resourceOf('mfg-work-report').fullChild.allowEmpty, true);
});

test('所有带必填隐藏字段的资源都声明了 contextFields', () => {
  const missing = [];
  for (const config of mfg) {
    if (!Array.isArray(config.fields)) {
      continue;
    }
    const hiddenRequired = config.fields
      .filter((item) => item.form === false && item.required)
      .map((item) => item.prop);
    if (!hiddenRequired.length) {
      continue;
    }
    const allowed = new Set(contextPayloadFields(config));
    const dropped = hiddenRequired.filter((prop) => !allowed.has(prop));
    if (dropped.length) {
      missing.push(`${config.key}: ${dropped.join(', ')}`);
    }
  }
  assert.deepEqual(missing, [], `这些资源的必填字段不会进入请求体：\n${missing.join('\n')}`);
});

test('隐藏字段只有在 contextFields 声明后才会随请求透传', () => {
  assert.deepEqual(contextPayloadFields({ fields: [{ prop: 'taskId' }] }), []);
  assert.deepEqual(contextPayloadFields({ fields: [{ prop: 'taskId' }], contextFields: ['taskId'] }), ['taskId']);
});
