/** Типы обмена с агентом на ПК. Соответствуют ru.ruznak.netscan.protocol. */

export const PROTOCOL_VERSION = 1;

export type ScanSource = 'camera' | 'manual' | 'image';

export interface ScanMessage {
  type: 'scan';
  id: string;
  code: string;
  format: string;
  scannedAt: number;
  source: ScanSource;
}

export interface HelloMessage {
  type: 'hello';
  device: DeviceInfo;
  protocolVersion: number;
}

export interface PingMessage {
  type: 'ping';
  ts: number;
}

export type ClientMessage = ScanMessage | HelloMessage | PingMessage;

export interface DeviceInfo {
  name: string;
  platform: string;
  userAgent?: string;
  appVersion?: string;
}

export interface ClientSettings {
  duplicateWindowMs: number;
  allowedFormats: string[];
  hostName: string;
}

export type AckStatus = 'accepted' | 'duplicate' | 'filtered' | 'failed';

export interface WelcomeMessage {
  type: 'welcome';
  deviceId: string;
  serverVersion: string;
  protocolVersion: number;
  settings: ClientSettings;
}

export interface AckMessage {
  type: 'ack';
  id: string;
  status: AckStatus;
  detail?: string;
}

export interface PongMessage {
  type: 'pong';
  ts: number;
  serverTs: number;
}

export interface NoticeMessage {
  type: 'notice';
  level: 'info' | 'warn' | 'error';
  text: string;
}

export interface SettingsPushMessage {
  type: 'settings';
  settings: ClientSettings;
}

export type ServerMessage =
  | WelcomeMessage
  | AckMessage
  | PongMessage
  | NoticeMessage
  | SettingsPushMessage;

export interface PairResponse {
  deviceId: string;
  sessionToken: string;
  serverVersion: string;
  protocolVersion: number;
  status: 'active' | 'pending' | 'revoked';
  settings: ClientSettings;
}

/** Скан в очереди телефона: живёт до подтверждения от ПК. */
export interface QueuedScan {
  id: string;
  code: string;
  format: string;
  scannedAt: number;
  source: ScanSource;
  status: 'pending' | AckStatus;
  detail?: string;
}

export type ConnectionStatus = 'offline' | 'connecting' | 'online' | 'unauthorized';
