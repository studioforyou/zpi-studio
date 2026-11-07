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
