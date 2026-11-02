/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
// 演练入口与历史入口独立配置品牌，不改写历史业务记录。
// 当前仓库为“智能调光台灯 MES”演练前端，产品名、副标题和特性文案集中在这里维护，
// 登录页、侧边栏、顶栏和大屏都从这里取值，避免品牌名散落在各个页面里。
const scope = import.meta.env.VITE_APP_SCOPE || 'lamp-rehearsal';
const isLampScope = scope.startsWith('lamp');

export const appName = import.meta.env.VITE_APP_NAME || (isLampScope ? '智能调光台灯 MES' : '羽球智造 MES');

// 侧边栏、顶栏空间有限时使用短名称，避免中英文混排被截断。
export const appShortName = import.meta.env.VITE_APP_SHORT_NAME || (isLampScope ? '台灯 MES' : '羽球 MES');

// 登录页左侧品牌区使用的产品定位文案。
export const appSubtitle = import.meta.env.VITE_APP_SUBTITLE || '智能调光台灯数字化制造执行系统';

// 首批产品型号，登录页特性列表和首页欢迎语引用。
export const productModels = ['L100', 'L200'];

// 登录页左侧特性列表：说明系统解决什么问题，而不是罗列技术名词。
export const appHighlights = [
  { icon: 'clipboard', title: '生产全程可追溯', text: '从工单下达到整机入库，批次、工序与检验记录一一对应' },
  { icon: 'shield-check', title: '质量闭环管控', text: '出货检验、不合格品处置与返工流程在线留痕' },
  { icon: 'warehouse', title: '仓储实时可视', text: '成品批次、库位与可发数量同步，销售出库一键生成' },
  { icon: 'bot', title: 'AI 智能助手', text: '自然语言解读看板数据，自动生成表格与图表' },
];
