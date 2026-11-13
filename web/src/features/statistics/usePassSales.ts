import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getPassSales, passSalesKeys, type PassSalesReport } from './passSalesApi';
import type { ReportState } from './useAttendanceReports';
import type { StatisticsFilters } from './useStatisticsFilters';

/** Passes sold by the whole studio in the filters' period. */
export function usePassSales(filters: StatisticsFilters): ReportState<PassSalesReport> {
  const period = filters.period.valid;
  const report = useQuery({
    queryKey: passSalesKeys.period(period?.from ?? '', period?.to ?? ''),
    queryFn: () => getPassSales(period!.from, period!.to),
    enabled: period !== null,
    placeholderData: keepPreviousData,
  });
  return { report, stale: report.isPlaceholderData || period === null };
}
