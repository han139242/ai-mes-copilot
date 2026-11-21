import { sliceUtf16 } from './text.js';

let pending;

// Drafts from private knowledge are carried in memory for the current login only.
export function queueBusinessDraft(text, ownerToken) {
  const value = sliceUtf16(String(text || ''), 500);
  if (!value.trim() || !ownerToken) return () => {};
  const handoff = { text: value, ownerToken };
  pending = handoff;
  return () => {
    if (pending === handoff) pending = undefined;
  };
}

export function takeBusinessDraft(ownerToken) {
  const handoff = pending;
  pending = undefined;
  return handoff?.ownerToken === ownerToken ? handoff?.text || '' : '';
}
