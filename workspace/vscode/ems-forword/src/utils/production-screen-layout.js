// Keep the 1920x1080 monitoring canvas inside the viewport and clear of mobile controls.
export function productionScreenLayout(width, height) {
  const viewportWidth = Math.max(1, Number(width) || 1);
  const viewportHeight = Math.max(1, Number(height) || 1);
  const toolbarHeight = viewportWidth <= 960 ? Math.min(56, viewportHeight - 1) : 0;
  const usableHeight = Math.max(1, viewportHeight - toolbarHeight);
  const scale = Math.min(viewportWidth / 1920, usableHeight / 1080);
  return {
    scale,
    offsetX: Math.max(0, (viewportWidth - 1920 * scale) / 2),
    offsetY: toolbarHeight + Math.max(0, (usableHeight - 1080 * scale) / 2),
    toolbarHeight,
  };
}
