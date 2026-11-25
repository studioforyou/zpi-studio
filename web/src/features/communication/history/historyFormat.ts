import { pl } from '@/shared/i18n/pl';
import type { HistoryItem, SendStatus } from './historyApi';

const texts = pl.communication.history;
const typeNames = pl.communication.notifications.types as Record<string, { name: string }>;

/** Every type the filter offers: announcements first, then the automatic ones. */
export const typeOptions = [
  { value: 'ANNOUNCEMENT', label: texts.announcementType },
  ...Object.entries(typeNames).map(([code, type]) => ({ value: code, label: type.name })),
];

export function typeLabel(code: string): string {
  return code === 'ANNOUNCEMENT' ? texts.announcementType : (typeNames[code]?.name ?? code);
}

export const statusColor: Record<SendStatus, string> = {
  READ: 'green',
  DELIVERED: 'blue',
  PUSH_FAILED: 'red',
  APP_ONLY: 'gray',
};

/** `klient · dotyczy: Zosia Lis` */
export function recipientNote(item: HistoryItem): string | null {
  const parts = [
    item.recipientRole ? texts.roles[item.recipientRole] : null,
    item.about ? texts.about(item.about) : null,
  ].filter(Boolean);
  return parts.length === 0 ? null : parts.join(' · ');
}
