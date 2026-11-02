/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';
export const getPreparationProducts = () => request.get('/api/mdm/manufacturing-preparation/products');
export const getPreparation = (id, asOf, signal) =>
  request.get(`/api/mdm/manufacturing-preparation/${id}`, { params: { asOf }, signal });
export const editProductParameter = (id, data) => request.put(`/api/mdm/product-parameter/${id}`, data);
export const reviewProductParameter = (id, version, action) =>
  request.post(`/api/mdm/product-parameter/${id}/${action}`, { version });
export const reviewInspectionStandard = (id, version, action) =>
  request.post(`/api/qms/inspection-standard/${id}/${action}`, { version });
