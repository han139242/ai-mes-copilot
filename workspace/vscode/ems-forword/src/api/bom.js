/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

export function getBomExplosion(bomId, params, signal) {
  return request.get(`/api/mdm/bom/${bomId}/explosion`, { params, signal });
}
