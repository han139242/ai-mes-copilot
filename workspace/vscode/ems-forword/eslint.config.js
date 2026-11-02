/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// 仅用于代码规范整改的 ESLint 配置：开启与 CodeArts 告警对应的少量规则。
import pluginVue from 'eslint-plugin-vue';
import vueParser from 'vue-eslint-parser';

export default [
  {
    ignores: ['dist/**', 'node_modules/**', 'tests/**'],
  },
  ...pluginVue.configs['flat/base'],
  {
    files: ['**/*.js', '**/*.vue'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      parser: vueParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    rules: {
      // CodeArts G.FMT.09：if/for/while/else 执行体一律加大括号。
      curly: ['error', 'all'],
      // CodeArts G.TYP.05：字符串拼接优先使用模板字符串。
      'prefer-template': 'error',
      // CodeArts G.EXP.04：混合操作符加括号明确优先级。
      'no-mixed-operators': 'error',
      // CodeArts G.TYP.10：优先使用解构赋值。
      'prefer-destructuring': ['error', { array: true, object: false }],
      // CodeArts 单字符字符串用单引号（由 prettier 的 singleQuote 保证）。
      quotes: ['error', 'single', { avoidEscape: true }],
      // CodeArts G.EXP.03：禁止嵌套三元表达式。
      'no-nested-ternary': 'error',
      // CodeArts G.EXP.06：正则使用命名捕获组。
      'prefer-named-capture-group': 'error',
      // CodeArts G.DCL.07：避免内层变量遮盖外层同名变量。
      'no-shadow': ['error', { builtinGlobals: false, hoist: 'functions' }],
      // CodeArts G.DCL.03：每个语句只声明一个变量。
      'one-var': ['error', 'never'],
      // CodeArts G.MET.07：函数内保持一致的 return 形态。
      'consistent-return': 'error',
      // CodeArts G.MET.09：不要给函数入参重新赋值。
      'no-param-reassign': ['error', { props: false }],
      // CodeArts G.FMT.02：单行不超过 120 字符。
      'max-len': ['error', { code: 120, ignoreUrls: true, ignoreStrings: true, ignoreTemplateLiterals: true }],
      // CodeArts G.MET.02 / G.CTL.07：控制流复杂度上限。
      complexity: ['error', 20],
    },
  },
];
