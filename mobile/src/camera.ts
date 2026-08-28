export interface CameraCapabilities {
  torch: boolean;
  zoom: { min: number; max: number; step: number } | null;
}

export interface CameraOption {
  deviceId: string;
  label: string;
}

/**
 * Управление камерой телефона: запуск потока, фонарик, зум и выбор объектива.
 *
 * Камера в браузере доступна только в защищённом контексте, поэтому агент и
 * отдаёт страницу по HTTPS — по обычному HTTP getUserMedia просто не вызовется.
 */
export class Camera {
  private stream: MediaStream | null = null;

  private track: MediaStreamTrack | null = null;

  constructor(private readonly video: HTMLVideoElement) {}

  get active(): boolean {
    return this.stream !== null;
  }

  static secureContextHint(): string | null {
    if (window.isSecureContext) return null;
    return 'Браузер запретил камеру: страница открыта не по HTTPS. Откройте адрес с QR-кода на ПК.';
  }

  async start(deviceId: string | null): Promise<void> {
    this.stop();

    const constraints: MediaStreamConstraints = {
      audio: false,
      video: deviceId
        ? { deviceId: { exact: deviceId }, width: { ideal: 1920 }, height: { ideal: 1080 } }
        : {
            facingMode: { ideal: 'environment' },
            width: { ideal: 1920 },
            height: { ideal: 1080 },
          },
    };

    this.stream = await navigator.mediaDevices.getUserMedia(constraints);
    this.track = this.stream.getVideoTracks()[0] ?? null;
    this.video.srcObject = this.stream;
    this.video.setAttribute('playsinline', 'true');
    this.video.muted = true;
    await this.video.play();
  }

  stop(): void {
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
    this.track = null;
    this.video.srcObject = null;
  }

  capabilities(): CameraCapabilities {
    const raw = this.track?.getCapabilities?.() as
      | (MediaTrackCapabilities & { torch?: boolean; zoom?: { min: number; max: number; step?: number } })
      | undefined;
    return {
      torch: Boolean(raw?.torch),
      zoom: raw?.zoom ? { min: raw.zoom.min, max: raw.zoom.max, step: raw.zoom.step ?? 0.1 } : null,
    };
  }

  async setTorch(on: boolean): Promise<boolean> {
    if (!this.track || !this.capabilities().torch) return false;
    try {
      await this.track.applyConstraints({ advanced: [{ torch: on } as MediaTrackConstraintSet] });
      return true;
    } catch {
      return false;
    }
  }

  async setZoom(value: number): Promise<void> {
    if (!this.track || !this.capabilities().zoom) return;
    try {
      await this.track.applyConstraints({ advanced: [{ zoom: value } as MediaTrackConstraintSet] });
    } catch {
      // Некоторые прошивки объявляют зум, но отказывают в его применении.
    }
  }

  currentDeviceId(): string | null {
    return this.track?.getSettings().deviceId ?? null;
  }

  /**
   * Список камер доступен только после выдачи разрешения: до этого браузер
   * прячет и метки, и идентификаторы устройств.
   */
  static async listCameras(): Promise<CameraOption[]> {
    if (!navigator.mediaDevices?.enumerateDevices) return [];
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices
      .filter((device) => device.kind === 'videoinput')
      .map((device, index) => ({
        deviceId: device.deviceId,
        label: device.label || `Камера ${index + 1}`,
      }));
  }
}

/**
 * Не даёт экрану гаснуть во время работы: сканер, который надо всё время
 * будить, бесполезен на приёмке товара.
 */
export class ScreenLock {
  private sentinel: WakeLockSentinel | null = null;

  async acquire(): Promise<void> {
    try {
      this.sentinel = (await navigator.wakeLock?.request('screen')) ?? null;
    } catch {
      this.sentinel = null;
    }
  }

  async release(): Promise<void> {
    try {
      await this.sentinel?.release();
    } catch {
      // блокировка могла быть снята системой при сворачивании приложения
    }
    this.sentinel = null;
  }

  get held(): boolean {
    return this.sentinel !== null && !this.sentinel.released;
  }
}
