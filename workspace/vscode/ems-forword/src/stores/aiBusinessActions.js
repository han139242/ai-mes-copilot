import { ref, watch } from 'vue';
import { getAgentCapabilities } from '@/api/ai/agent';
import { authRevision, getCurrentUser, getToken } from '@/utils/auth';
import { hasPermission } from '@/utils/permission';
import { permittedBusinessCommands } from '@/utils/ai-business-flow';

export const businessCommands = ref([]);
export const businessCommandsState = ref('idle');
let pending;

watch(
  authRevision,
  () => {
    pending = undefined;
    businessCommands.value = [];
    businessCommandsState.value = 'idle';
  },
  { flush: 'sync' },
);

export function loadBusinessCommands({ force = false } = {}) {
  if (!getToken() || !hasPermission('ai:assistant:use')) return;
  if (pending) return pending;
  if (!force && businessCommandsState.value === 'ready') return;
  const revision = authRevision.value;
  businessCommandsState.value = 'loading';
  pending = getAgentCapabilities()
    .then((response) => {
      if (revision !== authRevision.value) return;
      const actions = Array.isArray(response) ? response : response?.data;
      const user = getCurrentUser();
      const fullDataScope = user.superAdmin || user.roles?.includes('SUPER_ADMIN') || user.dataScope === 'ALL';
      businessCommands.value = permittedBusinessCommands(Array.isArray(actions) ? actions : [], hasPermission, fullDataScope);
      businessCommandsState.value = 'ready';
    })
    .catch(() => {
      if (revision !== authRevision.value) return;
      businessCommands.value = [];
      businessCommandsState.value = 'error';
    })
    .finally(() => {
      if (revision === authRevision.value) pending = undefined;
    });
  return pending;
}
