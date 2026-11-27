import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { StatisticsPage } from '@/features/statistics/StatisticsPage';
import type { TrainerHoursReport, WorkCounts } from '@/features/statistics/trainerHoursApi';
import { renderWithProviders } from './renderWithProviders';
import * as hoursExport from '@/features/statistics/trainerHoursExport';

const empty: WorkCounts = {
  seconds: 0,
  leadSeconds: 0,
  assistantSeconds: 0,
  events: 0,
  unconfirmedSeconds: 0,
  unconfirmedEvents: 0,
};
const anna = '08000000-0000-0000-0000-000000000001';
const marek = '08000000-0000-0000-0000-000000000002';

const report: TrainerHoursReport = {
  from: '2026-10-29',
  to: '2026-11-27',
  timeZone: 'Europe/Warsaw',
  totals: {
    ...empty,
    seconds: 10800,
    leadSeconds: 6300,
    assistantSeconds: 4500,
    events: 2,
    unconfirmedSeconds: 1800,
    unconfirmedEvents: 1,
  },
  instructors: [
    {
      id: anna,
      displayName: 'Anna Kowalska',
      active: true,
      counts: {
        ...empty,
        seconds: 6300,
        leadSeconds: 6300,
        events: 2,
        unconfirmedSeconds: 1800,
        unconfirmedEvents: 1,
      },
      sessions: [
        {
          eventId: 'consultation',
          kind: 'CONSULTATION',
          title: 'Rozmowa wstępna',
          startsAt: '2026-11-26T08:00:00Z',
          endsAt: '2026-11-26T08:30:00Z',
          role: 'LEAD',
          seconds: 1800,
          confirmed: false,
          rooms: 'Sala treningowa 2',
        },
        {
          eventId: 'class',
          kind: 'CLASS',
          title: 'Pole dance — podstawy',
          startsAt: '2026-11-25T17:00:00Z',
          endsAt: '2026-11-25T18:15:00Z',
          role: 'LEAD',
          seconds: 4500,
          confirmed: true,
          rooms: 'Sala pole dance',
        },
      ],
    },
    {
      id: marek,
      displayName: 'Marek Nowak',
      active: false,
      counts: { ...empty, seconds: 4500, assistantSeconds: 4500, events: 1 },
      sessions: [
        {
          eventId: 'class',
          kind: 'CLASS',
          title: 'Pole dance — podstawy',
          startsAt: '2026-11-25T17:00:00Z',
          endsAt: '2026-11-25T18:15:00Z',
          role: 'ASSISTANT',
          seconds: 4500,
          confirmed: true,
          rooms: 'Sala pole dance',
        },
      ],
    },
    { id: 'zero', displayName: 'Karolina Szymańska', active: true, counts: empty, sessions: [] },
  ],
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function stubApi(result = report, fail = false) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/api/v1/studio')
      return json({ name: 'Studio', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    if (url === '/api/v1/staff')
      return json({
        items: result.instructors.map((row) => ({
          id: row.id,
          displayName: row.displayName,
          isActive: row.active,
        })),
      });
    if (url.startsWith('/api/v1/reports/trainer-hours?'))
      return json(fail ? { code: 'INTERNAL_ERROR' } : result, fail ? 500 : 200);
    throw Error(`Unexpected request: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function renderHours(query = '') {
  return renderWithProviders(null, {
    routes: [{ path: '/statistics', element: <StatisticsPage /> }],
    initialEntry: `/statistics?tab=hours${query}`,
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-27T12:00:00Z'));
});
afterEach(() => vi.useRealTimers());

test('shows worked time, unique events, unconfirmed time and zero-hour instructors', async () => {
  const fetchMock = stubApi();
  // The attendance report's old kind filter must not silently hide personal work or consultations.
  renderHours('&kind=CLASS');
  expect(
    await screen.findByRole('button', { name: 'Szczegóły godzin: Anna Kowalska' }),
  ).toBeInTheDocument();
  expect(
    within(screen.getByRole('group', { name: 'Łączny czas pracy' })).getByText('3 h'),
  ).toBeInTheDocument();
  expect(within(screen.getByRole('group', { name: 'Zajęcia' })).getByText('2')).toBeInTheDocument();
  expect(
    within(screen.getByRole('group', { name: 'Bez potwierdzenia' })).getByText('30 min'),
  ).toBeInTheDocument();
  expect(screen.getByText('Nieaktywny')).toBeInTheDocument();
  expect(within(screen.getByRole('table')).getByText('Karolina Szymańska')).toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Szczegóły godzin: Karolina Szymańska' }),
  ).toBeDisabled();
  const requests = fetchMock.mock.calls.map(([url]) => url);
  expect(requests.filter((url) => url.startsWith('/api/v1/reports/'))).toEqual([
    '/api/v1/reports/trainer-hours?from=2026-10-29&to=2026-11-27',
  ]);
});

test('opens exact occurrences in studio time with roles, rooms and confirmation badges', async () => {
  stubApi();
  renderHours();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Szczegóły godzin: Anna Kowalska' }));
  const drawer = await screen.findByRole('dialog', { name: 'Anna Kowalska' });
  expect(within(drawer).getByText('Rozmowa wstępna')).toBeInTheDocument();
  expect(within(drawer).getByText('26.11.2026')).toBeInTheDocument();
  expect(within(drawer).getByText('09:00–09:30')).toBeInTheDocument();
  expect(within(drawer).getByText('Konsultacja · Sala treningowa 2')).toBeInTheDocument();
  expect(within(drawer).getByText('Bez potwierdzenia')).toBeInTheDocument();
  expect(within(drawer).getByText('Odbyte')).toBeInTheDocument();
  expect(within(drawer).getAllByRole('row')).toHaveLength(3);
});

test('keeps counting rules behind the information button', async () => {
  stubApi();
  renderHours();
  const user = userEvent.setup();
  await screen.findByRole('button', { name: 'Szczegóły godzin: Anna Kowalska' });
  expect(screen.queryByText(/Pełny czas zajęć otrzymuje/)).not.toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Jak liczymy godziny?' }));
  expect(await screen.findByText(/Pełny czas zajęć otrzymuje/)).toBeInTheDocument();
  expect(screen.getByText(/Raport nie zmienia obecności ani rozliczeń/)).toBeInTheDocument();
});

test('sends independent instructor, kind and confirmed-only filters and keeps them when changing period', async () => {
  const fetchMock = stubApi();
  renderHours();
  const user = userEvent.setup();
  await screen.findByRole('button', { name: 'Szczegóły godzin: Anna Kowalska' });
  await user.click(screen.getByRole('combobox', { name: 'Typ zajęć' }));
  // Mantine's portal options count as hidden in jsdom, as in the existing schedule tests.
  await user.click(await screen.findByRole('option', { name: 'Konsultacja', hidden: true }));
  await user.click(screen.getByRole('combobox', { name: 'Instruktor' }));
  await user.click(await screen.findByRole('option', { name: 'Marek Nowak', hidden: true }));
  await user.click(screen.getByLabelText('Tylko potwierdzone'));
  await user.click(screen.getByText('7 dni', { exact: true }));
  await waitFor(() =>
    expect(
      fetchMock.mock.calls.some(
        ([url]) =>
          url.includes('/reports/trainer-hours?from=2026-11-21&to=2026-11-27&kind=CONSULTATION') &&
          url.includes(`staffId=${marek}`) &&
          url.includes('confirmedOnly=true'),
      ),
    ).toBe(true),
  );
});

test('does not request or show a stale report for a reversed custom period', async () => {
  const fetchMock = stubApi();
  renderHours('&period=custom&from=2026-11-30&to=2026-11-01');
  expect(
    await screen.findByText('Data końcowa nie może być wcześniejsza niż początkowa.'),
  ).toBeInTheDocument();
  expect(
    fetchMock.mock.calls.some(([url]) => url.startsWith('/api/v1/reports/trainer-hours')),
  ).toBe(false);
});

test('shows an error instead of suggesting there were zero hours when the report fails', async () => {
  stubApi(report, true);
  renderHours();
  expect(await screen.findByText('Nie udało się wczytać godzin instruktorów.')).toBeInTheDocument();
  expect(
    screen.queryByText('Brak zakończonych zajęć dla wybranych filtrów.'),
  ).not.toBeInTheDocument();
});

test('shows an empty period without losing zero-hour rows', async () => {
  stubApi({
    ...report,
    totals: empty,
    instructors: [{ ...report.instructors[0], counts: empty, sessions: [] }],
  });
  renderHours();
  expect(
    await screen.findByText('Brak zakończonych zajęć dla wybranych filtrów.'),
  ).toBeInTheDocument();
  expect(within(screen.getByRole('table')).getByText('Anna Kowalska')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Szczegóły godzin: Anna Kowalska' })).toBeDisabled();
});

test('exports a freshly fetched full calendar month, independently of report kind and confirmation filters', async () => {
  const fetchMock = stubApi();
  const download = vi.spyOn(hoursExport, 'downloadHoursCsv').mockImplementation(() => {});
  renderHours(`&hoursStaff=${anna}&hoursKind=CLASS&hoursConfirmed=true`);
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Eksport miesięczny' }));
  const dialog = await screen.findByRole('dialog', { name: 'Eksport miesięczny' });
  fireEvent.change(within(dialog).getByLabelText('Miesiąc'), { target: { value: '2024-02' } });
  await user.click(within(dialog).getByRole('button', { name: 'Pobierz CSV' }));
  await waitFor(() => expect(download).toHaveBeenCalledOnce());
  expect(fetchMock.mock.calls.map(([url]) => url)).toContain(
    `/api/v1/reports/trainer-hours?from=2024-02-01&to=2024-02-29&staffId=${anna}`,
  );
  expect(download.mock.calls[0][0]).toMatchObject({ studioName: 'Studio', staffId: anna });
  download.mockRestore();
});

test('rejects a future export month and handles blocked printing without requesting a report', async () => {
  const fetchMock = stubApi();
  const popup = vi.spyOn(window, 'open').mockReturnValue(null);
  renderHours();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Eksport miesięczny' }));
  const dialog = await screen.findByRole('dialog', { name: 'Eksport miesięczny' });
  const month = within(dialog).getByLabelText('Miesiąc');
  fireEvent.change(month, { target: { value: '2026-12' } });
  expect(within(dialog).getByRole('button', { name: 'Pobierz PDF' })).toBeDisabled();
  fireEvent.change(month, { target: { value: '2026-11' } });
  const before = fetchMock.mock.calls.length;
  await user.click(within(dialog).getByRole('button', { name: 'Drukuj' }));
  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    'Zezwól na otwarcie okna wydruku',
  );
  expect(fetchMock.mock.calls.length).toBe(before);
  popup.mockRestore();
});

test('allows retrying a failed monthly export without downloading an empty document', async () => {
  stubApi(report, true);
  const download = vi.spyOn(hoursExport, 'downloadHoursCsv').mockImplementation(() => {});
  renderHours();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Eksport miesięczny' }));
  const dialog = await screen.findByRole('dialog', { name: 'Eksport miesięczny' });
  await user.click(within(dialog).getByRole('button', { name: 'Pobierz CSV' }));
  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    'Nie udało się przygotować zestawienia',
  );
  expect(download).not.toHaveBeenCalled();
  expect(within(dialog).getByRole('button', { name: 'Pobierz CSV' })).toBeEnabled();
  download.mockRestore();
});
