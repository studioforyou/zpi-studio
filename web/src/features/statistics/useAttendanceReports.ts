import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query';
import {
  attendanceReportKeys,
  getBookingOutcomes,
  getClassAttendance,
  groupEventKinds,
  type BookingOutcomesReport,
  type ClassAttendanceReport,
} from './attendanceReportsApi';
import type { StatisticsFilters } from './useStatisticsFilters';

export type ReportState<T> = {
  report: UseQueryResult<T>;
  /** The shown report is older than the filters: another one is loading or the period is invalid. */
  stale: boolean;
};

/**
 * Occupancy and attendance for the filters; only group classes and workshops have them, so
 * `applicable` is false when the filters choose personal training alone.
 */
export function useClassAttendance(
  filters: StatisticsFilters,
): ReportState<ClassAttendanceReport> & { applicable: boolean } {
  const kinds = filters.events.kinds.filter((kind) => groupEventKinds.includes(kind));
  const query = filters.query && kinds.length > 0 ? { ...filters.query, kinds } : null;
  const report = useQuery({
    queryKey: attendanceReportKeys.classAttendance(query ?? emptyQuery),
    queryFn: () => getClassAttendance(query!),
    enabled: query !== null,
    // Keeps the previous report on screen, dimmed, while another one loads.
    placeholderData: keepPreviousData,
  });
  return {
    report,
    stale: report.isPlaceholderData || filters.query === null,
    applicable: kinds.length > 0,
  };
}

/** No-shows and cancellations of the events the filters choose. */
export function useBookingOutcomes(filters: StatisticsFilters): ReportState<BookingOutcomesReport> {
  const query = filters.query;
  const report = useQuery({
    queryKey: attendanceReportKeys.bookingOutcomes(query ?? emptyQuery),
    queryFn: () => getBookingOutcomes(query!),
    enabled: query !== null,
    placeholderData: keepPreviousData,
  });
  return { report, stale: report.isPlaceholderData || query === null };
}

/** Stands in for a query that is not sent, so the query key stays well formed. */
const emptyQuery = {
  from: '',
  to: '',
  kinds: [],
  serviceTypeIds: [],
  staffIds: [],
  trainingGroupIds: [],
  resourceIds: [],
};
