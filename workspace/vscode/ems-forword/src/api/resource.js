/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 通用资源接口层：把各业务页面重复使用的 CRUD 请求集中封装起来。
// baseUrl 由资源配置提供，例如 /api/mfg/production-order；真正发送请求、添加 Token
// 和处理 ResultData 的工作仍由 request.js 完成。
// 分页查询：自动在资源根路径后拼接/page，params会被Axios转换成?current=1&size=20等查询参数。
export function getResourcePage(baseUrl, params, options = {}) {
  return request.get(`${baseUrl}/page`, { params, ...options });
}

// 列表查询：url由调用方提供，适合树结构、主从明细等不一定带/page的接口。
export function getResourceList(url, params) {
  return request.get(url, { params });
}

// 详情查询：把主键id拼到baseUrl后，例如GET /api/sys/role/10。
export function getResourceDetail(baseUrl, id) {
  return request.get(`${baseUrl}/${id}`);
}

// 新增资源：POST负责创建数据，data作为JSON请求体发送；path可覆盖/full等特殊新增地址。
export function createResource(baseUrl, data, path = '') {
  return request.post(`${baseUrl}${path}`, normalizeResourcePayload(baseUrl, data));
}

// 修改资源：PUT表示更新已有记录，data中通常包含主键、修改字段和乐观锁version。
export function updateResource(baseUrl, data, path = '') {
  return request.put(`${baseUrl}${path}`, normalizeResourcePayload(baseUrl, data));
}

// BOM可空引用清空用显式0表达；未传字段保持部分更新语义。
function normalizeResourcePayload(baseUrl, data) {
  if (baseUrl === '/api/mfg/report-material') {
    return normalizeReportMaterial(data);
  }
  if (baseUrl === '/api/mfg/work-report' && Array.isArray(data.materials)) {
    return { ...data, materials: data.materials.map(normalizeReportMaterial) };
  }
  if (baseUrl === '/api/mdm/bom-item') {
    const normalized = { ...data };
    for (const key of ['childBomId', 'consumptionOperationId']) {
      if (Object.prototype.hasOwnProperty.call(data, key) && !data[key]) {
        normalized[key] = 0;
      }
    }
    return normalized;
  }
  return data;
}

function normalizeReportMaterial(row) {
  if (!Object.prototype.hasOwnProperty.call(row, 'totalUsedQuantity')) {
    return row;
  }
  const result = { ...row };
  // 旧服务仍按“非报废+报废”扣料；同步派生旧字段，前后端滚动更新期间也不会重复扣料。
  result.usedQuantity = Number((Number(row.totalUsedQuantity) - Number(row.scrapQuantity || 0)).toFixed(4));
  return result;
}

// 删除资源：默认请求baseUrl/id；少数特殊接口可以直接传入完整path覆盖默认地址。
export function deleteResource(baseUrl, id, path) {
  return request.delete(path || `${baseUrl}/${id}`);
}

// release、withdraw、audit 等非普通 CRUD 操作的请求方法和地址不固定，统一走此入口。
export function executeResourceAction({ method = 'put', url, data, params }) {
  return request({ method, url, data, params });
}

// blob 表示后端返回的是 Excel、图片等二进制文件，而不是普通 JSON。
export function exportResource(baseUrl, params) {
  return request.get(`${baseUrl}/excel`, { params, responseType: 'blob' });
}

// 下载文件：responseType设为blob，阻止Axios把二进制内容当作ResultData JSON解析。
export function downloadResource(url) {
  return request.get(url, { responseType: 'blob' });
}

// 上传文件：formData中保存文件和关联业务字段，后端通过MultipartFile接收。
export function uploadResource(url, formData) {
  // multipart/form-data 用来同时提交文件内容和业务字段。
  return request.post(url, formData, { headers: { 'Content-Type': 'multipart/form-data' } });
}
