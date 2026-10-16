export function imageName(path) {
  return path?.split(/[\\/]/).pop() || '—'
}

// Keep the selected image on refresh, but replace its stale RUNNING object.
// Changing pages/filters must discard a selection that is no longer visible.
export function selectVisibleResult(items, selectedId) {
  return items.find(item => String(item.id) === String(selectedId))
    || items.find(item => item.status === 'SUCCESS')
    || items[0]
    || null
}

export function resultOptions(items, selected) {
  return selected && !items.some(item => item.id === selected.id) ? [selected, ...items] : items
}
