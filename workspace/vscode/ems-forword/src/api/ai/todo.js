/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 我的待办：九类业务待办按账号权限汇总，面板定时刷新，失败时由页面显示空态，
// 因此这里统一静默，接口未就绪时不会满屏弹红条。
function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 返回 { items, counts, total, generatedAt }，counts 的键是九类待办编码，计数为 0 的也会返回。
export function getMyTodos() {
  return request.get('/api/ai/todo/my', silent());
}
