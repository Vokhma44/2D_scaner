import {
  BarcodeFormat,
  BinaryBitmap,
  DecodeHintType,
  HybridBinarizer,
  MultiFormatReader,
  RGBLuminanceSource,
} from '@zxing/library';

export interface DecodedCode {
  code: string;
  format: string;
}

export interface Decoder {
  readonly engine: 'native' | 'zxing';
  decode(canvas: HTMLCanvasElement): Promise<DecodedCode | null>;
}

/** Символики, которые обычно требуются вместо USB-сканера 2D. */
const NATIVE_FORMATS = [
  'qr_code',
  'data_matrix',
  'pdf417',
  'aztec',
  'code_128',
  'code_39',
  'code_93',
  'codabar',
  'ean_13',
  'ean_8',
  'itf',
  'upc_a',
  'upc_e',
];

const ZXING_FORMATS = [
  BarcodeFormat.QR_CODE,
  BarcodeFormat.DATA_MATRIX,
  BarcodeFormat.PDF_417,
  BarcodeFormat.AZTEC,
  BarcodeFormat.CODE_128,
  BarcodeFormat.CODE_39,
  BarcodeFormat.CODE_93,
  BarcodeFormat.CODABAR,
  BarcodeFormat.EAN_13,
  BarcodeFormat.EAN_8,
  BarcodeFormat.ITF,
  BarcodeFormat.UPC_A,
  BarcodeFormat.UPC_E,
];

interface DetectedBarcode {
  rawValue: string;
  format: string;
}

interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}

interface BarcodeDetectorConstructor {
  new (options?: { formats?: string[] }): BarcodeDetectorLike;
  getSupportedFormats(): Promise<string[]>;
}

/**
 * Встроенный в браузер детектор: аппаратно ускорен и заметно быстрее любой
 * библиотеки. Есть в Chrome на Android — то есть на большинстве телефонов,
 * которые ставят на склад вместо сканера.
 */
class NativeDecoder implements Decoder {
  readonly engine = 'native' as const;

  private constructor(private readonly detector: BarcodeDetectorLike) {}

  static async create(): Promise<NativeDecoder | null> {
    const ctor = (globalThis as { BarcodeDetector?: BarcodeDetectorConstructor }).BarcodeDetector;
    if (!ctor) return null;
    try {
      const supported = await ctor.getSupportedFormats();
      const formats = NATIVE_FORMATS.filter((format) => supported.includes(format));
      // Без поддержки QR и DataMatrix встроенный детектор бесполезен для 2D-сканера.
      if (!formats.includes('qr_code') && !formats.includes('data_matrix')) return null;
      return new NativeDecoder(new ctor({ formats }));
    } catch {
      return null;
    }
  }

  async decode(canvas: HTMLCanvasElement): Promise<DecodedCode | null> {
    const found = await this.detector.detect(canvas);
    const first = found.find((item) => item.rawValue.length > 0);
    return first ? { code: first.rawValue, format: first.format } : null;
  }
}

/**
 * Запасной декодер на чистом JavaScript: работает везде, включая Safari на iOS,
 * где встроенного детектора нет.
 */
class ZXingDecoder implements Decoder {
  readonly engine = 'zxing' as const;

  private readonly reader = new MultiFormatReader();

  constructor() {
    const hints = new Map<DecodeHintType, unknown>();
    hints.set(DecodeHintType.POSSIBLE_FORMATS, ZXING_FORMATS);
    this.reader.setHints(hints);
  }

  async decode(canvas: HTMLCanvasElement): Promise<DecodedCode | null> {
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return null;

    const image = context.getImageData(0, 0, canvas.width, canvas.height);
    const luminances = toLuminance(image);
    const source = new RGBLuminanceSource(luminances, image.width, image.height);
    const bitmap = new BinaryBitmap(new HybridBinarizer(source));

    try {
      const result = this.reader.decode(bitmap);
      const text = result.getText();
      return text ? { code: text, format: formatName(result.getBarcodeFormat()) } : null;
    } catch {
      // NotFoundException на кадре без кода — обычное дело, кадров десятки в секунду.
      return null;
    } finally {
      this.reader.reset();
    }
  }
}

/** ZXing ждёт массив яркостей, а канвас отдаёт RGBA — переводим сами. */
function toLuminance(image: ImageData): Uint8ClampedArray {
  const { data, width, height } = image;
  const result = new Uint8ClampedArray(width * height);
  for (let i = 0, p = 0; i < result.length; i++, p += 4) {
    // Тот же «зелёно-ориентированный» вес, что и в эталонной реализации ZXing.
    result[i] = (data[p] + 2 * data[p + 1] + data[p + 2]) / 4;
  }
  return result;
}

function formatName(format: BarcodeFormat): string {
  const name = BarcodeFormat[format];
  return typeof name === 'string' ? name.toLowerCase() : 'unknown';
}

/** Выбирает лучший доступный декодер для текущего браузера. */
export async function createDecoder(): Promise<Decoder> {
  return (await NativeDecoder.create()) ?? new ZXingDecoder();
}
