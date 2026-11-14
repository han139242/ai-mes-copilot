import request from '@/api/request';
import { getToken } from '@/utils/auth';

function verifyOwner(token) {
  if (getToken() !== token) {
    const error = new Error('登录会话已变化，请重新打开此页面');
    error.code = 'ERR_CANCELED';
    error.displayMessage = error.message;
    throw error;
  }
}

export async function privateAction(action, payload = {}, options = {}) {
  const expectedAuthToken = Object.prototype.hasOwnProperty.call(options, 'expectedAuthToken')
    ? options.expectedAuthToken
    : getToken();
  const result = await request.post(
    '/api/ai/private/dispatch',
    { action, payload },
    {
      timeout: action === 'question' ? 300000 : 30000,
      suppressErrorMessage: true,
      serviceDomain: 'private-assistant',
      ...options,
      expectedAuthToken,
    },
  );
  verifyOwner(expectedAuthToken);
  return result.data;
}

export async function uploadPrivateFile(file, documentId, expectedAuthToken = getToken()) {
  return privateFileRequest('/documents', file, documentId ? { documentId } : {}, { expectedAuthToken });
}

export async function privateFileRequest(path, file, fields = {}, options = {}) {
  const { expectedAuthToken = getToken(), ...requestOptions } = options;
  const data = new FormData();
  data.append('file', file);
  for (const [key, value] of Object.entries(fields)) data.append(key, value);
  const result = await request.post('/api/ai/private' + path, data, {
    timeout: 60000,
    suppressErrorMessage: true,
    serviceDomain: 'private-assistant',
    ...requestOptions,
    expectedAuthToken,
  });
  verifyOwner(expectedAuthToken);
  return result.data;
}

export async function faceAction(action, payload = {}, extra = {}) {
  const options = {
    timeout: 60000,
    suppressErrorMessage: true,
    serviceDomain: 'private-assistant',
    expectedAuthToken: getToken(),
    ...extra,
  };
  const result =
    action === 'status'
      ? await request.get('/api/auth/face/me', options)
      : await request.post(`/api/auth/face/${action}`, payload, options);
  verifyOwner(options.expectedAuthToken);
  return action === 'status' ? result.data : result;
}

export async function adminResetFace(userId, expectedAuthToken = getToken()) {
  const result = await request.delete(`/api/auth/face/admin/${encodeURIComponent(userId)}`, {
    suppressErrorMessage: true,
    serviceDomain: 'private-assistant',
    expectedAuthToken,
  });
  verifyOwner(expectedAuthToken);
  return result;
}
