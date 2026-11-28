import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { StatisticsPage } from '@/features/statistics/StatisticsPage';
import {
  settlementAmount,
  type TrainerSettlement,
} from '@/features/statistics/trainerSettlementsApi';
import { settlementsCsv, settlementsPdf } from '@/features/statistics/trainerSettlementsExport';
import { renderWithProviders } from './renderWithProviders';

const money = (amountMinor: number) => ({ amountMinor, currency: 'PLN' });
const staffId = '08000000-0000-0000-0000-000000000002';
const counts = {
  seconds: 5400,
  leadSeconds: 5400,
  assistantSeconds: 0,
  events: 2,
  unconfirmedSeconds: 1800,
  unconfirmedEvents: 1,
};
const report = {
  from: '2026-10-01',
  to: '2026-10-31',
  timeZone: 'Europe/Warsaw',
  totals: counts,
  instructors: [
    {
      id: staffId,
      displayName: 'Marek Nowak',
      active: true,
      counts,
      sessions: [
        {
          eventId: 'event1',
          kind: 'CONSULTATION' as const,
          title: 'Zapisana konsultacja',
          startsAt: '2026-10-21T08:00:00Z',
          endsAt: '2026-10-21T09:00:00Z',
          role: 'LEAD' as const,
          seconds: 3600,
          confirmed: true,
          rooms: 'Sala Żółta',
        },
        {
          eventId: 'event2',
          kind: 'CONSULTATION' as const,
          title: 'Bez potwierdzenia',
          startsAt: '2026-10-22T08:00:00Z',
          endsAt: '2026-10-22T08:30:00Z',
          role: 'LEAD' as const,
          seconds: 1800,
          confirmed: false,
          rooms: 'Sala Żółta',
        },
      ],
    },
  ],
};
const fixture: TrainerSettlement = {
  summary: {
    id: 'settlement1',
    staffId,
    month: '2026-10',
    displayName: 'Marek Nowak',
    version: 0,
    revision: 1,
    amount: money(10000),
    paid: money(0),
    remaining: money(10000),
    countedSeconds: 3600,
    unconfirmedSeconds: 1800,
    includeUnconfirmed: false,
    status: 'UNPAID',
    updatedAt: '2026-11-26T12:00:00Z',
  },
  revisions: [
    {
      id: 'revision1',
      revision: 1,
      amount: money(10000),
      includeUnconfirmed: false,
      snapshot: { report, studioName: 'Studio Łódź', generatedAt: '2026-11-26T12:00:00Z' },
      reason: 'Uzgodniona kwota',
      actor: 'admin@studio.pl',
      createdAt: '2026-11-26T12:00:00Z',
    },
  ],
  payments: [],
};

function response(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
function renderPage() {
  return renderWithProviders(null, {
    routes: [{ path: '/statistics', element: <StatisticsPage /> }],
    initialEntry: '/statistics?tab=settlements',
  });
}
function setup({ empty = false, failFirstPayout = false, stalePreview = false } = {}) {
  let current = structuredClone(fixture);
  let created = !empty;
  let fresh = false;
  const writes: { url: string; body: Record<string, unknown> }[] = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === '/api/v1/studio')
      return response({ name: 'Studio Łódź', timeZone: 'Europe/Warsaw', currency: 'PLN' });
    if (url.startsWith('/api/v1/reports/trainer-hours?')) return response(report);
    if (url.startsWith('/api/v1/trainer-settlements/preview?'))
      return response({
        snapshot: current.revisions[0].snapshot,
        currency: 'PLN',
        fingerprint: fresh ? 'fresh' : 'old',
      });
    if (init?.method === 'POST') {
      const body = JSON.parse(init.body as string);
      writes.push({ url, body });
      if (url === '/api/v1/trainer-settlements') {
        if (stalePreview && !fresh) {
          fresh = true;
          return response({ code: 'SETTLEMENT_SOURCE_CHANGED' }, 409);
        }
        created = true;
        return response(current);
      }
      if (url.endsWith('/payouts')) {
        if (failFirstPayout && writes.filter((x) => x.url.endsWith('/payouts')).length === 1)
          throw Error('Offline');
        current = {
          ...current,
          summary: {
            ...current.summary,
            version: 1,
            paid: body.amount,
            remaining: money(10000 - body.amount.amountMinor),
            status: 'PARTIAL',
          },
          payments: [
            {
              id: body.operationId,
              amount: body.amount,
              paidOn: body.paidOn,
              method: body.method,
              note: body.note,
              reversesId: null,
              reversed: false,
              actor: 'admin@studio.pl',
              createdAt: '2026-11-27T12:00:00Z',
            },
          ],
        };
        return response(current);
      }
    }
    if (url.startsWith('/api/v1/trainer-settlements?'))
      return response(created ? [current.summary] : []);
    if (url === '/api/v1/trainer-settlements/settlement1') return response(current);
    if (url.startsWith('/api/v1/trainer-settlements/export?')) return response([current]);
    throw Error(`Unexpected request ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, writes };
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-27T12:00:00Z'));
});
afterEach(() => vi.useRealTimers());

test('shows saved balances and hours without reading the current schedule', async () => {
  const { fetchMock } = setup();
  renderPage();
  const user = userEvent.setup();
  expect(
    await screen.findByRole('tab', { name: 'Rozliczenia', selected: true }),
  ).toBeInTheDocument();
  expect(screen.queryByText('Wersja 1')).not.toBeInTheDocument();
  await user.click(await screen.findByRole('button', { name: 'Otwórz: Marek Nowak' }));
  const drawer = await screen.findByRole('dialog', { name: 'Marek Nowak' });
  await user.click(within(drawer).getByRole('tab', { name: 'Zapisane godziny' }));
  expect(await within(drawer).findByText('Zapisana konsultacja')).toBeInTheDocument();
  expect(within(drawer).getByText('30 min bez potwierdzenia — pominięte.')).toBeInTheDocument();
  expect(
    fetchMock.mock.calls.some(([url]) => url.startsWith('/api/v1/reports/trainer-hours')),
  ).toBe(false);
  await user.click(within(drawer).getByRole('tab', { name: 'Historia zmian' }));
  expect(await within(drawer).findByText('Utworzenie rozliczenia')).toBeInTheDocument();
});

test('requires reviewing changed hours before approval and preserves the operation ID on retry', async () => {
  const { writes } = setup({ empty: true, stalePreview: true });
  renderPage();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Nowe rozliczenie' }));
  const form = await screen.findByRole('dialog', { name: 'Nowe rozliczenie' });
  expect(within(form).getByText('Październik 2026')).toBeInTheDocument();
  expect(within(form).queryByLabelText('Kwota za miesiąc (PLN)')).not.toBeInTheDocument();
  await user.click(within(form).getByRole('combobox', { name: 'Pracownik' }));
  await user.click(await screen.findByRole('option', { name: 'Marek Nowak', hidden: true }));
  await within(form).findByLabelText('Dolicz 30 min bez potwierdzenia');
  expect(within(form).getByRole('button', { name: 'Zapisz rozliczenie' })).toBeDisabled();
  await user.type(within(form).getByLabelText('Kwota za miesiąc (PLN)'), '100,25');
  await user.click(within(form).getByLabelText('Dolicz 30 min bez potwierdzenia'));
  await user.click(within(form).getByRole('button', { name: 'Zapisz rozliczenie' }));
  expect(await within(form).findByRole('alert')).toHaveTextContent(
    'Godziny w grafiku się zmieniły',
  );
  await user.click(within(form).getByRole('button', { name: 'Odśwież' }));
  await waitFor(() =>
    expect(within(form).getByRole('button', { name: 'Zapisz rozliczenie' })).toBeEnabled(),
  );
  await user.click(within(form).getByRole('button', { name: 'Zapisz rozliczenie' }));
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes[1].body).toMatchObject({
    amount: money(10025),
    includeUnconfirmed: true,
    fingerprint: 'fresh',
  });
  expect(writes[0].body.operationId).toBe(writes[1].body.operationId);
});

test('records a partial payout in exact cents and safely retries an uncertain network response', async () => {
  const { writes } = setup({ failFirstPayout: true });
  renderPage();
  const user = userEvent.setup();
  await user.click(await screen.findByRole('button', { name: 'Otwórz: Marek Nowak' }));
  await user.click(await screen.findByRole('button', { name: 'Zapisz wypłatę' }));
  const form = await screen.findByRole('dialog', { name: 'Dodaj wpis wypłaty' });
  const amount = within(form).getByLabelText('Kwota wypłaty (PLN)');
  await user.clear(amount);
  await user.type(amount, '2,50');
  await user.click(within(form).getByRole('button', { name: 'Zapisz wypłatę' }));
  expect(await within(form).findByRole('alert')).toHaveTextContent(
    'Możesz bezpiecznie ponowić próbę',
  );
  await user.click(within(form).getByRole('button', { name: 'Zapisz wypłatę' }));
  await waitFor(() => expect(writes).toHaveLength(2));
  expect(writes[0].body.operationId).toBe(writes[1].body.operationId);
  expect(writes[1].body.amount).toEqual(money(250));
  expect(
    await within(screen.getByRole('dialog', { name: 'Marek Nowak' })).findByText(
      'Częściowo wypłacone',
    ),
  ).toBeInTheDocument();
});

test('does not request an empty or future month', async () => {
  const { fetchMock } = setup();
  renderPage();
  await screen.findByRole('button', { name: 'Otwórz: Marek Nowak' });
  const before = fetchMock.mock.calls.length;
  fireEvent.change(screen.getByLabelText('Miesiąc'), { target: { value: '' } });
  expect(screen.getByLabelText('Miesiąc')).toHaveValue('');
  expect(await screen.findByText('Wybierz bieżący lub wcześniejszy miesiąc.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Nowe rozliczenie' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'CSV' })).toBeDisabled();
  expect(fetchMock.mock.calls).toHaveLength(before);
  fireEvent.change(screen.getByLabelText('Miesiąc'), { target: { value: '2026-12' } });
  expect(await screen.findByText('Wybierz bieżący lub wcześniejszy miesiąc.')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Nowe rozliczenie' })).toBeDisabled();
  expect(fetchMock.mock.calls).toHaveLength(before);
});

test('money parsing preserves cents and rejects fractions of a cent, negatives and oversized amounts', () => {
  expect(settlementAmount('1234,56')).toBe(123456);
  expect(settlementAmount('99999999.99')).toBe(9999999999);
  for (const value of ['', '-1', '1.234', '1,2.3', '100000000', '1e3', 'NaN'])
    expect(settlementAmount(value)).toBeNull();
});

test('settlement exports use approved snapshots, exact amounts and monochrome documents', () => {
  const csv = settlementsCsv([fixture], '2026-11-27T12:00:00Z');
  expect(csv).toContain(
    '"2026-10";"Marek Nowak";"1,000000";"3600";"Nie";"100,00";"0,00";"100,00";"PLN"',
  );
  const pdf = settlementsPdf([fixture], '2026-11-27T12:00:00Z', true);
  const content = JSON.stringify(pdf);
  expect(content).toContain('Zapisana konsultacja');
  expect(content).toContain('Pominięte (bez potwierdzenia)');
  expect(content).toContain('Utworzenie rozliczenia');
  expect(content).not.toContain('Wersja');
  expect(content).not.toContain('Zestawienie zapisanych rozliczeń');
  expect(content).not.toContain('fillColor');
  for (const color of content.match(/#[0-9a-f]{6}/gi) ?? []) {
    expect(color.slice(1, 3)).toBe(color.slice(3, 5));
    expect(color.slice(3, 5)).toBe(color.slice(5, 7));
  }
});
