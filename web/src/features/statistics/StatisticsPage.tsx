import type { ReactNode } from 'react';
import { Stack, Tabs, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import { DEFAULT_TIME_ZONE, todayIn } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { BookingOutcomesPanel } from './BookingOutcomesPanel';
import { ClassAttendancePanel } from './ClassAttendancePanel';
import { CustomerActivityPanel } from './CustomerActivityPanel';
import { OverviewPanel } from './OverviewPanel';
import { StatisticsFilterBar } from './StatisticsFilterBar';
import { defaultStatisticsTab, statisticsTabs, type StatisticsTab } from './statisticsTabs';
import { useStatisticsFilters, type StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics;

type PanelProps = { filters: StatisticsFilters; open: (tab: StatisticsTab) => void };

/** The panel of each tab; every one gets the page's filters. */
const panels: Record<StatisticsTab, (props: PanelProps) => ReactNode> = {
  overview: (props) => <OverviewPanel {...props} />,
  attendance: ({ filters }) => <ClassAttendancePanel filters={filters} />,
  outcomes: ({ filters }) => <BookingOutcomesPanel filters={filters} />,
  customers: ({ filters }) => <CustomerActivityPanel filters={filters} />,
};

/** The studio's dashboard: every report, for the period and events chosen above them. */
export function StatisticsPage() {
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const filters = useStatisticsFilters(todayIn(studio.data?.timeZone ?? DEFAULT_TIME_ZONE));
  // The tab is part of the address with the filters, so a view can be linked.
  const [searchParams, setSearchParams] = useSearchParams();
  const requested = searchParams.get('tab');
  const tab = statisticsTabs.find((value) => value === requested) ?? defaultStatisticsTab;

  function open(value: string | null) {
    setSearchParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value && value !== defaultStatisticsTab) {
          next.set('tab', value);
        } else {
          next.delete('tab');
        }
        return next;
      },
      { replace: true },
    );
  }

  return (
    <Stack gap="lg">
      <Title order={2}>{texts.title}</Title>
      <StatisticsFilterBar filters={filters} />
      {/* Only the open tab is mounted, so only its reports are loaded. */}
      <Tabs value={tab} onChange={open} keepMounted={false}>
        <Tabs.List>
          {statisticsTabs.map((value) => (
            <Tabs.Tab key={value} value={value}>
              {texts.tabs[value]}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        {statisticsTabs.map((value) => (
          <Tabs.Panel key={value} value={value} pt="md">
            {panels[value]({ filters, open })}
          </Tabs.Panel>
        ))}
      </Tabs>
    </Stack>
  );
}
