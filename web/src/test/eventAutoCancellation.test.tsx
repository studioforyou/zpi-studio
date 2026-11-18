import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import type { EventAutoCancellation } from '@/features/schedule/autoCancellationApi';
import { EventAutoCancellationSection } from '@/features/schedule/EventAutoCancellationSection';
import type { ScheduleEvent } from '@/features/schedule/scheduleApi';
import type { ScheduleLookups } from '@/features/schedule/useScheduleLookups';
import { renderWithProviders } from './renderWithProviders';

const event: ScheduleEvent = {
  id: 'pole-class',
  kind: 'CLASS',
  status: 'SCHEDULED',
  visibility: 'PUBLIC',
  title: 'Pole dance',
  serviceTypeId: 'pole',
  serviceTypeName: 'Pole dance - początkujący',
  seriesId: null,
  isSeriesException: false,
  seriesChange: null,
  trainingGroupId: 'group-tue',
  startsAt: '2030-11-03T17:00:00Z',
  endsAt: '2030-11-03T18:00:00Z',
  capacity: 10,
  staff: [],
  resources: [],
  cancelReason: null,
  blockReason: null,
  guestName: null,
  version: 1,
};

const lookups: ScheduleLookups = {
  timeZone: 'Europe/Warsaw',
  staff: [],
  staffById: new Map(),
  serviceTypes: [],
  serviceTypeById: new Map(),
  rooms: [],
  resourceById: new Map(),
  groups: [{ id: 'group-tue', name: 'Pole wt 18:00', serviceTypeId: 'pole', isActive: true }],
  isError: false,
};

const pending: EventAutoCancellation = {
  eventId: event.id,
  state: 'PENDING',
  ruleEnabled: true,
  forceRun: false,
  trainingGroupId: 'group-tue',
  disabledForGroup: false,
  participants: 2,
  minimumParticipants: 3,
  checkAt: '2030-11-03T14:00:00Z',
};

// Mantine names a switch by its label and its description.
const forceSwitch = /^Wymuś odbycie się zajęć/;
const groupSwitch = /^Wyłącz automatyczne odwołanie dla grupy Pole wt 18:00/;

const eventUrl = '/api/v1/schedule-events/pole-class/auto-cancellation';
const groupUrl = '/api/v1/training-groups/group-tue/auto-cancellation';

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function stubApi(answers: {
  get: () => Response;
  putEvent?: (body: { forceRun: boolean }) => Response;
  putGroup?: (body: { disabled: boolean }) => Response;
}) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    if (method === 'GET' && url === eventUrl) return answers.get();
    if (method === 'PUT' && url === eventUrl && answers.putEvent) return answers.putEvent(body);
    if (method === 'PUT' && url === groupUrl && answers.putGroup) return answers.putGroup(body);
    throw new Error(`Unexpected request: ${method} ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

function requests(fetchMock: ReturnType<typeof stubApi>, method: string, url: string) {
  return fetchMock.mock.calls.filter(
    ([callUrl, init]) => callUrl === url && (init?.method ?? 'GET') === method,
  );
}

test('shows when the participants are counted and warns about too few of them', async () => {
  stubApi({ get: () => json(pending) });
  renderWithProviders(<EventAutoCancellationSection event={event} lookups={lookups} />);

  expect(await screen.findByText('Zapisanych: 2, wymagane minimum: 3')).toBeInTheDocument();
  expect(screen.getByText(/zostanie sprawdzona 03\.11\.2030 15:00/)).toBeInTheDocument();
  expect(
    screen.getByText(
      'Na razie zapisanych jest za mało – zajęcia zostaną odwołane przy sprawdzeniu.',
    ),
  ).toBeInTheDocument();
  expect(screen.getByRole('switch', { name: forceSwitch })).not.toBeChecked();
  expect(screen.getByRole('switch', { name: groupSwitch })).not.toBeChecked();
});

test('forces the class to run and lets the rule decide again', async () => {
  let current = pending;
  const fetchMock = stubApi({
    get: () => json(current),
    putEvent: ({ forceRun }) => {
      current = { ...pending, forceRun, state: forceRun ? 'FORCED' : 'PENDING' };
      return json(current);
    },
  });
  renderWithProviders(<EventAutoCancellationSection event={event} lookups={lookups} />);

  await userEvent.click(await screen.findByRole('switch', { name: forceSwitch }));

  expect(
    await screen.findByText('Zajęcia odbędą się bez względu na liczbę zapisanych.'),
  ).toBeInTheDocument();
  expect(screen.getByRole('switch', { name: forceSwitch })).toBeChecked();
  expect(screen.queryByText(/zostaną odwołane przy sprawdzeniu/)).not.toBeInTheDocument();
  const [[, init]] = requests(fetchMock, 'PUT', eventUrl);
  expect(JSON.parse(String(init!.body))).toEqual({ forceRun: true });

  await userEvent.click(screen.getByRole('switch', { name: forceSwitch }));
  expect(await screen.findByText(/zostanie sprawdzona/)).toBeInTheDocument();
  expect(JSON.parse(String(requests(fetchMock, 'PUT', eventUrl)[1][1]!.body))).toEqual({
    forceRun: false,
  });
});

test('switches the rule off for the whole group', async () => {
  let current = pending;
  const fetchMock = stubApi({
    get: () => json(current),
    putGroup: ({ disabled }) => {
      current = {
        ...pending,
        state: 'OFF',
        ruleEnabled: false,
        disabledForGroup: disabled,
        minimumParticipants: null,
        checkAt: null,
      };
      return json({ trainingGroupId: 'group-tue', disabled });
    },
  });
  renderWithProviders(<EventAutoCancellationSection event={event} lookups={lookups} />);

  await userEvent.click(
    await screen.findByRole('switch', {
      name: groupSwitch,
    }),
  );

  expect(
    await screen.findByText(
      'Reguła automatycznego odwołania jest wyłączona dla grupy Pole wt 18:00.',
    ),
  ).toBeInTheDocument();
  expect(screen.getByRole('switch', { name: groupSwitch })).toBeChecked();
  // Forcing a class the rule leaves alone anyway is pointless.
  expect(screen.queryByRole('switch', { name: forceSwitch })).not.toBeInTheDocument();
  const [[, init]] = requests(fetchMock, 'PUT', groupUrl);
  expect(JSON.parse(String(init!.body))).toEqual({ disabled: true });
  expect(requests(fetchMock, 'GET', eventUrl)).toHaveLength(2);
});

test('offers no switch when the rule does not apply to the class', async () => {
  stubApi({
    get: () =>
      json({
        ...pending,
        state: 'OFF',
        ruleEnabled: false,
        trainingGroupId: null,
        minimumParticipants: null,
        checkAt: null,
      }),
  });
  renderWithProviders(
    <EventAutoCancellationSection event={{ ...event, trainingGroupId: null }} lookups={lookups} />,
  );

  expect(
    await screen.findByText('Reguła automatycznego odwołania nie obowiązuje dla tych zajęć.'),
  ).toBeInTheDocument();
  expect(screen.queryByRole('switch')).not.toBeInTheDocument();
  expect(screen.queryByText(/wymagane minimum/)).not.toBeInTheDocument();
});

test('explains why a class that is no longer scheduled cannot be forced', async () => {
  stubApi({
    get: () => json(pending),
    putEvent: () => json({ status: 422, code: 'EVENT_NOT_EDITABLE', detail: 'Not editable.' }, 422),
  });
  renderWithProviders(<EventAutoCancellationSection event={event} lookups={lookups} />);

  await userEvent.click(await screen.findByRole('switch', { name: forceSwitch }));

  expect(
    await screen.findByText(
      'Zajęcia są już odwołane, odbyte albo się zaczęły. Wymusić można tylko zaplanowane zajęcia przed startem.',
    ),
  ).toBeInTheDocument();
});
