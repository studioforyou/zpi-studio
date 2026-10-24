import {
  addDays,
  addMonths,
  formatDate,
  formatDayLong,
  formatMonth,
  startOfMonth,
  startOfWeek,
} from './studioTime';

export const scheduleViews = ['day', 'week', 'month', 'list'] as const;
export type ScheduleView = (typeof scheduleViews)[number];

export type ViewRange = {
  /** First shown day. */
  start: string;
  /** Day after the last shown day. */
  end: string;
  label: string;
};

/**
 * The days a view shows around the anchor date. The month view shows whole weeks, so it
 * starts on the Monday before the 1st; the list shows the month itself.
 */
export function viewRange(view: ScheduleView, anchor: string): ViewRange {
  switch (view) {
    case 'day':
      return { start: anchor, end: addDays(anchor, 1), label: formatDayLong(anchor) };
    case 'week': {
      const start = startOfWeek(anchor);
      const end = addDays(start, 7);
      return { start, end, label: `${formatDate(start)} – ${formatDate(addDays(end, -1))}` };
    }
    case 'month': {
      const start = startOfWeek(startOfMonth(anchor));
      return { start, end: addDays(start, 42), label: formatMonth(anchor) };
    }
    case 'list': {
      const start = startOfMonth(anchor);
      return { start, end: addMonths(start, 1), label: formatMonth(anchor) };
    }
  }
}

/** The anchor of the previous (`-1`) or next (`1`) period of the view. */
export function shiftAnchor(view: ScheduleView, anchor: string, direction: -1 | 1): string {
  switch (view) {
    case 'day':
      return addDays(anchor, direction);
    case 'week':
      return addDays(anchor, 7 * direction);
    case 'month':
    case 'list':
      return addMonths(anchor, direction);
  }
}
