import type { QueuedScan } from './types';

export const DEFAULT_QUEUE_LIMIT: number;
export function sanitizeQueue(value: unknown, limit?: number): QueuedScan[];
export function appendQueued(queue: QueuedScan[], scan: QueuedScan, limit?: number): QueuedScan[];
export function removeAcknowledged(queue: QueuedScan[], acknowledgedId: string): QueuedScan[];
