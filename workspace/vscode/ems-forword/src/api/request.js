/*
 * 智能调光台灯 MES 系统
 * Copyright (c) 2026 AI-MES Copilot 项目贡献者组
 */
// Axios 是真正发送 HTTP 请求的工具；这里创建全项目共用的 Axios 实例。
import axios from 'axios';
// ElMessage 用于把后端错误以消息提示的形式展示给用户。
import { ElMessage } from 'element-plus';
// 每次请求前读取 Token；登录失效时统一清理登录信息。
import { clearAuth, getToken } from '@/utils/auth';
import { recordBackendConnection } from '@/utils/service-connection';

// 记录上一次错误，避免同一个接口错误在短时间内连续弹出多次。
let lastMessage = '';
let lastMessageAt = 0;

const GENERIC_FAILURE_MESSAGES = new Set(['操作失败', '业务处理异常', '请求处理失败']);

// 后端重启或未启动时，前端会连续收到连接被拒；这类提示要拉长去重窗口，避免刷屏。
const OUTAGE_MESSAGES = new Set(['服务暂时不可用，请稍后重试', '后端服务暂时无法连接，请稍后重试']);

// 网关/连接类故障的重试次数与退避间隔，只对幂等请求生效。
const MAX_RETRY = 2;
const RETRY_DELAYS = [600, 1800];

// 服务中断期间只提示一次；任意请求成功即视为恢复，下一次中断重新提示。
let outageNotified = false;

// 弹出错误消息，并在指定窗口内拦截完全相同的重复提示。
function showError(message, windowMs = 1500) {
  const now = Date.now();
  if (message === lastMessage && now - lastMessageAt < windowMs) {
    return;
  }
  lastMessage = message;
  lastMessageAt = now;
  ElMessage.error(message);
}

/**
 * 判断是否为「服务不可达」类故障。
 * Vite 代理在后端未启动时会把连接被拒包装成 HTTP 500 且响应体不是 JSON 信封，
 * 因此 500 也要看响应体形态，不能一律当成业务异常。
 */
function isServiceOutage(error) {
  const status = error.response?.status;
  if (!status) {
    return true;
  }
  if (status === 502 || status === 503 || status === 504) {
    return true;
  }
  if (status !== 500) {
    return false;
  }
  const { data } = error.response;
  const isEnvelope = data && typeof data === 'object' && typeof data.code !== 'undefined';
  return !isEnvelope;
}

/**
 * 网络层错误的中文提示：按 HTTP 状态与超时标记依次判断，命中即返回，
 * 都不命中时才回落到「连接失败」。
 */
function messageForError(status, error) {
  if (status === 401) {
    clearAuth();
    window.location.hash = '#/login';
    return '登录状态已失效，请重新登录';
  }
  if (status === 403) {
    return '当前账号没有操作权限';
  }
  if (status >= 500) {
    return '服务暂时不可用，请稍后重试';
  }
  if (error.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试';
  }
  return '后端服务暂时无法连接，请稍后重试';
}

/**
 * 服务中断提示只发一次：中断期间用户不需要被每分钟的轮询反复打扰，
 * 任意请求成功后自动复位，下一次中断会重新提示一次。
 */
function notifyOutageOnce(error, message) {
  if (outageNotified) {
    return;
  }
  outageNotified = true;
  showError(message, OUTAGE_MESSAGES.has(message) ? 10000 : 1500);
}

// 只对幂等的 GET 请求做自动重试，写操作重试可能造成重复提交。
function shouldRetry(error) {
  const { config } = error;
  if (!config || config.__retryCount >= MAX_RETRY) {
    return false;
  }
  if (axios.isCancel(error)) {
    return false;
  }
  const method = String(config.method || 'get').toLowerCase();
  if (method !== 'get') {
    return false;
  }
  // 超时本身就是等待过久，再重试只会让用户等更长时间。
  if (error.code === 'ECONNABORTED') {
    return false;
  }
  return isServiceOutage(error);
}

// 退避一段时间后重放同一个 GET 请求，短暂重启对用户无感。
async function retryLater(error) {
  const { config } = error;
  const attempt = (config.__retryCount || 0) + 1;
  config.__retryCount = attempt;
  await new Promise((resolve) => setTimeout(resolve, RETRY_DELAYS[attempt - 1]));
  return request(config);
}

// 后端只返回通用错误时，根据请求方法和接口动作补充更具体的操作建议。
function resolveOperationFallback(config) {
  const method = String(config?.method || '').toLowerCase();
  const [url] = String(config?.url || '').split('?', 1);
  if (method === 'delete') {
    return '删除失败：记录可能不存在或已删除，也可能处于不可删除状态、已被其他业务引用；请刷新后检查关联数据';
  }
  if (url.endsWith('/withdraw')) {
    return '撤回失败：当前单据状态不允许撤回，或已产生不能直接回退的下游数据；请刷新后查看最新状态';
  }
  return '';
}

// 按“后端详细信息 -> ResultData.msg -> 前端操作兜底”的优先级选出最终错误文案。
function resolveResultMessage(result, config) {
  const detail = result?.data?.message;
  const detailMessage = typeof detail === 'string' ? detail.trim() : '';
  if (detailMessage && !GENERIC_FAILURE_MESSAGES.has(detailMessage)) {
    return detailMessage;
  }
  const message = typeof result?.msg === 'string' ? result.msg.trim() : '';
  if (message && !GENERIC_FAILURE_MESSAGES.has(message)) {
    return message;
  }
  return resolveOperationFallback(config) || detailMessage || message || '请求处理失败';
}

// 本机开发时直连后端：长连接占满浏览器到 Vite 的连接后，普通 /api 请求可能排队超时。
// 局域网访问和正式构建仍沿用既有代理/部署地址；显式 VITE_API_BASE_URL 始终优先。
const localBackendUrl = import.meta.env.DEV &&
  ['localhost', '127.0.0.1'].includes(window.location.hostname)
  ? import.meta.env.VITE_API_PROXY_TARGET || ''
  : '';

// 创建统一请求实例。业务页面不直接使用原始 axios，而是统一导入这个 request。
const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || localBackendUrl,
  // 请求超过20秒仍未完成时按超时处理；个别慢接口可在请求配置里单独放大。
  timeout: 20000,
});

// 后端以 blob 方式请求时，失败响应同样会是 blob；这里把 JSON 错误体还原成对象。
async function readBlobError(blob) {
  if (!blob || typeof blob.text !== 'function') {
    return null;
  }
  try {
    const text = await blob.text();
    if (!text || !text.trim().startsWith('{')) {
      return null;
    }
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

// 网络层错误里后端可能已经给出可读原因，优先用它替代笼统提示。
function resolveNetworkMessage(error, status) {
  const data = error.response?.data;
  const detail = typeof data?.data?.message === 'string' ? data.data.message.trim() : '';
  const message = typeof data?.msg === 'string' ? data.msg.trim() : '';
  const candidate = detail || message;
  if (candidate && !GENERIC_FAILURE_MESSAGES.has(candidate)) {
    return candidate;
  }
  if (status === 401) {
    return '登录状态已失效，请重新登录';
  }
  if (status === 403) {
    return '当前账号没有操作权限';
  }
  if (status === 400) {
    return '请求参数无效，请刷新页面后重试';
  }
  if (status === 404) {
    return '目标接口或单据不存在，请核对地址';
  }
  if (status >= 400 && status < 500) {
    return '请求未被接受，请检查参数后重试';
  }
  if (status >= 500) {
    return '服务暂时不可用，请稍后重试';
  }
  return '';
}

// 请求拦截器：每个请求发出前自动执行，为已登录请求添加 JWT Token。
request.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    // 后端从 Authorization: Bearer <token> 中识别当前登录用户。
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：后端返回后统一解析 ResultData，业务页面不必重复判断code。
request.interceptors.response.use(
  (response) => {
    recordBackendConnection(true);
    // 下载Excel或文件时返回的是二进制，不按普通JSON处理。
    if (response.config.responseType === 'blob') {
      return response;
    }

    // 普通接口约定返回 { code, msg, data }。
    const result = response.data;
    if (!result || typeof result.code === 'undefined') {
      return result;
    }

    if (Number(result.code) === 200) {
      // code=200表示业务成功，直接把ResultData交给调用页面。
      outageNotified = false;
      return result;
    }

    if (Number(result.code) === 1002 || Number(result.code) === 401) {
      // Token失效：清理本地登录信息，并跳回登录页，同时保留原目标地址。
      clearAuth();
      const redirect = encodeURIComponent(window.location.hash.slice(1) || '/dashboard');
      window.location.hash = `#/login?redirect=${redirect}`;
    }

    const message = resolveResultMessage(result, response.config);
    const error = new Error(message);
    error.code = result.code;
    error.data = result.data;
    error.displayMessage = message;
    if (!response.config.suppressErrorMessage) {
      showError(message);
    }
    return Promise.reject(error);
  },
  async (error) => {
    // 第二个回调处理网络层错误，例如401、403、服务器异常和超时。
    if (axios.isCancel(error)) {
      return Promise.reject(error);
    }
    recordBackendConnection(!isServiceOutage(error));

    // 后端短暂不可达时先自动重试，重试期间不弹提示，避免用户被反复打断。
    if (shouldRetry(error)) {
      return retryLater(error);
    }

    const status = error.response?.status;
    let message = messageForError(status, error);

    // 后端返回了明确原因时优先展示，避免用户只看到“服务暂时不可用”。
    if (status) {
      if (error.response?.data instanceof Blob) {
        const parsed = await readBlobError(error.response.data);
        if (parsed) {
          error.response.data = parsed;
        }
      }
      const resolved = resolveNetworkMessage(error, status);
      if (resolved) {
        message = resolved;
      }
    }

    error.displayMessage = message;
    if (!error.config?.suppressErrorMessage) {
      const outage = isServiceOutage(error) || OUTAGE_MESSAGES.has(message);
      if (outage) {
        // 后端重启/未启动时轮询接口会连续失败，这里一次中断只提示一次。
        notifyOutageOnce(error, message);
      } else {
        showError(message);
      }
    }
    return Promise.reject(error);
  },
);

// 默认导出统一Axios实例，其他API文件可以用任意本地名称import它。
export default request;
