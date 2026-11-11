import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type {
  BookingOutcomeCounts,
  BookingOutcomesReport,
  ClassAttendanceCounts,
  ClassAttendanceReport,
} from '@/features/statistics/attendanceReportsApi';
import { StatisticsPage } from '@/features/statistics/StatisticsPage';
import { renderWithProviders } from './renderWithProviders';

/** `search` is the query string of the page's address, e.g. `tab=outcomes&kind=CLASS`. */
function renderStatisticsPage(search?: string) {
  return renderWithProviders(null, {
    routes: [{ path: '/statistics', element: <StatisticsPage /> }],
    initialEntry: search ? `/statistics?${search}` : '/statistics',
  });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function classCounts(values: Partial<ClassAttendanceCounts>): ClassAttendanceCounts {
  return {
    events: 0,
    booked: 0,
    attended: 0,
    absent: 0,
    unmarked: 0,
    places: 0,
    placesBooked: 0,
    waitlisted: 0,
    ...values,
  };
}

function outcomeCounts(values: Partial<BookingOutcomeCounts>): BookingOutcomeCounts {
  return {
    events: 0,
    bookings: 0,
    attended: 0,
    absent: 0,
    unmarked: 0,
    cancelledInTime: 0,
    cancelledLate: 0,
    lateEntriesForfeited: 0,
    ...values,
  };
}

const attendance: ClassAttendanceReport = {
  from: '2026-09-26',
  to: '2026-10-25',
  kinds: ['CLASS', 'WORKSHOP'],
  totals: classCounts({
    events: 9,
    booked: 40,
    attended: 36,
    absent: 4,
    unmarked: 2,
    places: 60,
    placesBooked: 30,
    waitlisted: 3,
  }),
  classes: [
    {
      id: 'tuesday',
      seriesId: 'tuesday',
      title: 'Pole dance początkujący',
      serviceTypeId: 'pole',
      serviceTypeName: 'Pole dance',
      calendarColor: '#E91E63',
      trainingGroupId: 'group',
      trainingGroupName: 'Pole wt/czw 18:00',
      weekday: 2,
      startTime: '18:00',
      counts: classCounts({
        events: 4,
        booked: 20,
        attended: 18,
        absent: 2,
        places: 40,
        placesBooked: 20,
        waitlisted: 3,
      }),
    },
    {
      id: 'thursday',
      seriesId: 'thursday',
      title: 'Pole dance początkujący',
      serviceTypeId: 'pole',
      serviceTypeName: 'Pole dance',
      calendarColor: '#E91E63',
      trainingGroupId: 'group',
      trainingGroupName: 'Pole wt/czw 18:00',
      weekday: 4,
      startTime: '18:00',
      counts: classCounts({
        events: 4,
        booked: 10,
        attended: 8,
        absent: 2,
        places: 20,
        placesBooked: 10,
      }),
    },
    {
      id: 'workshop',
      seriesId: null,
      title: 'Warsztaty stretchingu',
      serviceTypeId: 'stretch',
      serviceTypeName: 'Stretching',
      calendarColor: '#4CAF50',
      trainingGroupId: null,
      trainingGroupName: null,
      weekday: 6,
      startTime: '12:00',
      counts: classCounts({ events: 1, booked: 10, attended: 10 }),
    },
  ],
  days: [
    { date: '2026-10-06', counts: classCounts({ events: 1, booked: 5, attended: 5 }) },
    { date: '2026-10-13', counts: classCounts({ events: 1, booked: 5, attended: 4 }) },
  ],
};

function outcomes(kinds: string[]): BookingOutcomesReport {
  return {
    from: '2026-09-26',
    to: '2026-10-25',
    kinds: kinds as BookingOutcomesReport['kinds'],
    totals: outcomeCounts({
      events: 9,
      bookings: 50,
      attended: 36,
      absent: 4,
      unmarked: 2,
      cancelledInTime: 6,
      cancelledLate: 4,
      lateEntriesForfeited: 3,
    }),
    services: [
      {
        serviceTypeId: 'pole',
        name: 'Pole dance',
        calendarColor: '#E91E63',
        counts: outcomeCounts({
          events: 8,
          bookings: 40,
          attended: 26,
          absent: 4,
          cancelledInTime: 6,
          cancelledLate: 4,
        }),
        policy: {
          cancellationAllowed: true,
          freeCancelHours: 12,
          lateCancelAllowed: true,
          lateCancelForfeitsEntry: true,
        },
      },
    ],
    cancellationLeadTimes: [
      { fromHours: 0, toHours: 2, inTime: 0, late: 2 },
      { fromHours: 2, toHours: 6, inTime: 0, late: 2 },
      { fromHours: 6, toHours: 12, inTime: 0, late: 0 },
      { fromHours: 12, toHours: 24, inTime: 3, late: 0 },
      { fromHours: 24, toHours: 48, inTime: 2, late: 0 },
      { fromHours: 48, toHours: null, inTime: 1, late: 0 },
    ],
    days: [
      {
        date: '2026-10-06',
        counts: outcomeCounts({ events: 1, bookings: 6, attended: 5, absent: 1 }),
      },
    ],
  };
}

/** Answers every request of the page; customer reports count `active` and `fresh` for any period. */
function stubApi(active = 12, fresh = 3) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/v1/studio') {
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    }
    const query = new URLSearchParams(url.split('?')[1]);
    if (url.startsWith('/api/v1/reports/customer-activity?')) {
      return json({
        current: {
          from: query.get('from'),
          to: query.get('to'),
          activeCustomers: active,
          newCustomers: fresh,
        },
        previous: { from: '2026-08-27', to: '2026-09-25', activeCustomers: 8, newCustomers: 3 },
      });
    }
    if (url.startsWith('/api/v1/reports/class-attendance?')) {
      return json(attendance);
    }
    if (url.startsWith('/api/v1/reports/booking-outcomes?')) {
      return json(outcomes(query.getAll('kind')));
    }
    if (url === '/api/v1/service-types') {
      return json({
        items: [
          { id: 'pole', name: 'Pole dance', category: 'GROUP_CLASS', isActive: true },
          { id: 'stretch', name: 'Stretching', category: 'WORKSHOP', isActive: true },
          { id: 'pt', name: 'Trening personalny', category: 'PERSONAL_TRAINING', isActive: true },
        ],
      });
    }
    if (url === '/api/v1/staff') {
      return json({ items: [{ id: 'anna', displayName: 'Anna Kowalska', isActive: true }] });
    }
    if (url === '/api/v1/training-groups') {
      return json({ items: [{ id: 'group', name: 'Pole wt/czw 18:00', isActive: true }] });
    }
    if (url === '/api/v1/resources') {
      return json({
        items: [{ id: 'room', name: 'Sala pole dance', kind: 'ROOM', isActive: true }],
      });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function reportRequests(fetchMock: ReturnType<typeof stubApi>, report: string): string[] {
  return fetchMock.mock.calls
    .map(([url]) => url)
    .filter((url) => url.startsWith(`/api/v1/reports/${report}`));
}

beforeEach(() => {
  // Only Date is faked, so user events and React Query keep their real timers.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-25T12:00:00Z'));
});

afterEach(() => {
  vi.useRealTimers();
});

const groupQuery = 'from=2026-09-26&to=2026-10-25&kind=CLASS&kind=WORKSHOP';

test('shows the whole studio at a glance first', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage();

  // Each headline number shows as soon as its own report has loaded.
  await screen.findByText('30 z 60 miejsc zajętych');
  // No-shows and late cancellations are 4 of 50 each.
  expect(await screen.findAllByText('4 z 50 rezerwacji')).toHaveLength(2);
  await screen.findByText('▲ +4 (+50%)');
  const fill = screen.getByRole('group', { name: 'Średnie obłożenie' });
  expect(within(fill).getByText('50%')).toBeInTheDocument();
  expect(
    within(screen.getByRole('group', { name: 'Frekwencja' })).getByText('90%'),
  ).toBeInTheDocument();
  const noShows = screen.getByRole('group', { name: 'Nieobecności' });
  expect(within(noShows).getByText('8%')).toBeInTheDocument();
  const late = screen.getByRole('group', { name: 'Późne anulowania' });
  expect(within(late).getByText('3 z utratą wejścia z karnetu')).toBeInTheDocument();
  const active = screen.getByRole('group', { name: 'Aktywni klienci' });
  expect(within(active).getByText('12')).toBeInTheDocument();
  // Without event filters the customer numbers need no remark.
  expect(within(active).queryByText(/całe studio/)).not.toBeInTheDocument();

  const fullest = screen.getByRole('region', { name: 'Najpełniejsze zajęcia' });
  expect(within(fullest).getAllByText('Pole dance początkujący')).toHaveLength(2);
  expect(reportRequests(fetchMock, 'class-attendance')).toEqual([
    `/api/v1/reports/class-attendance?${groupQuery}`,
  ]);
  expect(reportRequests(fetchMock, 'booking-outcomes')).toEqual([
    `/api/v1/reports/booking-outcomes?${groupQuery}`,
  ]);
  expect(reportRequests(fetchMock, 'customer-activity')).toEqual([
    '/api/v1/reports/customer-activity?from=2026-09-26&to=2026-10-25',
  ]);
});

test('opens the details of a section from the overview', async () => {
  stubApi();
  renderStatisticsPage();
  const trend = await screen.findByRole('region', { name: 'Obłożenie i frekwencja w czasie' });

  await userEvent.click(within(trend).getByRole('button', { name: 'Szczegóły →' }));

  expect(screen.getByRole('tab', { name: 'Frekwencja i obłożenie' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(
    await screen.findByRole('region', { name: 'Obłożenie i frekwencja poszczególnych zajęć' }),
  ).toBeInTheDocument();
});

test('shows occupancy with days in columns and start hours in rows', async () => {
  stubApi();
  renderStatisticsPage('tab=attendance');

  const heatmap = await screen.findByRole('table', { name: 'Kiedy zajęcia są najpełniejsze' });
  expect(
    within(heatmap)
      .getAllByRole('columnheader')
      .map((header) => header.getAttribute('aria-label'))
      .slice(1),
  ).toEqual(['poniedziałek', 'wtorek', 'środa', 'czwartek', 'piątek', 'sobota', 'niedziela']);
  expect(
    within(heatmap)
      .getAllByRole('rowheader')
      .map((header) => header.textContent),
  ).toEqual(['12:00', '18:00']);
  const evening = within(heatmap).getAllByRole('row')[2];
  expect(
    within(evening).getByRole('cell', {
      name: 'wtorek, 18:00: obłożenie 50%. Pole dance początkujący (20 / 40)',
    }),
  ).toBeInTheDocument();
  expect(
    within(heatmap).getByRole('cell', {
      name: 'sobota, 12:00: obłożenie bez limitu. Warsztaty stretchingu',
    }),
  ).toBeInTheDocument();
});

test('lists the classes fullest first and sums them per fixed group', async () => {
  stubApi();
  renderStatisticsPage('tab=attendance');

  const table = await screen.findByRole('region', {
    name: 'Obłożenie i frekwencja poszczególnych zajęć',
  });
  const rows = () => within(table).getAllByRole('row').slice(1);
  expect(rows().map((row) => within(row).getAllByRole('cell')[0].textContent)).toEqual([
    'Pole dance początkującywt. 18:00 · Pole wt/czw 18:00',
    'Pole dance początkującyczw. 18:00 · Pole wt/czw 18:00',
    'Warsztaty stretchingusob. 12:00 · jednorazowe',
  ]);
  expect(within(rows()[2]).getByText('bez limitu')).toBeInTheDocument();

  await userEvent.click(within(table).getByText('Grupy stałe'));

  expect(rows()).toHaveLength(2);
  expect(within(rows()[0]).getByText('Pole wt/czw 18:00')).toBeInTheDocument();
  expect(within(rows()[0]).getByText('50%')).toBeInTheDocument();
  expect(within(rows()[1]).getByText('Bez stałej grupy')).toBeInTheDocument();

  await userEvent.click(within(table).getByRole('button', { name: 'Sortuj według: Nazwa' }));

  expect(within(rows()[0]).getByText('Bez stałej grupy')).toBeInTheDocument();
});

test('shows no-shows and late cancellations with the current cancellation rules', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=outcomes');

  const absent = await screen.findByRole('group', { name: 'Nieobecności' });
  expect(within(absent).getByText('4')).toBeInTheDocument();
  expect(within(absent).getByText('8% rezerwacji')).toBeInTheDocument();
  expect(
    screen.getByRole('img', {
      name: 'Podział rezerwacji: Obecni 36 (72%), Nieobecności 4 (8%), Późne anulowania 4 (8%), Anulowania na czas 6 (12%)',
    }),
  ).toBeInTheDocument();
  expect(
    screen.getByText('bezpłatnie do 12 godzin przed, później wejście przepada'),
  ).toBeInTheDocument();
  expect(
    screen.getByText('Obecnie bezpłatne anulowanie do 12 godzin przed rozpoczęciem.'),
  ).toBeInTheDocument();
  expect(reportRequests(fetchMock, 'booking-outcomes')).toEqual([
    `/api/v1/reports/booking-outcomes?${groupQuery}`,
  ]);
});

test('adds personal training to the cancellations when its kind is chosen', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=outcomes');
  await screen.findByRole('group', { name: 'Nieobecności' });

  await userEvent.click(screen.getByRole('checkbox', { name: 'Treningi personalne' }));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock, 'booking-outcomes')).toContain(
      '/api/v1/reports/booking-outcomes?from=2026-09-26&to=2026-10-25&kind=CLASS&kind=PERSONAL_TRAINING&kind=WORKSHOP',
    ),
  );
  expect(screen.getByRole('button', { name: 'Wyczyść filtry' })).toBeInTheDocument();
});

test('narrows every report to the chosen kinds of classes, staff, groups and rooms', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=attendance');
  await screen.findByRole('group', { name: 'Średnie obłożenie' });

  await userEvent.click(screen.getByRole('combobox', { name: 'Rodzaj zajęć' }));
  await userEvent.click(await screen.findByRole('option', { name: 'Pole dance', hidden: true }));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock, 'class-attendance')).toContain(
      `/api/v1/reports/class-attendance?${groupQuery}&serviceTypeId=pole`,
    ),
  );

  await userEvent.click(screen.getByRole('button', { name: 'Wyczyść filtry' }));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock, 'class-attendance').at(-1)).toBe(
      `/api/v1/reports/class-attendance?${groupQuery}`,
    ),
  );
  expect(screen.queryByRole('button', { name: 'Wyczyść filtry' })).not.toBeInTheDocument();
});

test('takes the period and filters from the address', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage(
    'tab=outcomes&period=thisMonth&kind=CLASS&service=pole,stretch&staff=anna&group=group&room=room',
  );

  await screen.findByRole('group', { name: 'Nieobecności' });
  expect(reportRequests(fetchMock, 'booking-outcomes')).toEqual([
    '/api/v1/reports/booking-outcomes?from=2026-10-01&to=2026-10-25&kind=CLASS' +
      '&serviceTypeId=pole&serviceTypeId=stretch&staffId=anna&trainingGroupId=group&resourceId=room',
  ]);
  expect(screen.getByRole('checkbox', { name: 'Zajęcia grupowe' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: 'Warsztaty' })).not.toBeChecked();
});

test('has no occupancy to show for personal training alone', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=attendance&kind=PERSONAL_TRAINING');

  expect(
    await screen.findByText(/Obłożenie i frekwencja dotyczą zajęć grupowych i warsztatów/),
  ).toBeInTheDocument();
  expect(reportRequests(fetchMock, 'class-attendance')).toEqual([]);
});

test('says the customer report covers the whole studio when events are filtered', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=customers&staff=anna');

  expect(await screen.findByText(/Raport klientów obejmuje całe studio/)).toBeInTheDocument();
  expect(reportRequests(fetchMock, 'customer-activity')).toEqual([
    '/api/v1/reports/customer-activity?from=2026-09-26&to=2026-10-25',
  ]);
});

test('shows active and new customers of the last 30 days against the 30 days before', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=customers');

  const active = await screen.findByRole('group', { name: 'Aktywni klienci' });
  expect(await within(active).findByText('12')).toBeInTheDocument();
  expect(within(active).getByText('▲ +4 (+50%)')).toBeInTheDocument();
  expect(within(active).getByText('poprzednio 8 (27.08.2026 – 25.09.2026)')).toBeInTheDocument();
  const fresh = screen.getByRole('group', { name: 'Nowi klienci' });
  expect(within(fresh).getByText('3')).toBeInTheDocument();
  expect(within(fresh).getByText('bez zmian')).toBeInTheDocument();
  expect(screen.getByText('26.09.2026 – 25.10.2026 (30 dni)')).toBeInTheDocument();
  expect(screen.queryByText(/Raport klientów obejmuje całe studio/)).not.toBeInTheDocument();
  expect(reportRequests(fetchMock, 'customer-activity')).toEqual([
    '/api/v1/reports/customer-activity?from=2026-09-26&to=2026-10-25',
  ]);
});

test('reports fewer customers as a decrease', async () => {
  stubApi(6);
  renderStatisticsPage('tab=customers');

  const active = await screen.findByRole('group', { name: 'Aktywni klienci' });
  expect(await within(active).findByText('▼ −2 (−25%)')).toBeInTheDocument();
});

test('asks every report for the period of the chosen preset', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=customers');
  await screen.findByText('▲ +4 (+50%)');

  await userEvent.click(screen.getByText('Ten miesiąc'));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock, 'customer-activity')).toContain(
      '/api/v1/reports/customer-activity?from=2026-10-01&to=2026-10-25',
    ),
  );
  expect(await screen.findByText('01.10.2026 – 25.10.2026 (25 dni)')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('tab', { name: 'Frekwencja i obłożenie' }));

  await vi.waitFor(() =>
    expect(reportRequests(fetchMock, 'class-attendance')).toEqual([
      '/api/v1/reports/class-attendance?from=2026-10-01&to=2026-10-25&kind=CLASS&kind=WORKSHOP',
    ]),
  );
});

test('does not ask for a custom period that ends before it starts', async () => {
  const fetchMock = stubApi();
  renderStatisticsPage('tab=customers');
  await screen.findByText('▲ +4 (+50%)');

  await userEvent.click(screen.getByText('Własny'));
  const from = screen.getByLabelText('Od');
  await userEvent.clear(from);
  await userEvent.type(from, '2026-11-01');

  expect(
    await screen.findByText('Data końcowa nie może być wcześniejsza niż początkowa.'),
  ).toBeInTheDocument();
  expect(reportRequests(fetchMock, 'customer-activity')).toHaveLength(1);
});
