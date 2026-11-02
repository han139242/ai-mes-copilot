/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
import { computed, ref } from 'vue';

// 这里只表示实际 HTTP 连接状态，不用固定绿点冒充整体业务健康。
export const backendConnection = ref('unknown');
export const backendConnectionLabel = computed(() => {
  if (backendConnection.value === 'connected') {
    return '后端已连接';
  }
  if (backendConnection.value === 'disconnected') {
    return '后端连接中断';
  }
  return '正在连接后端';
});

export function recordBackendConnection(connected) {
  backendConnection.value = connected ? 'connected' : 'disconnected';
}
