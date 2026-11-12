// Vue Router schedules native history traversal asynchronously. Two aborted
// Back operations must restore the last committed entry once, rather than
// scheduling two relative corrections from the same browser position.
export function installPopRollbackRecovery(router) {
  const history = router.options.history;
  const originalGo = history.go,
    nativeGo = originalGo.bind(history);
  let committed = null,
    ticket = 0,
    active = true;
  const removeAfterEach = router.afterEach((_to, _from, failure) => {
    if (!failure) committed = { route: router.currentRoute.value, position: history.state?.position };
  });
  function wrappedGo(delta, triggerListeners = true) {
    if (triggerListeners !== false) return nativeGo(delta, triggerListeners);
    const route = router.currentRoute.value,
      anchor = committed;
    if (anchor?.route !== route || !Number.isFinite(anchor.position) || !Number.isFinite(history.state?.position)) {
      return nativeGo(delta, false);
    }
    const currentTicket = ++ticket;
    queueMicrotask(() => {
      if (!active || currentTicket !== ticket || router.currentRoute.value !== route || committed !== anchor) return;
      const currentPosition = history.state?.position;
      if (!Number.isFinite(currentPosition)) return nativeGo(delta, false);
      const correction = anchor.position - currentPosition;
      if (correction) nativeGo(correction, false);
      else if (history.location !== route.fullPath) nativeGo(delta, false);
    });
  }
  history.go = wrappedGo;
  return () => {
    active = false;
    ticket++;
    removeAfterEach();
    if (history.go === wrappedGo) history.go = originalGo;
  };
}
