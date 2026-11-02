/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
// navigation.js根据资源配置和当前账号权限生成侧边菜单，而不是把80个菜单逐项写死。
import { domainDefinitions, getDomainResources } from '@/config/resources';
import { hasPermission, resourcePermission } from '@/utils/permission';

const mfgNavigationOrder = new Map(
  [
    'mfg-production-order',
    'mfg-production-plan',
    'mfg-work-order',
    'mfg-dispatch-order',
    'mfg-task',
    'mfg-work-report',
    'mfg-exception',
    'mfg-andon-alert',
    'mfg-line-product-capability',
    'mfg-line-calendar-rule',
  ].map((key, index) => [key, index]),
);

function orderedDomainResources(domain) {
  const resources = getDomainResources(domain);
  if (domain !== 'mfg') {
    return resources;
  }
  return [...resources].sort(
    (left, right) =>
      (mfgNavigationOrder.get(left.key) ?? Number.MAX_SAFE_INTEGER) -
      (mfgNavigationOrder.get(right.key) ?? Number.MAX_SAFE_INTEGER),
  );
}

// 各业务域独有的特殊页面入口；未列出的域视为没有特殊入口。
const SPECIAL_DOMAIN_ENTRIES = {
  mfg: [
    {
      label: '智能排产工作台',
      icon: 'route',
      path: '/mfg/schedule-workbench',
      description: '计算排程方案、识别风险并人工确认生产计划',
      permission: 'mfg:schedule-scenario:view',
    },
    {
      label: '流程优化对比',
      icon: 'activity',
      path: '/mfg/process-optimization',
      description: '领料、检验、追溯三条主线的改造前后对照',
      permission: 'mfg:work-order:view',
    },
    {
      label: '跨层资源冲突',
      icon: 'alert',
      path: '/mfg/resource-conflicts',
      description: '识别组件晚于父工单开工、同线时段重叠两类风险',
      permission: 'mfg:production-plan:view',
    },
  ],
  mdm: [
    {
      label: '制造准备检查',
      icon: 'clipboard',
      path: '/mdm/manufacturing-preparation',
      description: '核对参数、工艺资源及检验依据',
      permission: 'mdm:manufacturing-preparation:view',
    },
    {
      label: 'BOM 需求分析',
      icon: 'route',
      path: '/mdm/bom-explorer',
      description: '查看组件结构、合并用料及半成品抵扣',
      permission: 'mdm:bom:expand',
    },
  ],
};

// 将某个业务域下的资源配置转换成可点击菜单项。
function domainEntries(domain) {
  const specialEntries = SPECIAL_DOMAIN_ENTRIES[domain] || [];
  return [
    ...specialEntries,
    ...orderedDomainResources(domain).map((resource) => ({
      label: resource.title,
      icon: resource.icon,
      path: resource.routePath,
      resourceKey: resource.key,
      description: resource.description,
      permission: resourcePermission(resource.key, 'view'),
    })),
  ];
}

// 把业务域定义转换成一个可展开的父菜单，并挂载该域下的资源子菜单。
function moduleEntry(domain) {
  // 一个moduleEntry对应侧边栏中的一个可展开模块，例如生产制造或仓储库存。
  const definition = domainDefinitions[domain];
  return {
    key: domain,
    label: definition.title,
    icon: definition.icon,
    permission: `module:${domain}:view`,
    children: domainEntries(domain),
  };
}

// 将业务域定义整理成模块目录，模块导航页可直接根据key读取完整入口。
export const moduleCatalog = Object.fromEntries(
  Object.entries(domainDefinitions).map(([key, definition]) => [
    key,
    {
      ...definition,
      entries: domainEntries(key),
    },
  ]),
);

// 大屏和AI不是通用resource页面，所以单独加入模块目录。
moduleCatalog['big-screen'] = {
  title: '可视化大屏',
  icon: 'monitor',
  tone: 'blue',
  description: '生产运营指挥与实时数据监控',
  entries: [
    { label: '生产运营大屏', path: '/big-screen/production', icon: 'monitor', permission: 'dashboard:big-screen:view' },
  ],
};

moduleCatalog.ai = {
  title: 'AI 工作台',
  icon: 'bot',
  tone: 'blue',
  description: '业务问答、看板解读、风险分析与智能写作',
  entries: [
    { label: '进入 AI 工作台', path: '/ai/workbench', icon: 'bot', permission: 'ai:assistant:use' },
    { label: '执行监控', path: '/ai/agent-monitor', icon: 'monitor', permission: 'ai:assistant:use' },
    { label: '自动化规则', path: '/ai/automation', icon: 'workflow', permission: 'ai:assistant:use' },
    { label: '自动化任务', path: '/ai/tasks', icon: 'workflow', permission: 'ai:assistant:use' },
    { label: '异常闭环', path: '/ai/anomaly', icon: 'alert', permission: 'ai:assistant:use' },
    { label: '预警中心', path: '/ai/alert', icon: 'bell', permission: 'ai:assistant:use' },
  ],
};

// 侧边栏菜单的业务分组顺序，最终还会经过filterItem递归权限过滤。
export const navigationGroups = [
  {
    label: '重点能力',
    items: [
      { label: 'AI 工作台', icon: 'bot', path: '/ai/workbench', featured: true, permission: 'ai:assistant:use' },
      // 预警中心要在侧边栏能直接点到：它的"改限度"就在页面里的规则页签，
      // 只放在模块目录里等于没有入口。
      { label: '预警中心', icon: 'bell', path: '/ai/alert', permission: 'ai:assistant:use' },
      {
        label: '可视化大屏',
        icon: 'monitor',
        path: '/big-screen/production',
        featured: true,
        permission: 'dashboard:big-screen:view',
      },
    ],
  },
  {
    label: '运营中心',
    items: [
      { label: '首页', icon: 'dashboard', path: '/dashboard', permission: 'dashboard:summary:view' },
      moduleEntry('mfg'),
      moduleEntry('wms'),
      moduleEntry('qms'),
    ],
  },
  {
    label: '制造基础',
    items: [moduleEntry('mdm'), moduleEntry('process'), moduleEntry('factory')],
  },
  {
    label: '运营保障',
    items: [moduleEntry('eam'), moduleEntry('trace'), moduleEntry('org'), moduleEntry('sys')],
  },
];

// 模块工作台只返回当前用户有权访问的入口。
export function getModuleDefinition(moduleKey) {
  const definition = moduleCatalog[moduleKey] || moduleCatalog.sys;
  return {
    ...definition,
    entries: (definition.entries || []).filter((item) => !item.permission || hasPermission(item.permission)),
  };
}

// 递归把多级菜单转成扁平数组，供顶部搜索和侧边栏搜索使用。
function flattenItems(items) {
  return items.flatMap((item) => [
    ...(item.path || item.action ? [item] : []),
    ...(item.children ? flattenItems(item.children) : []),
  ]);
}

// 获取当前用户最终可见的全部菜单入口，并展开为一维数组。
export function getNavigationItems() {
  const menuItems = getNavigationGroups().flatMap((group) => flattenItems(group.items));
  // AI 子页面属于模块目录，搜索也应能找到，但仍须执行相同的权限过滤。
  const aiItems = (moduleCatalog.ai.entries || []).map(filterItem).filter(Boolean);
  const itemsByPath = new Map();
  [...menuItems, ...aiItems].forEach((item) => {
    const key = item.path || item.action;
    if (key && !itemsByPath.has(key)) {
      itemsByPath.set(key, item);
    }
  });
  return [...itemsByPath.values()];
}

const preferredHomePaths = ['/dashboard', '/eam/equipment', '/eam/repair-orders'];

export function firstAccessiblePath(fallback = '/dashboard') {
  const items = getNavigationItems();
  return (
    preferredHomePaths.find((path) => items.some((item) => item.path === path)) ||
    items.find((item) => item.path)?.path ||
    fallback
  );
}

export function isNavigationPathAccessible(path) {
  const [plainPath] = String(path || '').split(/[?#]/, 1);
  return Boolean(plainPath) && getNavigationItems().some((item) => item.path === plainPath);
}

// 逐组执行权限过滤，删除没有任何可见子项的空分组。
export function getNavigationGroups() {
  return navigationGroups
    .map((group) => ({
      ...group,
      items: group.items.map(filterItem).filter(Boolean),
    }))
    .filter((group) => group.items.length);
}

// 权限过滤的核心：无权限菜单直接移除；父菜单没有任何可见子项时也不显示。
function filterItem(item) {
  if (item.permission && !hasPermission(item.permission)) {
    return null;
  }
  if (!item.children) {
    return item;
  }
  const children = item.children.map(filterItem).filter(Boolean);
  return children.length ? { ...item, children } : null;
}
