// Stable comparisons for in-memory drafts. Do not persist business form data.
export function draftFingerprint(value) {
  function normalize(item) {
    if (item instanceof Date) return item.toISOString();
    if (Array.isArray(item)) return item.map(normalize);
    if (item && typeof item === 'object')
      return Object.fromEntries(
        Object.keys(item)
          .filter((key) => key !== '_clientId')
          .sort()
          .map((key) => [key, normalize(item[key])]),
      );
    return item === undefined ? null : item;
  }
  return JSON.stringify(normalize(value));
}
