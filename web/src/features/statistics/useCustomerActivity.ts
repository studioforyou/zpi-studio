import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  customerActivityKeys,
  getCustomerActivity,
  type CustomerActivityReport,
} from './customerActivityApi';
import type { ReportState } from './useAttendanceReports';
import type { StatisticsFilters } from './useStatisticsFilters';

/** Active and new customers of the whole studio in the filters' period. */
export function useCustomerActivity(
  filters: StatisticsFilters,
): ReportState<CustomerActivityReport> {
  const period = filters.period.valid;
  const report = useQuery({
    queryKey: customerActivityKeys.period(period?.from ?? '', period?.to ?? ''),
    queryFn: () => getCustomerActivity(period!.from, period!.to),
    enabled: period !== null,
    placeholderData: keepPreviousData,
  });
  return { report, stale: report.isPlaceholderData || period === null };
}
