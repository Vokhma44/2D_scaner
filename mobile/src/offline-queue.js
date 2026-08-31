export const DEFAULT_QUEUE_LIMIT = 500;

export function sanitizeQueue(value, limit = DEFAULT_QUEUE_LIMIT) {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item) => item && typeof item.id === 'string' && typeof item.code === 'string')
    .slice(-limit);
}

export function appendQueued(queue, scan, limit = DEFAULT_QUEUE_LIMIT) {
  return sanitizeQueue([...queue, scan], limit);
}

/** Удаляем только запись, которую ПК подтвердил по её уникальному id. */
export function removeAcknowledged(queue, acknowledgedId) {
  return queue.filter((item) => item.id !== acknowledgedId);
}
