// Search only the permission-filtered navigation supplied by the caller.
function normalize(value) {
  return String(value || '')
    .normalize('NFKC')
    .toLocaleLowerCase()
    .trim();
}

export function navigationResults(items, query, recent = [], limit = 8) {
  const keyword = normalize(query),
    tokens = keyword.split(/\s+/).filter(Boolean);
  const ranked = items
    .map((item, order) => {
      const label = normalize(item.label),
        haystack = normalize(`${item.label} ${item.description || ''} ${item.path || ''}`);
      if (tokens.some((token) => !haystack.includes(token))) return null;
      const relevance = !keyword
        ? 0
        : label === keyword
          ? 0
          : label.startsWith(keyword)
            ? 1
            : label.includes(keyword)
              ? 2
              : 3;
      const recentIndex = recent.indexOf(item.path);
      return { item, relevance, recentIndex: recentIndex < 0 ? Infinity : recentIndex, order };
    })
    .filter(Boolean);
  return ranked
    .sort((a, b) => a.relevance - b.relevance || a.recentIndex - b.recentIndex || a.order - b.order)
    .slice(0, limit)
    .map(({ item }) => item);
}

export function safeRecentPaths(value, items) {
  const allowed = new Set(items.map((item) => item.path));
  return [
    ...new Set(
      (Array.isArray(value) ? value : []).filter(
        (path) => typeof path === 'string' && path.startsWith('/') && !/[?#]/.test(path) && allowed.has(path),
      ),
    ),
  ].slice(0, 5);
}
