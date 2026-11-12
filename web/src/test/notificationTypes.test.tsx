import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { CommunicationPage } from '@/features/communication/CommunicationPage';
import { formatLeadTime, toLeadTimeValue } from '@/features/communication/notifications/leadTime';
import { renderWithProviders } from './renderWithProviders';

const reminder = {
  code: 'CLASS_REMINDER',
  enabled: true,
  timed: true,
  leadTimesMinutes: [1440, 120],
  title: 'Przypomnienie o zajęciach',
  body: '{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}.',
  placeholders: [
    { name: 'zajęcia', description: 'Name of the class or training' },
    { name: 'dzień', description: 'Day' },
    { name: 'godzina', description: 'Time' },
    { name: 'sala', description: 'Room' },
    { name: 'ile przed', description: 'Lead time' },
  ],
  updatedAt: null,
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json',
    },
  });
}

function stubApi() {
  let current = { ...reminder };
  const patches: Record<string, unknown>[] = [];
  const fetchMock = vi.fn(async (url: string, init: RequestInit) => {
    if (url === '/api/v1/notification-types' && init.method === 'GET') {
      return json({ items: [current] });
    }
    if (url === '/api/v1/notification-types/CLASS_REMINDER' && init.method === 'PATCH') {
      const patch = JSON.parse(init.body as string);
      patches.push(patch);
      current = { ...current, ...patch };
      return json(current);
    }
    throw new Error(`Unexpected request: ${init.method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return patches;
}

test('formats reminder times in the largest whole unit', () => {
  expect(formatLeadTime(1440)).toBe('1 dzień');
  expect(formatLeadTime(2880)).toBe('2 dni');
  expect(formatLeadTime(120)).toBe('2 godz.');
  expect(formatLeadTime(90)).toBe('1 godz. 30 min');
  expect(formatLeadTime(45)).toBe('45 min');
  expect(toLeadTimeValue(1440)).toEqual({ amount: 1, unit: 'DAYS' });
  expect(toLeadTimeValue(90)).toEqual({ amount: 90, unit: 'MINUTES' });
});

test('shows each notification type with its reminder times and switches it off', async () => {
  const patches = stubApi();
  renderWithProviders(<CommunicationPage />);
  expect(screen.getByRole('tab', { name: 'Powiadomienia' })).toHaveAttribute(
    'aria-selected',
    'true',
  );
  expect(await screen.findByText('Przypomnienie o zajęciach')).toBeInTheDocument();
  expect(
    screen.getByText('{{zajęcia}}: {{dzień}}, godz. {{godzina}}. Sala: {{sala}}.'),
  ).toBeInTheDocument();
  expect(screen.getByText(/Powiadomienia dziecka dostaje rodzic\./)).toBeInTheDocument();
  expect(screen.getByText('1 dzień przed')).toBeInTheDocument();
  expect(screen.getByText('2 godz. przed')).toBeInTheDocument();
  const toggle = screen.getByRole('switch', { name: 'Wysyłane: Przypomnienie o zajęciach' });
  expect(toggle).toBeChecked();
  await userEvent.click(toggle);
  expect(patches).toEqual([{ enabled: false }]);
  expect(await screen.findByRole('switch', { name: /^Wysyłane/ })).not.toBeChecked();
});

test('edits reminder times and content, and rejects unknown placeholders', async () => {
  const patches = stubApi();
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  const preview = within(dialog).getByLabelText('Podgląd');
  expect(
    within(preview).getByText('Pole dance: jutro, godz. 18:00. Sala: Sala 1.'),
  ).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Usuń przypomnienie 2' }));
  await userEvent.click(within(dialog).getByRole('button', { name: 'Dodaj przypomnienie' }));
  const amount = within(dialog).getByRole('textbox', { name: 'Przypomnienie 2: ile' });
  await userEvent.clear(amount);
  await userEvent.type(amount, '30');
  await userEvent.click(
    within(dialog).getByRole('combobox', { name: 'Przypomnienie 2: jednostka' }),
  );
  // Each unit select has its own options; the new reminder's select is the last one.
  const minutes = await screen.findAllByRole('option', { name: 'minut', hidden: true });
  await userEvent.click(minutes.at(-1)!);

  const body = within(dialog).getByRole('textbox', { name: /^Treść/ });
  await userEvent.clear(body);
  await userEvent.click(body);
  await userEvent.paste('{{zajęcia}} {{cena}}');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(
    within(dialog).getByText('Nieznane pole {{cena}}. Wstaw pole przyciskiem poniżej.'),
  ).toBeInTheDocument();
  expect(patches).toEqual([]);

  // The buttons insert a field where the cursor is.
  await userEvent.clear(body);
  await userEvent.click(body);
  await userEvent.paste('Za : ');
  (body as HTMLTextAreaElement).setSelectionRange(3, 3);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wstaw: Ile czasu przed' }));
  const end = (body as HTMLTextAreaElement).value.length;
  (body as HTMLTextAreaElement).setSelectionRange(end, end);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wstaw: Nazwa zajęć' }));
  expect(body).toHaveValue('Za {{ile przed}}: {{zajęcia}}');
  expect(within(preview).getByText('Za 30 min: Pole dance')).toBeInTheDocument();

  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(patches).toEqual([
    {
      title: 'Przypomnienie o zajęciach',
      body: 'Za {{ile przed}}: {{zajęcia}}',
      leadTimesMinutes: [1440, 30],
    },
  ]);
  expect(await screen.findByText('30 min przed')).toBeInTheDocument();
});

test('inserts a field into the title when the title was used last', async () => {
  stubApi();
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  const title = within(dialog).getByRole('textbox', { name: /^Tytuł/ });
  await userEvent.click(title);
  (title as HTMLInputElement).setSelectionRange(0, 0);
  await userEvent.click(within(dialog).getByRole('button', { name: 'Wstaw: Godzina' }));
  expect(title).toHaveValue('{{godzina}}Przypomnienie o zajęciach');
  expect(within(dialog).getByText('18:00Przypomnienie o zajęciach')).toBeInTheDocument();
});

test('does not save duplicate reminder times', async () => {
  const patches = stubApi();
  renderWithProviders(<CommunicationPage />);
  await userEvent.click(await screen.findByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  const amount = within(dialog).getByRole('textbox', { name: 'Przypomnienie 2: ile' });
  await userEvent.clear(amount);
  await userEvent.type(amount, '24');
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(within(dialog).getByText('Przypomnienia muszą mieć różne terminy.')).toBeInTheDocument();
  expect(patches).toEqual([]);
});

test('counts a pass reminder back from 9:00 on the last day and previews pass fields', async () => {
  const expiring = {
    code: 'PASS_EXPIRING',
    enabled: true,
    timed: true,
    leadTimesMinutes: [4320, 1440],
    title: 'Karnet wkrótce wygaśnie',
    body: '{{karnet}} jest ważny do {{ważny do}}.',
    placeholders: [
      { name: 'karnet', description: 'Name of the pass' },
      { name: 'ważny do', description: 'Last day' },
      { name: 'ile przed', description: 'Lead time' },
    ],
    updatedAt: null,
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/notification-types' && init.method === 'GET') {
        return json({ items: [expiring] });
      }
      throw new Error(`Unexpected request: ${init.method} ${url}`);
    }),
  );
  renderWithProviders(<CommunicationPage />);
  expect(await screen.findByText('Karnet wkrótce wygaśnie')).toBeInTheDocument();
  expect(screen.getByText('3 dni przed')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getAllByText('przed 9:00 ostatniego dnia')).toHaveLength(2);
  expect(
    within(dialog).getByText(/przed godziną 9:00 ostatniego dnia ważności/),
  ).toBeInTheDocument();
  const preview = within(dialog).getByLabelText('Podgląd');
  expect(within(preview).getByText('Karnet 8 wejść jest ważny do 12.11.2026.')).toBeInTheDocument();
});

test('shows the new message notification without reminder times and previews message fields', async () => {
  const newMessage = {
    code: 'NEW_MESSAGE',
    enabled: true,
    timed: false,
    leadTimesMinutes: [],
    title: '{{od}}',
    body: '{{ile}} · {{treść}}',
    placeholders: [
      { name: 'od', description: 'Senders' },
      { name: 'ile', description: 'How many' },
      { name: 'rozmowa', description: 'Conversation' },
      { name: 'treść', description: 'Message' },
    ],
    updatedAt: null,
  };
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/notification-types' && init.method === 'GET') {
        return json({ items: [newMessage] });
      }
      throw new Error(`Unexpected request: ${init.method} ${url}`);
    }),
  );
  renderWithProviders(<CommunicationPage />);
  expect(await screen.findByText('Nowa wiadomość')).toBeInTheDocument();
  expect(
    screen.getByText(/Wiadomości z czatu grupowego dziecka dostaje rodzic\./),
  ).toBeInTheDocument();
  expect(screen.queryByText(/przed$/)).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Edytuj' }));
  const dialog = await screen.findByRole('dialog');
  expect(
    within(dialog).queryByRole('button', { name: 'Dodaj przypomnienie' }),
  ).not.toBeInTheDocument();
  const preview = within(dialog).getByLabelText('Podgląd');
  expect(within(preview).getByText('Pole dance · Anna Wiśniewska, Ewa Lis')).toBeInTheDocument();
  expect(
    within(preview).getByText('3 nowe wiadomości · Weźcie wodę i ręcznik.'),
  ).toBeInTheDocument();
});

test('configures both waiting-list messages and edits the deadline template without reminder times', async () => {
  const offer = {
    ...reminder,
    code: 'WAITLIST_PLACE_OFFERED',
    timed: false,
    leadTimesMinutes: [],
    title: 'Zwolniło się miejsce',
    body: '{{zajęcia}}. Potwierdź miejsce do {{potwierdź do}}.',
    placeholders: [
      { name: 'zajęcia', description: 'Class' },
      { name: 'dzień', description: 'Day' },
      { name: 'godzina', description: 'Time' },
      { name: 'potwierdź do', description: 'Deadline' },
    ],
  };
  const types = [
    offer,
    { ...offer, code: 'WAITLIST_PLACE_GRANTED', title: 'Masz miejsce na zajęciach' },
  ];
  const patches: Record<string, unknown>[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init: RequestInit) => {
      if (url === '/api/v1/notification-types') return json({ items: types });
      const code = url.split('/').at(-1);
      const index = types.findIndex((type) => type.code === code);
      if (index !== -1 && init.method === 'PATCH') {
        const patch = JSON.parse(init.body as string);
        patches.push(patch);
        types[index] = { ...types[index], ...patch };
        return json(types[index]);
      }
      throw new Error(`Unexpected request: ${init.method} ${url}`);
    }),
  );
  renderWithProviders(<CommunicationPage />);
  expect(await screen.findByText('Miejsce z listy — do potwierdzenia')).toBeInTheDocument();
  expect(screen.getByText('Zapis z listy rezerwowej')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('switch', { name: 'Wysyłane: Zapis z listy rezerwowej' }));
  expect(patches).toEqual([{ enabled: false }]);
  await userEvent.click(screen.getAllByRole('button', { name: 'Edytuj' })[0]);
  const dialog = await screen.findByRole('dialog');
  expect(
    within(dialog).queryByRole('button', { name: 'Dodaj przypomnienie' }),
  ).not.toBeInTheDocument();
  expect(
    within(dialog).getByRole('button', { name: 'Wstaw: Termin potwierdzenia' }),
  ).toBeInTheDocument();
  const body = within(dialog).getByRole('textbox', { name: /^Treść/ });
  await userEvent.clear(body);
  await userEvent.click(body);
  await userEvent.paste('Potwierdź do {{potwierdź do}}');
  expect(
    within(within(dialog).getByLabelText('Podgląd')).getByText('Potwierdź do 13.11, 15:30'),
  ).toBeInTheDocument();
  await userEvent.click(within(dialog).getByRole('button', { name: 'Zapisz' }));
  expect(patches[1]).toEqual({
    title: 'Zwolniło się miejsce',
    body: 'Potwierdź do {{potwierdź do}}',
  });
});
