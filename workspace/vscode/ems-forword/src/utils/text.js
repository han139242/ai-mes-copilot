// Java's @Size and browser maxlength count UTF-16 units. Keep that budget
// without splitting a valid supplementary character at the cut point.
export function sliceUtf16(value, limit) {
  const text = String(value ?? '');
  let end = Math.max(0, Math.trunc(limit));
  if (end >= text.length) return text;
  if (end > 0 && /[\uD800-\uDBFF]/.test(text[end - 1]) && /[\uDC00-\uDFFF]/.test(text[end])) end--;
  return text.slice(0, end);
}
