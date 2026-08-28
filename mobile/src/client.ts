import type {
  AckMessage,
  ClientMessage,
  ConnectionStatus,
  DeviceInfo,
  PairResponse,
  QueuedScan,
  ServerMessage,
} from './types';
import { PROTOCOL_VERSION } from './types';
import { clearSession, loadQueue, loadSession, saveQueue, saveSession, type Session } from './storage';

export interface ClientEvents {
  onStatus(status: ConnectionStatus, detail?: string): void;
  onQueue(queue: QueuedScan[]): void;
  onAck(ack: AckMessage): void;
  onNotice(level: 'info' | 'warn' | 'error', text: string): void;
  onLatency(ms: number): void;
}

const RECONNECT_MIN_MS = 500;
const RECONNECT_MAX_MS = 5000;
const PING_INTERVAL_MS = 10_000;

/**
 * Транспорт до агента на ПК.
 *
 * Главная гарантия: скан не теряется и не вводится дважды. Он лежит в очереди
 * телефона до тех пор, пока ПК не подтвердит его по идентификатору, а сам
 * идентификатор делает повторную отправку безопасной.
 */
export class AgentClient {
  private socket: WebSocket | null = null;

  private queue: QueuedScan[] = loadQueue();

  private session: Session | null = loadSession();

  private reconnectDelay = RECONNECT_MIN_MS;

  private reconnectTimer: number | null = null;

  private pingTimer: number | null = null;

  private closedByUser = false;

  constructor(
    private readonly events: ClientEvents,
    private readonly deviceName: () => string,
  ) {}

  get paired(): boolean {
    return this.session !== null;
  }

  get hostName(): string {
    return this.session?.hostName ?? location.hostname;
  }

  get pending(): QueuedScan[] {
    return this.queue;
  }

  /** Сопряжение по коду из QR на экране ПК. */
  async pair(token: string, device: DeviceInfo): Promise<PairResponse> {
    const response = await fetch('/api/pair', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ token, device }),
    });

    const body = (await response.json()) as PairResponse & { message?: string };
    if (!response.ok) throw new Error(body.message ?? 'Не удалось подключиться к ПК');

    this.session = {
      deviceId: body.deviceId,
      sessionToken: body.sessionToken,
      serverVersion: body.serverVersion,
      hostName: body.settings.hostName,
    };
    saveSession(this.session);
    return body;
  }

  forget(): void {
    this.closedByUser = true;
    this.socket?.close();
    this.socket = null;
    this.session = null;
    clearSession();
    this.events.onStatus('offline');
  }

  connect(): void {
    if (!this.session) return;
    this.closedByUser = false;
    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return;
    }

    this.events.onStatus('connecting');
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const socket = new WebSocket(`${scheme}://${location.host}/api/ws?s=${encodeURIComponent(this.session.sessionToken)}`);
    this.socket = socket;

    socket.onopen = () => {
      this.reconnectDelay = RECONNECT_MIN_MS;
      this.events.onStatus('online');
      this.send({
        type: 'hello',
        protocolVersion: PROTOCOL_VERSION,
        device: describeDevice(this.deviceName()),
      });
      this.flush();
      this.startPing();
    };

    socket.onmessage = (event) => this.handleMessage(event.data as string);

    socket.onclose = (event) => {
      this.stopPing();
      this.socket = null;
      if (this.closedByUser) return;
      // Код 1008 (VIOLATED_POLICY) агент шлёт на отозванную или неизвестную сессию:
      // переподключение бессмысленно, нужно новое сопряжение.
      if (event.code === 1008) {
        clearSession();
        this.session = null;
        this.events.onStatus('unauthorized', event.reason);
        return;
      }
      this.events.onStatus('offline', event.reason);
      this.scheduleReconnect();
    };

    socket.onerror = () => {
      // Реальную причину даст onclose; здесь только гасим необработанную ошибку.
    };
  }

  private scheduleReconnect(): void {
    if (this.reconnectTimer !== null || this.closedByUser || !this.session) return;
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null;
      this.connect();
    }, this.reconnectDelay);
    this.reconnectDelay = Math.min(this.reconnectDelay * 2, RECONNECT_MAX_MS);
  }

  private startPing(): void {
    this.stopPing();
    this.pingTimer = window.setInterval(() => this.send({ type: 'ping', ts: Date.now() }), PING_INTERVAL_MS);
  }

  private stopPing(): void {
    if (this.pingTimer !== null) window.clearInterval(this.pingTimer);
    this.pingTimer = null;
  }

  private handleMessage(raw: string): void {
    let message: ServerMessage;
    try {
      message = JSON.parse(raw) as ServerMessage;
    } catch {
      return;
    }

    switch (message.type) {
      case 'welcome':
        if (this.session) {
          this.session = { ...this.session, serverVersion: message.serverVersion, hostName: message.settings.hostName };
          saveSession(this.session);
        }
        break;

      case 'ack': {
        const entry = this.queue.find((item) => item.id === message.id);
        if (entry) {
          entry.status = message.status;
          entry.detail = message.detail;
        }
        // Подтверждённые сканы уходят из очереди; в журнале их держит интерфейс.
        this.queue = this.queue.filter((item) => item.status === 'pending');
        saveQueue(this.queue);
        this.events.onQueue(this.queue);
        this.events.onAck(message);
        break;
      }

      case 'pong':
        this.events.onLatency(Date.now() - message.ts);
        break;

      case 'notice':
        this.events.onNotice(message.level, message.text);
        break;

      case 'settings':
        break;
    }
  }

  /** Ставит скан в очередь и пытается отправить сразу. */
  enqueue(scan: QueuedScan): void {
    this.queue.push(scan);
    saveQueue(this.queue);
    this.events.onQueue(this.queue);
    this.flush();
  }

  private flush(): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    for (const scan of this.queue) {
      if (scan.status !== 'pending') continue;
      this.send({
        type: 'scan',
        id: scan.id,
        code: scan.code,
        format: scan.format,
        scannedAt: scan.scannedAt,
        source: scan.source,
      });
    }
  }

  private send(message: ClientMessage): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    this.socket.send(JSON.stringify(message));
  }
}

export function describeDevice(name: string): DeviceInfo {
  return {
    name: name || 'Телефон',
    platform: navigator.platform || guessPlatform(),
    userAgent: navigator.userAgent.slice(0, 200),
    appVersion: '1.1.0',
  };
}

function guessPlatform(): string {
  const ua = navigator.userAgent;
  if (/android/i.test(ua)) return 'Android';
  if (/iphone|ipad|ipod/i.test(ua)) return 'iOS';
  return 'unknown';
}
