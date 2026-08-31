import type { QueuedScan } from './types';
import { sanitizeQueue } from './offline-queue.js';

/** Настройки, которые оператор меняет на самом телефоне. */
export interface AppSettings {
  deviceName: string;
  beep: boolean;
  vibrate: boolean;
  continuous: boolean;
  /** Пауза после успешного кода в непрерывном режиме, мс. */
  rescanDelayMs: number;
  /** Доля кадра по ширине, в которой ищется код: меньше — быстрее и точнее. */
  roiRatio: number;
  /** Интервал распознавания, мс. */
  decodeIntervalMs: number;
  torch: boolean;
  keepAwake: boolean;
  preferredCameraId: string | null;
  zoom: number | null;
}

export interface Session {
  deviceId: string;
  sessionToken: string;
  serverVersion: string;
  hostName: string;
}

const SETTINGS_KEY = 'netscan.settings';
const SESSION_KEY = 'netscan.session';
const QUEUE_KEY = 'netscan.queue';

export const DEFAULT_SETTINGS: AppSettings = {
  deviceName: defaultDeviceName(),
  beep: true,
  vibrate: true,
  continuous: true,
  rescanDelayMs: 900,
  roiRatio: 0.72,
  decodeIntervalMs: 90,
  torch: false,
  keepAwake: true,
  preferredCameraId: null,
  zoom: null,
};

function defaultDeviceName(): string {
  const ua = navigator.userAgent;
  if (/android/i.test(ua)) return 'Android-телефон';
  if (/iphone|ipad|ipod/i.test(ua)) return 'iPhone';
  return 'Телефон';
}

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? ({ ...fallback, ...(JSON.parse(raw) as object) } as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Приватный режим браузера может запрещать запись — работаем без сохранения.
  }
}

export function loadSettings(): AppSettings {
  return read<AppSettings>(SETTINGS_KEY, DEFAULT_SETTINGS);
}

export function saveSettings(settings: AppSettings): void {
  write(SETTINGS_KEY, settings);
}

export function loadSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session): void {
  write(SESSION_KEY, session);
}

export function clearSession(): void {
  try {
    localStorage.removeItem(SESSION_KEY);
  } catch {
    // ничего не делаем: сессия всё равно будет переспрошена
  }
}

/**
 * Очередь сканов переживает перезагрузку страницы и потерю связи: коды,
 * отсканированные в мёртвой зоне Wi-Fi, уйдут на ПК после восстановления связи.
 */
export function loadQueue(): QueuedScan[] {
  try {
    const raw = localStorage.getItem(QUEUE_KEY);
    return raw ? sanitizeQueue(JSON.parse(raw)) : [];
  } catch {
    return [];
  }
}

export function saveQueue(queue: QueuedScan[]): void {
  write(QUEUE_KEY, sanitizeQueue(queue));
}
