/**
 * Обратная связь при удачном коде. У настоящего сканера есть писк и подсветка —
 * без них оператор не понимает, считался код или нет, и сканирует его дважды.
 */
export class Feedback {
  private context: AudioContext | null = null;

  /**
   * Мобильные браузеры разрешают звук только после касания экрана, поэтому
   * контекст создаётся при первом жесте пользователя, а не при загрузке.
   */
  unlock(): void {
    if (this.context) {
      void this.context.resume();
      return;
    }
    const Ctor = window.AudioContext ?? (window as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctor) return;
    try {
      this.context = new Ctor();
      void this.context.resume();
    } catch {
      this.context = null;
    }
  }

  beep(kind: 'ok' | 'error' = 'ok'): void {
    const context = this.context;
    if (!context) return;
    try {
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = 'square';
      oscillator.frequency.value = kind === 'ok' ? 2000 : 420;
      gain.gain.setValueAtTime(0.0001, context.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.25, context.currentTime + 0.01);
      gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + (kind === 'ok' ? 0.09 : 0.25));
      oscillator.connect(gain).connect(context.destination);
      oscillator.start();
      oscillator.stop(context.currentTime + (kind === 'ok' ? 0.1 : 0.26));
    } catch {
      // звук — не критичная функция, молча пропускаем
    }
  }

  vibrate(kind: 'ok' | 'error' = 'ok'): void {
    try {
      navigator.vibrate?.(kind === 'ok' ? 35 : [60, 60, 60]);
    } catch {
      // вибрации может не быть на планшете
    }
  }
}
