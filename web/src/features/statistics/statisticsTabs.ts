/**
 * Tabs of the statistics page, in order. A new report adds its value here, its label in
 * `pl.statistics.tabs` and its panel in `StatisticsPage`.
 */
export const statisticsTabs = ['overview', 'attendance', 'outcomes', 'customers'] as const;
export type StatisticsTab = (typeof statisticsTabs)[number];

export const defaultStatisticsTab: StatisticsTab = 'overview';
