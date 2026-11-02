export function formatDistance(meters: number) {
  if (meters < 1000) return `${Math.max(10, Math.round(meters / 10) * 10)} m`;
  return `${(meters / 1000).toFixed(1).replace('.', ',')} km`;
}

export const resultCount = (total: number) =>
  total === 0
    ? 'Aucun établissement'
    : `${total} établissement${total > 1 ? 's' : ''}`;
