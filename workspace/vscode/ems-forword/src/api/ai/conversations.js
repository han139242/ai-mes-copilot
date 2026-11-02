/*
 * 版权所有 (c) 智能调光台灯 MES 项目组 2026
 * 本项目为课程实训作品，版权说明按 CodeArts 文件头注释规范补充。
 */
import request from '@/api/request';

// 会话历史是页面辅助信息：接口不可用时页面退化为本地会话，不能因为拉历史失败就弹红条。
const CONVERSATION_TIMEOUT = 20000;

function silent(config = {}) {
  return { ...config, suppressErrorMessage: true };
}

// 会话分页：默认只查未归档，keyword 按标题模糊匹配。
export function listConversations(params = {}) {
  return request.get('/api/ai/conversations', silent({ params, timeout: CONVERSATION_TIMEOUT }));
}

// 新建会话，返回落库后的会话对象（含 conversationId）。
export function createConversation(data = {}) {
  return request.post('/api/ai/conversations', data, silent({ timeout: CONVERSATION_TIMEOUT }));
}

// 会话详情：一次带回会话本身和分页后的消息列表。
export function getConversationDetail(conversationId, params = {}) {
  return request.get(`/api/ai/conversations/${conversationId}`, silent({ params, timeout: CONVERSATION_TIMEOUT }));
}

// 重命名会话，用户首句提问后用它把「新对话」改成可辨识的标题。
export function renameConversation(conversationId, title) {
  return request.put(`/api/ai/conversations/${conversationId}/rename`, { title }, silent());
}

// 归档或取消归档。
export function setConversationArchived(conversationId, archived = true) {
  return request.put(`/api/ai/conversations/${conversationId}/archive`, { archived }, silent());
}

// 逻辑删除会话及其消息。
export function deleteConversation(conversationId) {
  return request.delete(`/api/ai/conversations/${conversationId}`, silent());
}

// 追加一条消息；正常问答由后端在 /api/ai/chat 里自动落库，这里只用于补偿写入。
export function appendConversationMessage(conversationId, message) {
  return request.post(`/api/ai/conversations/${conversationId}/messages`, message, silent());
}

// 取最近若干条消息，用于恢复上下文或跨设备续聊。
export function getConversationMessages(conversationId, limit = 50) {
  return request.get(`/api/ai/conversations/${conversationId}/messages`, silent({ params: { limit } }));
}
