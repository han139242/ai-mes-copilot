/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import dayjs from 'dayjs';

// 开发预览只展示空白布局，不注入旧项目样例或伪造台灯生产实绩。
// 正式登录后的首页和大屏仍从后端读取实际数据。
export function createDashboardPreview() {
  return {
    summary: {
      productionOrderCount: 0,
      workOrderCount: 0,
      activeProductionOrderCount: 0,
      activeWorkOrderCount: 0,
      runningTaskCount: 0,
      todayQualifiedQuantity: 0,
      todayDefectQuantity: 0,
      inventoryWarningCount: 0,
      equipmentFaultCount: 0,
    },
    trend: Array.from({ length: 7 }, (_, index) => ({
      date: dayjs()
        .subtract(6 - index, 'day')
        .format('YYYY-MM-DD'),
      qualifiedQuantity: 0,
      defectQuantity: 0,
    })),
    quality: {
      inspectionCount: 0,
      evaluatedCount: 0,
      pendingCount: 0,
      qualifiedCount: 0,
      unqualifiedCount: 0,
      qualifiedRate: 0,
    },
    qualityClosure: { total: 0, closed: 0, rate: 0 },
    equipment: { running: 0, idle: 0, stopped: 0, fault: 0, maintenance: 0 },
    lines: [],
    schedule: [],
  };
}
