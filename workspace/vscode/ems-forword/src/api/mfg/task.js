/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import request from '@/api/request';

// 获取生产任务提醒汇总，例如待开工、超期等数量，供顶部任务提醒区域展示。
export function getTaskReminderSummary() {
  return request.get('/api/mfg/task/reminder-summary');
}
