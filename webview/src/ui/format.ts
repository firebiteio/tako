import { t } from './i18n';

export function truncate(text: string, limit: number): string {
  return text.length > limit ? text.slice(0, limit) + '\n' + t('common.truncated') : text;
}

export function firstLine(text: unknown): string {
  const line = String(text || '').split('\n')[0];
  return line.length > 160 ? line.slice(0, 159) + '…' : line;
}

export function duration(ms: number): string {
  const seconds = Math.max(1, Math.round(ms / 1000));
  if (seconds < 60) return t('time.duration.seconds', seconds);
  return t('time.duration.minutes', Math.floor(seconds / 60), seconds % 60);
}

/** Seconds below a minute, else {@link duration}: for clocks that tick from zero. */
export function elapsed(since: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - since) / 1000));
  return seconds < 60 ? t('time.duration.seconds', seconds) : duration(seconds * 1000);
}

export function ago(timestamp: number): string {
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) return t('time.ago.now');
  if (seconds < 3600) return t('time.ago.minutes', Math.floor(seconds / 60));
  if (seconds < 86400) return t('time.ago.hours', Math.floor(seconds / 3600));
  return t('time.ago.days', Math.floor(seconds / 86400));
}

export function countdown(ms: number): string {
  const seconds = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(seconds / 60);
  return minutes >= 60
    ? t('time.countdown.hours', Math.floor(minutes / 60), String(minutes % 60).padStart(2, '0'))
    : minutes + ':' + String(seconds % 60).padStart(2, '0');
}

export function money(value: number): string {
  if (!value) return '$0.00';
  if (value < 0.01) return '<$0.01';
  return '$' + (value >= 100 ? value.toFixed(0) : value.toFixed(2));
}

export function tokens(value: number | undefined): string {
  if (!value) return '0';
  if (value >= 1e9) return (value / 1e9).toFixed(1) + 'B';
  if (value >= 1e6) return (value / 1e6).toFixed(1) + 'M';
  if (value >= 1e3) return (value / 1e3).toFixed(1) + 'k';
  return String(value);
}

export function clock(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function shortDay(date: Date): string {
  return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

/** Paths are equal ignoring case and slash direction, as on Windows. */
export function samePath(first: string | null | undefined, second: string | null | undefined): boolean {
  if (!first || !second) return false;
  return first.replace(/\\/g, '/').toLowerCase() === second.replace(/\\/g, '/').toLowerCase();
}
