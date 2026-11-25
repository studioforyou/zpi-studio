import { pl } from '@/shared/i18n/pl';
import {
  DEFAULT_TIME_ZONE,
  addDays,
  formatDayShort,
  formatTime,
  toZoned,
} from '@/features/schedule/studioTime';
import type { AnnouncementSummary } from './announcementsApi';

const texts = pl.communication.announcements;

/** `dziś, 17:40`, `wczoraj, 12:05` or `15.11, 09:30` (`15.11.2025, 09:30` in another year). */
export function formatSentAt(
  instant: string,
  now: Date = new Date(),
  timeZone: string = DEFAULT_TIME_ZONE,
): string {
  const date = toZoned(instant, timeZone).date;
  const today = toZoned(now, timeZone).date;
  const time = formatTime(instant, timeZone);
  if (date === today) return texts.today(time);
  if (date === addDays(today, -1)) return texts.yesterday(time);
  const [year, month, day] = date.split('-');
  return `${day}.${month}${year === today.slice(0, 4) ? '' : `.${year}`}, ${time}`;
}

/** `sob., 22.11, 10:00` */
export function formatEventWhen(instant: string, timeZone: string = DEFAULT_TIME_ZONE): string {
  return `${formatDayShort(toZoned(instant, timeZone).date)}, ${formatTime(instant, timeZone)}`;
}

/** Who the announcement was for, e.g. `Grupa: Pilates` or `Wszyscy klienci`. */
export function audienceLabel(announcement: AnnouncementSummary): string {
  switch (announcement.audience) {
    case 'PERSON':
      return texts.audience.PERSON(announcement.targetName);
    case 'GROUP':
      return texts.audience.GROUP(announcement.targetName);
    case 'EVENT':
      return texts.audience.EVENT(
        announcement.targetName,
        announcement.eventStartsAt ? formatEventWhen(announcement.eventStartsAt) : null,
      );
    case 'ALL':
      return texts.audience.ALL;
  }
}
