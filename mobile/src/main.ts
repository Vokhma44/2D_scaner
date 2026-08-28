import './styles.css';

import { Camera, ScreenLock, type CameraOption } from './camera';
import { AgentClient, describeDevice } from './client';
import { createDecoder, type Decoder } from './decoder';
import { Feedback } from './feedback';
import {
  DEFAULT_SETTINGS,
  loadSettings,
  saveSettings,
  type AppSettings,
} from './storage';
import type { AckMessage, ConnectionStatus, QueuedScan, ScanSource } from './types';

const HISTORY_LIMIT = 100;

const STATUS_LABEL: Record<ConnectionStatus, string> = {
  online: 'на связи',
  connecting: 'подключение…',
  offline: 'нет связи с ПК',
  unauthorized: 'нужно новое сопряжение',
};

/**
 * Мобильный клиент: камера телефона распознаёт код и отправляет его агенту на
 * ПК, который вводит код в активное окно так же, как это делал бы USB-сканер.
 */
class App {
  private settings: AppSettings = loadSettings();

  private readonly feedback = new Feedback();

  private readonly screenLock = new ScreenLock();

  private readonly client = new AgentClient(
    {
      onStatus: (status, detail) => this.onStatus(status, detail),
      onQueue: () => this.renderQueueChip(),
      onAck: (ack) => this.onAck(ack),
      onNotice: (level, text) => this.toast(text, level === 'info' ? 'ok' : 'error'),
      onLatency: (ms) => {
        this.latencyMs = ms;
        this.renderStatusChip();
      },
    },
    () => this.settings.deviceName,
  );

  private camera: Camera | null = null;

  private decoder: Decoder | null = null;

  private cameras: CameraOption[] = [];

  private history: QueuedScan[] = [];

  private status: ConnectionStatus = 'offline';

  private statusDetail = '';

  private latencyMs: number | null = null;

  private scanning = false;

  private paused = false;

  private lastCode = '';

  private lastCodeAt = 0;

  private toastTimer: number | null = null;

  private readonly root = document.getElementById('app') as HTMLDivElement;

  async start(): Promise<void> {
    registerServiceWorker();
    document.addEventListener('pointerdown', () => this.feedback.unlock(), { once: true });
    document.addEventListener('visibilitychange', () => this.onVisibilityChange());

    const pairingCode = new URLSearchParams(location.search).get('p');
    if (pairingCode && !this.client.paired) {
      this.renderPairScreen(pairingCode.toUpperCase(), 'Подключаюсь к ПК…');
      await this.tryPair(pairingCode);
      return;
    }

    if (this.client.paired) await this.enterScanner();
    else this.renderPairScreen('');
  }

  // ------------------------------------------------------------ сопряжение

  private renderPairScreen(code: string, message = ''): void {
    this.root.innerHTML = `
      <div class="pair">
        <h1>netscan</h1>
        <p>Телефон станет 2D-сканером для этого ПК: отсканированный код появится
           в том окне, которое сейчас активно на компьютере.</p>
        <ol class="steps">
          <li>Запустите агент netscan на ПК.</li>
          <li>Наведите камеру на QR-код в окне агента — код подставится сам.</li>
          <li>Или введите код сопряжения вручную.</li>
        </ol>
        <label>Код сопряжения
          <input id="pair-code" class="code-input" value="${escapeHtml(code)}" placeholder="XXXX-XXXX-XXXX"
                 autocapitalize="characters" autocomplete="off" spellcheck="false">
        </label>
        <label>Имя устройства (видно на ПК)
          <input id="pair-name" value="${escapeHtml(this.settings.deviceName)}" autocomplete="off">
        </label>
        <div class="error" id="pair-error">${escapeHtml(message)}</div>
        <button class="primary" id="pair-submit">Подключить</button>
      </div>`;

    const submit = () => {
      const value = (document.getElementById('pair-code') as HTMLInputElement).value.trim();
      const name = (document.getElementById('pair-name') as HTMLInputElement).value.trim();
      if (name) this.updateSettings({ deviceName: name });
      if (!value) {
        this.setPairError('Введите код сопряжения с экрана ПК');
        return;
      }
      void this.tryPair(value);
    };

    document.getElementById('pair-submit')?.addEventListener('click', submit);
    document.getElementById('pair-code')?.addEventListener('keydown', (event) => {
      if ((event as KeyboardEvent).key === 'Enter') submit();
    });
  }

  private setPairError(text: string): void {
    const target = document.getElementById('pair-error');
    if (target) target.textContent = text;
  }

  private async tryPair(code: string): Promise<void> {
    try {
      const response = await this.client.pair(code, describeDevice(this.settings.deviceName));
      // Код сопряжения не должен остаться в адресной строке и в истории браузера.
      history.replaceState(null, '', location.pathname);

      if (response.status === 'pending') {
        this.setPairError('Устройство добавлено, подтвердите его в консоли на ПК');
      }
      await this.enterScanner();
    } catch (error) {
      this.renderPairScreen(code, error instanceof Error ? error.message : 'Не удалось подключиться');
    }
  }

  // ------------------------------------------------------------ экран сканера

  private async enterScanner(): Promise<void> {
    this.renderScanScreen();
    this.client.connect();

    const hint = Camera.secureContextHint();
    if (hint) {
      this.showHint(hint);
      return;
    }

    const video = document.getElementById('video') as HTMLVideoElement;
    this.camera = new Camera(video);
    this.decoder = await createDecoder();

    try {
      await this.camera.start(this.settings.preferredCameraId);
    } catch (error) {
      this.showHint(cameraErrorText(error));
      return;
    }

    this.cameras = await Camera.listCameras();
    if (this.settings.torch) await this.camera.setTorch(true);
    if (this.settings.zoom !== null) await this.camera.setZoom(this.settings.zoom);
    if (this.settings.keepAwake) await this.screenLock.acquire();

    this.showHint('');
    this.layoutViewfinder();
    window.addEventListener('resize', () => this.layoutViewfinder());
    this.startLoop();
  }

  private renderScanScreen(): void {
    this.root.innerHTML = `
      <div class="scan">
        <video id="video" playsinline muted></video>
        <canvas id="frame"></canvas>
        <div class="viewfinder" id="viewfinder"></div>
        <div class="hud">
          <span class="chip" id="status"><i class="dot"></i><span>подключение…</span></span>
          <span class="chip queue" id="queue" hidden></span>
        </div>
        <div class="hint" id="hint"></div>
        <div class="result" id="result"><b id="result-code"></b><span id="result-meta"></span></div>
        <button class="shutter" id="shutter" hidden aria-label="Сканировать"></button>
        <div class="controls">
          <button id="btn-torch"><span class="glyph">☀</span>фонарик</button>
          <button id="btn-camera"><span class="glyph">⟳</span>камера</button>
          <button id="btn-mode"><span class="glyph">∞</span>режим</button>
          <button id="btn-manual"><span class="glyph">⌨</span>ввод</button>
          <button id="btn-history"><span class="glyph">≡</span>журнал</button>
          <button id="btn-settings"><span class="glyph">⚙</span>настройки</button>
        </div>
      </div>
      <div class="sheet" id="sheet"><div class="panel" id="sheet-panel"></div></div>
      <div class="toast" id="toast"></div>`;

    document.getElementById('btn-torch')?.addEventListener('click', () => void this.toggleTorch());
    document.getElementById('btn-camera')?.addEventListener('click', () => void this.switchCamera());
    document.getElementById('btn-mode')?.addEventListener('click', () => this.toggleMode());
    document.getElementById('btn-manual')?.addEventListener('click', () => this.openManualSheet());
    document.getElementById('btn-history')?.addEventListener('click', () => this.openHistorySheet());
    document.getElementById('btn-settings')?.addEventListener('click', () => this.openSettingsSheet());
    document.getElementById('shutter')?.addEventListener('click', () => this.resume());
    document.getElementById('sheet')?.addEventListener('click', (event) => {
      if ((event.target as HTMLElement).id === 'sheet') this.closeSheet();
    });

    this.renderStatusChip();
    this.renderModeButton();
  }

  private showHint(text: string): void {
    const hint = document.getElementById('hint');
    if (hint) hint.textContent = text;
  }

  /** Рамка на экране показывает ровно ту область кадра, которая уходит в декодер. */
  private layoutViewfinder(): void {
    const video = document.getElementById('video') as HTMLVideoElement | null;
    const box = document.getElementById('viewfinder') as HTMLDivElement | null;
    if (!video || !box || !video.videoWidth) return;

    const roi = this.computeRoi(video);
    const scale = Math.max(video.clientWidth / video.videoWidth, video.clientHeight / video.videoHeight);
    box.style.width = `${Math.round(roi.width * scale)}px`;
    box.style.height = `${Math.round(roi.height * scale)}px`;
  }

  private computeRoi(video: HTMLVideoElement): { x: number; y: number; width: number; height: number } {
    const vw = video.videoWidth;
    const vh = video.videoHeight;
    const scale = Math.max(video.clientWidth / vw, video.clientHeight / vh) || 1;
    // Учитываем object-fit: cover — часть кадра не видна на экране, и искать код
    // в невидимой области нельзя: оператор не понял бы, куда наводить телефон.
    const visibleW = Math.min(vw, video.clientWidth / scale);
    const visibleH = Math.min(vh, video.clientHeight / scale);
    const side = Math.min(visibleW, visibleH) * this.settings.roiRatio;
    return { x: (vw - side) / 2, y: (vh - side) / 2, width: side, height: side };
  }

  // ------------------------------------------------------------ цикл распознавания

  private startLoop(): void {
    if (this.scanning) return;
    this.scanning = true;
    void this.tick();
  }

  private async tick(): Promise<void> {
    if (!this.scanning) return;

    const video = document.getElementById('video') as HTMLVideoElement | null;
    const canvas = document.getElementById('frame') as HTMLCanvasElement | null;

    if (video && canvas && this.decoder && !this.paused && video.readyState >= 2 && video.videoWidth > 0) {
      const roi = this.computeRoi(video);
      const target = Math.min(720, Math.round(roi.width));
      canvas.width = target;
      canvas.height = target;
      const context = canvas.getContext('2d', { willReadFrequently: true });
      if (context) {
        context.drawImage(video, roi.x, roi.y, roi.width, roi.height, 0, 0, target, target);
        try {
          const found = await this.decoder.decode(canvas);
          if (found) this.onCode(found.code, found.format, 'camera');
        } catch {
          // Сбой распознавания одного кадра не должен останавливать сканер.
        }
      }
    }

    window.setTimeout(() => void this.tick(), this.settings.decodeIntervalMs);
  }

  private onCode(code: string, format: string, source: ScanSource): void {
    const now = Date.now();
    // Локальное подавление повторов: камера видит один код десятки раз в секунду.
    if (source === 'camera' && code === this.lastCode && now - this.lastCodeAt < this.settings.rescanDelayMs) return;

    this.lastCode = code;
    this.lastCodeAt = now;

    const scan: QueuedScan = {
      id: newId(),
      code,
      format,
      scannedAt: now,
      source,
      status: 'pending',
    };

    this.history.unshift(scan);
    this.history = this.history.slice(0, HISTORY_LIMIT);
    this.client.enqueue(scan);

    if (this.settings.beep) this.feedback.beep('ok');
    if (this.settings.vibrate) this.feedback.vibrate('ok');
    this.flashViewfinder();
    this.showResult(scan);

    if (!this.settings.continuous) this.pause();
  }

  private pause(): void {
    this.paused = true;
    const shutter = document.getElementById('shutter') as HTMLButtonElement | null;
    if (shutter) shutter.hidden = false;
  }

  private resume(): void {
    this.paused = false;
    this.lastCode = '';
    const shutter = document.getElementById('shutter') as HTMLButtonElement | null;
    if (shutter) shutter.hidden = true;
  }

  private flashViewfinder(): void {
    const box = document.getElementById('viewfinder');
    if (!box) return;
    box.classList.add('hit');
    window.setTimeout(() => box.classList.remove('hit'), 220);
  }

  private showResult(scan: QueuedScan): void {
    const panel = document.getElementById('result');
    const code = document.getElementById('result-code');
    const meta = document.getElementById('result-meta');
    if (!panel || !code || !meta) return;

    code.textContent = scan.code;
    meta.textContent = `${scan.format} · ${statusText(scan)}`;
    panel.className = `result show ${resultClass(scan)}`;
  }

  private onAck(ack: AckMessage): void {
    const entry = this.history.find((item) => item.id === ack.id);
    if (entry) {
      entry.status = ack.status;
      entry.detail = ack.detail;
      const code = document.getElementById('result-code');
      if (code?.textContent === entry.code) this.showResult(entry);
    }
    if (ack.status === 'failed') {
      if (this.settings.beep) this.feedback.beep('error');
      if (this.settings.vibrate) this.feedback.vibrate('error');
      this.toast(ack.detail ?? 'ПК не смог ввести код', 'error');
    }
    this.renderQueueChip();
  }

  // ------------------------------------------------------------ элементы управления

  private async toggleTorch(): Promise<void> {
    const next = !this.settings.torch;
    const applied = (await this.camera?.setTorch(next)) ?? false;
    if (!applied) {
      this.toast('Эта камера не умеет включать фонарик', 'error');
      return;
    }
    this.updateSettings({ torch: next });
    document.getElementById('btn-torch')?.classList.toggle('on', next);
  }

  private async switchCamera(): Promise<void> {
    if (this.cameras.length < 2) {
      this.toast('Другой камеры не найдено', 'error');
      return;
    }
    const current = this.camera?.currentDeviceId() ?? this.settings.preferredCameraId;
    const index = this.cameras.findIndex((item) => item.deviceId === current);
    const next = this.cameras[(index + 1) % this.cameras.length];
    await this.useCamera(next.deviceId);
  }

  private async useCamera(deviceId: string): Promise<void> {
    try {
      await this.camera?.start(deviceId);
      this.updateSettings({ preferredCameraId: deviceId });
      if (this.settings.torch) await this.camera?.setTorch(true);
      this.layoutViewfinder();
    } catch (error) {
      this.toast(cameraErrorText(error), 'error');
    }
  }

  private toggleMode(): void {
    this.updateSettings({ continuous: !this.settings.continuous });
    this.resume();
    this.renderModeButton();
  }

  private renderModeButton(): void {
    const button = document.getElementById('btn-mode');
    if (!button) return;
    const continuous = this.settings.continuous;
    button.innerHTML = `<span class="glyph">${continuous ? '∞' : '1'}</span>${continuous ? 'поток' : 'по одному'}`;
    button.classList.toggle('on', continuous);
  }

  // ------------------------------------------------------------ шторки

  private openSheet(html: string): void {
    const sheet = document.getElementById('sheet');
    const panel = document.getElementById('sheet-panel');
    if (!sheet || !panel) return;
    panel.innerHTML = html;
    sheet.classList.add('open');
  }

  private closeSheet(): void {
    document.getElementById('sheet')?.classList.remove('open');
  }

  private openManualSheet(): void {
    this.openSheet(`
      <h2>Ввод кода вручную</h2>
      <p class="muted">Код уйдёт на ПК так же, как отсканированный камерой.</p>
      <input id="manual-code" placeholder="Например, 0104607012345678" autocomplete="off">
      <div class="row">
        <button class="primary" id="manual-send" style="flex:1">Отправить</button>
        <button class="ghost" id="manual-close">Закрыть</button>
      </div>`);

    const input = document.getElementById('manual-code') as HTMLInputElement;
    input.focus();
    const send = () => {
      const value = input.value.trim();
      if (!value) return;
      this.onCode(value, 'manual', 'manual');
      input.value = '';
      this.closeSheet();
    };
    document.getElementById('manual-send')?.addEventListener('click', send);
    document.getElementById('manual-close')?.addEventListener('click', () => this.closeSheet());
    input.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') send();
    });
  }

  private openHistorySheet(): void {
    const items = this.history.length === 0
      ? '<p class="muted">Пока ничего не отсканировано</p>'
      : this.history
          .map(
            (scan) => `
              <div class="item ${scan.status}">
                <b>${escapeHtml(scan.code)}</b>
                <span>${new Date(scan.scannedAt).toLocaleTimeString('ru-RU')} · ${escapeHtml(scan.format)} · ${statusText(scan)}</span>
              </div>`,
          )
          .join('');

    this.openSheet(`
      <div class="row between"><h2>Журнал сканов</h2><button class="ghost" id="history-close">Закрыть</button></div>
      <div class="history">${items}</div>`);
    document.getElementById('history-close')?.addEventListener('click', () => this.closeSheet());
  }

  private openSettingsSheet(): void {
    const capabilities = this.camera?.capabilities();
    const zoom = capabilities?.zoom;

    this.openSheet(`
      <div class="row between"><h2>Настройки</h2><button class="ghost" id="settings-close">Закрыть</button></div>

      <label>Имя устройства
        <input id="set-name" value="${escapeHtml(this.settings.deviceName)}">
      </label>

      <label class="switch"><span>Звук при считывании</span>
        <input type="checkbox" id="set-beep" ${this.settings.beep ? 'checked' : ''}></label>
      <label class="switch"><span>Вибрация</span>
        <input type="checkbox" id="set-vibrate" ${this.settings.vibrate ? 'checked' : ''}></label>
      <label class="switch"><span>Не гасить экран</span>
        <input type="checkbox" id="set-awake" ${this.settings.keepAwake ? 'checked' : ''}></label>

      <div class="grid2">
        <label>Пауза после кода, мс
          <input type="number" id="set-rescan" min="0" max="10000" step="100" value="${this.settings.rescanDelayMs}">
        </label>
        <label>Интервал кадров, мс
          <input type="number" id="set-interval" min="30" max="1000" step="10" value="${this.settings.decodeIntervalMs}">
        </label>
      </div>

      <label>Размер области поиска: <span id="roi-value">${Math.round(this.settings.roiRatio * 100)}%</span>
        <input type="range" id="set-roi" min="0.3" max="1" step="0.02" value="${this.settings.roiRatio}">
      </label>

      ${this.cameras.length > 1 ? `
      <label>Камера
        <select id="set-camera">
          ${this.cameras
            .map(
              (item) => `<option value="${escapeHtml(item.deviceId)}"${
                item.deviceId === this.camera?.currentDeviceId() ? ' selected' : ''
              }>${escapeHtml(item.label)}</option>`,
            )
            .join('')}
        </select>
      </label>` : ''}

      ${zoom ? `
      <label>Приближение
        <input type="range" id="set-zoom" min="${zoom.min}" max="${zoom.max}" step="${zoom.step}"
               value="${this.settings.zoom ?? zoom.min}">
      </label>` : ''}

      <p class="muted">
        ПК: ${escapeHtml(this.client.hostName)} · связь: ${STATUS_LABEL[this.status]}${
          this.latencyMs !== null ? ` · задержка ${this.latencyMs} мс` : ''
        }<br>
        Распознавание: ${this.decoder?.engine === 'native' ? 'встроенное в браузер' : 'библиотека ZXing'} ·
        в очереди: ${this.client.pending.length}
      </p>

      <button id="set-forget">Отключиться от ПК</button>`);

    document.getElementById('settings-close')?.addEventListener('click', () => this.closeSheet());

    const bindCheckbox = (id: string, key: 'beep' | 'vibrate' | 'keepAwake') => {
      document.getElementById(id)?.addEventListener('change', (event) => {
        const checked = (event.target as HTMLInputElement).checked;
        this.updateSettings({ [key]: checked } as Partial<AppSettings>);
        if (key === 'keepAwake') void (checked ? this.screenLock.acquire() : this.screenLock.release());
      });
    };
    bindCheckbox('set-beep', 'beep');
    bindCheckbox('set-vibrate', 'vibrate');
    bindCheckbox('set-awake', 'keepAwake');

    document.getElementById('set-name')?.addEventListener('change', (event) => {
      this.updateSettings({ deviceName: (event.target as HTMLInputElement).value.trim() || DEFAULT_SETTINGS.deviceName });
    });
    document.getElementById('set-rescan')?.addEventListener('change', (event) => {
      this.updateSettings({ rescanDelayMs: clampNumber(event, 0, 10_000, DEFAULT_SETTINGS.rescanDelayMs) });
    });
    document.getElementById('set-interval')?.addEventListener('change', (event) => {
      this.updateSettings({ decodeIntervalMs: clampNumber(event, 30, 1000, DEFAULT_SETTINGS.decodeIntervalMs) });
    });
    document.getElementById('set-roi')?.addEventListener('input', (event) => {
      const ratio = Number((event.target as HTMLInputElement).value);
      this.updateSettings({ roiRatio: ratio });
      const label = document.getElementById('roi-value');
      if (label) label.textContent = `${Math.round(ratio * 100)}%`;
      this.layoutViewfinder();
    });
    document.getElementById('set-camera')?.addEventListener('change', (event) => {
      void this.useCamera((event.target as HTMLSelectElement).value);
    });
    document.getElementById('set-zoom')?.addEventListener('input', (event) => {
      const value = Number((event.target as HTMLInputElement).value);
      this.updateSettings({ zoom: value });
      void this.camera?.setZoom(value);
    });
    document.getElementById('set-forget')?.addEventListener('click', () => {
      this.client.forget();
      this.scanning = false;
      this.camera?.stop();
      void this.screenLock.release();
      this.closeSheet();
      this.renderPairScreen('');
    });
  }

  // ------------------------------------------------------------ статус и утилиты

  private onStatus(status: ConnectionStatus, detail?: string): void {
    this.status = status;
    this.statusDetail = detail ?? '';
    this.renderStatusChip();
    this.renderQueueChip();

    if (status === 'unauthorized') {
      this.scanning = false;
      this.camera?.stop();
      this.renderPairScreen('', this.statusDetail || 'ПК больше не принимает это устройство');
    }
  }

  private renderStatusChip(): void {
    const chip = document.getElementById('status');
    if (!chip) return;
    const latency = this.status === 'online' && this.latencyMs !== null ? ` · ${this.latencyMs} мс` : '';
    chip.className = `chip ${this.status}`;
    chip.innerHTML = `<i class="dot"></i><span>${escapeHtml(this.client.hostName)} · ${STATUS_LABEL[this.status]}${latency}</span>`;
  }

  private renderQueueChip(): void {
    const chip = document.getElementById('queue') as HTMLElement | null;
    if (!chip) return;
    const pending = this.client.pending.length;
    chip.hidden = pending === 0;
    chip.textContent = `в очереди: ${pending}`;
  }

  private onVisibilityChange(): void {
    if (document.hidden) return;
    // После возврата в приложение блокировка экрана снимается системой,
    // а сокет мог закрыться в фоне — восстанавливаем и то, и другое.
    if (this.settings.keepAwake && !this.screenLock.held) void this.screenLock.acquire();
    this.client.connect();
  }

  private updateSettings(patch: Partial<AppSettings>): void {
    this.settings = { ...this.settings, ...patch };
    saveSettings(this.settings);
  }

  private toast(text: string, kind: 'ok' | 'error' = 'ok'): void {
    const toast = document.getElementById('toast');
    if (!toast) return;
    toast.textContent = text;
    toast.style.borderColor = kind === 'error' ? 'var(--err)' : 'var(--line)';
    toast.classList.add('show');
    if (this.toastTimer !== null) window.clearTimeout(this.toastTimer);
    this.toastTimer = window.setTimeout(() => toast.classList.remove('show'), 2600);
  }
}

function statusText(scan: QueuedScan): string {
  switch (scan.status) {
    case 'pending':
      return 'ожидает отправки';
    case 'accepted':
      return 'введён на ПК';
    case 'duplicate':
      return 'повтор, пропущен';
    case 'filtered':
      return `отфильтрован${scan.detail ? `: ${scan.detail}` : ''}`;
    case 'failed':
      return `ошибка${scan.detail ? `: ${scan.detail}` : ''}`;
  }
}

function resultClass(scan: QueuedScan): string {
  if (scan.status === 'accepted') return 'ok';
  if (scan.status === 'failed' || scan.status === 'pending') return 'err';
  return 'warn';
}

function cameraErrorText(error: unknown): string {
  const name = error instanceof DOMException ? error.name : '';
  if (name === 'NotAllowedError') return 'Доступ к камере запрещён. Разрешите его в настройках браузера и обновите страницу.';
  if (name === 'NotFoundError') return 'Камера не найдена.';
  if (name === 'NotReadableError') return 'Камера занята другим приложением.';
  return `Не удалось включить камеру: ${error instanceof Error ? error.message : 'неизвестная ошибка'}`;
}

function clampNumber(event: Event, min: number, max: number, fallback: number): number {
  const value = Number((event.target as HTMLInputElement).value);
  if (!Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, value));
}

function newId(): string {
  if (crypto.randomUUID) return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] ?? char);
}

function registerServiceWorker(): void {
  if (!('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    void navigator.serviceWorker.register('./sw.js').catch(() => undefined);
  });
}

void new App().start();
