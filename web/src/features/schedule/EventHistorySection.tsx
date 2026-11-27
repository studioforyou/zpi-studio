import { useState, type ReactNode } from 'react';
import { Accordion, Alert, Button, Loader, Stack, Table, Text, Timeline } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  getEventHistory,
  scheduleKeys,
  type EventHistoryChanges,
  type AssignedStaff,
  type AssignedResource,
} from './scheduleApi';
import { formatDateTime, formatTimeRange } from './scheduleFormat';
import { formatDate, toZoned } from './studioTime';

export function EventHistorySection({ eventId, timeZone }: { eventId: string; timeZone: string }) {
  const texts = pl.schedule.eventHistory;
  const [opened, setOpened] = useState(false);
  const [visible, setVisible] = useState(5);
  const history = useQuery({
    queryKey: scheduleKeys.history(eventId),
    queryFn: () => getEventHistory(eventId),
    enabled: opened,
  });

  return (
    <Accordion
      variant="contained"
      value={opened ? 'history' : null}
      onChange={(value) => setOpened(value !== null)}
    >
      <Accordion.Item value="history">
        <Accordion.Control>{texts.title}</Accordion.Control>
        <Accordion.Panel>
          {history.isPending && <Loader size="sm" aria-label={texts.loading} />}
          {history.isError && (
            <Alert color="red">
              <Stack gap="xs">
                <Text size="sm">{texts.error}</Text>
                <Button variant="subtle" size="xs" onClick={() => void history.refetch()}>
                  {texts.retry}
                </Button>
              </Stack>
            </Alert>
          )}
          {history.data?.length === 0 && (
            <Text size="sm" c="dimmed">
              {texts.empty}
            </Text>
          )}
          {history.data && history.data.length > 0 && (
            <Stack gap="sm">
              <Timeline bulletSize={10} lineWidth={1}>
                {history.data.slice(0, visible).map((entry) => (
                  <Timeline.Item
                    key={entry.id}
                    title={
                      <Text size="sm" fw={600}>
                        {texts.kinds[entry.kind]}
                      </Text>
                    }
                  >
                    <Text size="xs" c="dimmed" mb="xs">
                      {formatDateTime(entry.occurredAt, timeZone)} ·{' '}
                      {entry.actorName ??
                        (entry.actorAccountId ? texts.unknownActor : texts.system)}
                    </Text>
                    <Changes changes={entry.changes} timeZone={timeZone} />
                    {entry.reason && (
                      <Text size="sm" mt="xs">
                        {entry.reason}
                      </Text>
                    )}
                  </Timeline.Item>
                ))}
              </Timeline>
              {history.data.length > visible && (
                <Button variant="subtle" size="xs" onClick={() => setVisible((count) => count + 5)}>
                  {texts.more}
                </Button>
              )}
            </Stack>
          )}
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  );
}

function Changes({ changes, timeZone }: { changes: EventHistoryChanges; timeZone: string }) {
  const texts = pl.schedule.eventHistory;
  const rows: { label: string; before: ReactNode; after: ReactNode }[] = [];
  function add<T>(
    label: string,
    change: { old: T; new: T } | undefined,
    value: (input: T) => ReactNode,
  ) {
    if (change) rows.push({ label, before: value(change.old), after: value(change.new) });
  }
  add(texts.fields.time, changes.time, (value) => <EventTime value={value} timeZone={timeZone} />);
  add(texts.fields.resources, changes.resources, (value) => <Rooms members={value} />);
  add(texts.fields.staff, changes.staff, (value) => <Staff members={value} />);
  add(texts.fields.title, changes.title, (value) => value ?? texts.none);
  add(texts.fields.capacity, changes.capacity, (value) => value ?? texts.unlimited);
  add(texts.fields.status, changes.status, (value) => pl.schedule.statuses[value]);
  add(texts.fields.visibility, changes.visibility, (value) => pl.schedule.visibilities[value]);
  add(texts.fields.guestName, changes.guestName, (value) => value ?? texts.none);
  add(texts.fields.blockReason, changes.blockReason, (value) => value ?? texts.none);
  add(texts.fields.forceRun, changes.forceRun, (value) =>
    value ? texts.forceRun : texts.automaticDecision,
  );
  if (rows.length === 0) return null;
  return (
    <Table
      horizontalSpacing={4}
      verticalSpacing={4}
      withRowBorders={false}
      style={{ tableLayout: 'fixed' }}
      aria-label={texts.changedValues}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th w="22%">
            <Text size="xs" c="dimmed">
              {texts.field}
            </Text>
          </Table.Th>
          <Table.Th>
            <Text size="xs" c="dimmed">
              {texts.before}
            </Text>
          </Table.Th>
          <Table.Th>
            <Text size="xs" c="dimmed">
              {texts.after}
            </Text>
          </Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {rows.map((row) => (
          <Table.Tr key={row.label}>
            <Table.Td style={{ verticalAlign: 'top' }}>
              <Text size="xs" c="dimmed">
                {row.label}
              </Text>
            </Table.Td>
            <Table.Td style={{ verticalAlign: 'top', overflowWrap: 'anywhere' }}>
              <Text component="div" size="sm">
                {row.before}
              </Text>
            </Table.Td>
            <Table.Td style={{ verticalAlign: 'top', overflowWrap: 'anywhere' }}>
              <Text component="div" size="sm">
                {row.after}
              </Text>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function EventTime({
  value,
  timeZone,
}: {
  value: { startsAt: string; endsAt: string };
  timeZone: string;
}) {
  const start = toZoned(value.startsAt, timeZone).date;
  const end = toZoned(value.endsAt, timeZone).date;
  return (
    <>
      <Text size="xs" c="dimmed">
        {formatDate(start)}
        {start !== end ? `–${formatDate(end)}` : ''}
      </Text>
      <Text size="sm">{formatTimeRange(value.startsAt, value.endsAt, timeZone)}</Text>
    </>
  );
}

function Staff({ members }: { members: AssignedStaff[] }) {
  return members.length === 0
    ? pl.schedule.eventHistory.none
    : members.map((member) => (
        <Text key={member.staffId} size="sm">
          {member.displayName ?? pl.schedule.unknownStaff}
          <Text component="span" size="xs" c="dimmed">
            {' '}
            · {pl.schedule.roles[member.role]}
          </Text>
        </Text>
      ));
}

function Rooms({ members }: { members: AssignedResource[] }) {
  return members.length === 0
    ? pl.schedule.eventHistory.none
    : members.map((room) => (
        <Text key={room.resourceId} size="sm">
          {room.name}
          <Text component="span" size="xs" c="dimmed">
            {' '}
            · {room.blocksWholeRoom ? pl.schedule.wholeRoom : pl.schedule.sharedRoom}
          </Text>
        </Text>
      ));
}
