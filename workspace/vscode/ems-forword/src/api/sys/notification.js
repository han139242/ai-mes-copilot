/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 查询当前登录用户的未读通知数量，主要供顶部铃铛角标使用。
// 顶部栏会定时轮询，这里默认静默失败，避免后端重启期间反复弹提示。
export function getNotificationUnreadCount(options = {}) {
  return request.get('/api/sys/notification/unread-count', { suppressErrorMessage: true, ...options });
}

// 分页查询通知中心列表，params 中包含页码、每页条数、分类和已读状态等筛选条件。
// silent=true 时用于后台轮询，不打断用户操作。
export function getNotificationPage(params = {}, options = {}) {
  return request.get('/api/sys/notification/page', { params, ...options });
}

// recipientId 是“某用户收到的通知记录ID”，放在URL路径中查询单条详情。
export function getNotificationDetail(recipientId) {
  return request.get(`/api/sys/notification/${recipientId}`);
}

// PUT 表示修改现有接收记录，将指定通知状态更新为已读。
export function markNotificationRead(recipientId) {
  return request.put(`/api/sys/notification/${recipientId}/read`);
}

// 批量标记已读；接口没有请求体，所以第二个参数传 null，category 通过查询参数传递。
export function markAllNotificationsRead(category) {
  return request.put('/api/sys/notification/read-all', null, { params: category ? { category } : {} });
}

// 确认通知用于需要用户明确知晓的业务消息，和普通“已读”语义不同。
export function acknowledgeNotification(recipientId) {
  return request.put(`/api/sys/notification/${recipientId}/ack`);
}
