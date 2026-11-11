import { useSearchParams } from 'react-router';
import {
  groupEventKinds,
  reportEventKinds,
  type EventFilters,
  type EventReportQuery,
  type ReportEventKind,
} from './attendanceReportsApi';
import {
  periodError,
  periodPresets,
  presetPeriod,
  type Period,
  type PeriodError,
  type PeriodPreset,
} from './reportPeriod';

export type PeriodChoice = PeriodPreset | 'custom';

export type ReportPeriodState = {
  choice: PeriodChoice;
  choose: (choice: PeriodChoice) => void;
  custom: Period;
  setCustom: (period: Period) => void;
  /** The period shown so far, also while a custom one is invalid. */
  period: Period;
  /** The period to report, or `null` while the custom one is invalid. */
  valid: Period | null;
  error: PeriodError | null;
};

/** The filters every report on the statistics page shares. */
export type StatisticsFilters = {
  period: ReportPeriodState;
  events: EventFilters;
  setEvents: (change: Partial<EventFilters>) => void;
  /** Back to every event of the default kinds. */
  clearEvents: () => void;
  /** Whether the events are narrowed down beyond the default kinds. */
  eventsFiltered: boolean;
  /** The query of a report about events, or `null` while the period cannot be reported. */
  query: EventReportQuery | null;
};

const DEFAULT_PRESET: PeriodPreset = 'last30';

/** Group classes and workshops; personal training only when chosen, so it does not skew them. */
export const defaultEventKinds: ReportEventKind[] = [...groupEventKinds];

/** Query parameter of each event filter; lists are comma separated. */
const listParams = {
  kinds: 'kind',
  serviceTypeIds: 'service',
  staffIds: 'staff',
  trainingGroupIds: 'group',
  resourceIds: 'room',
} as const satisfies Record<keyof EventFilters, string>;

/**
 * The period and event filters of the statistics page, kept in the address so a view can be
 * linked and survives a reload: `?period=thisMonth&kind=CLASS&service=…&staff=…`.
 */
export function useStatisticsFilters(today: string): StatisticsFilters {
  const [params, setParams] = useSearchParams();

  function update(changes: Record<string, string | null>) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const [name, value] of Object.entries(changes)) {
          if (value === null || value === '') {
            next.delete(name);
          } else {
            next.set(name, value);
          }
        }
        return next;
      },
      { replace: true },
    );
  }

  const requested = params.get('period');
  const choice: PeriodChoice =
    requested === 'custom' || periodPresets.some((preset) => preset === requested)
      ? (requested as PeriodChoice)
      : DEFAULT_PRESET;
  const custom: Period = { from: params.get('from') ?? '', to: params.get('to') ?? '' };
  const period = choice === 'custom' ? custom : presetPeriod(choice, today);
  const error = choice === 'custom' ? periodError(period) : null;

  const list = (name: string) => (params.get(name) ?? '').split(',').filter(Boolean);
  const kinds = reportEventKinds.filter((kind) => list(listParams.kinds).includes(kind));
  const events: EventFilters = {
    kinds: kinds.length > 0 ? kinds : defaultEventKinds,
    serviceTypeIds: list(listParams.serviceTypeIds),
    staffIds: list(listParams.staffIds),
    trainingGroupIds: list(listParams.trainingGroupIds),
    resourceIds: list(listParams.resourceIds),
  };
  const defaultKinds = sameKinds(events.kinds, defaultEventKinds);

  return {
    period: {
      choice,
      choose: (value) => {
        if (value === choice) {
          return;
        }
        // A custom range starts from the period shown so far.
        update(
          value === 'custom'
            ? { period: 'custom', from: period.from, to: period.to }
            : { period: value === DEFAULT_PRESET ? null : value, from: null, to: null },
        );
      },
      custom,
      setCustom: (next) => update({ from: next.from, to: next.to }),
      period,
      valid: error ? null : period,
      error,
    },
    events,
    setEvents: (change) =>
      update(
        Object.fromEntries(
          (Object.keys(change) as (keyof EventFilters)[]).map((key) => {
            const values = change[key] ?? [];
            const isDefault =
              key === 'kinds'
                ? values.length === 0 || sameKinds(values as ReportEventKind[], defaultEventKinds)
                : values.length === 0;
            return [listParams[key], isDefault ? null : values.join(',')];
          }),
        ),
      ),
    clearEvents: () =>
      update(Object.fromEntries(Object.values(listParams).map((name) => [name, null]))),
    eventsFiltered:
      !defaultKinds ||
      events.serviceTypeIds.length > 0 ||
      events.staffIds.length > 0 ||
      events.trainingGroupIds.length > 0 ||
      events.resourceIds.length > 0,
    query: error ? null : { ...period, ...events },
  };
}

function sameKinds(a: readonly ReportEventKind[], b: readonly ReportEventKind[]): boolean {
  return a.length === b.length && a.every((kind) => b.includes(kind));
}
