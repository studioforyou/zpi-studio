import { Badge, Box, Group, Stack, Table, Text, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { ScheduleEvent } from './scheduleApi';
import { staffingGapsOf } from './scheduleFilters';
import { eventColor, eventTitle, formatTimeRange, type ColorBy } from './scheduleFormat';
import { formatDayLong, toZoned } from './studioTime';
import type { ScheduleLookups } from './useScheduleLookups';

type AgendaViewProps = {
  events: ScheduleEvent[];
  lookups: ScheduleLookups;
  colorBy: ColorBy;
  onSelect: (event: ScheduleEvent) => void;
};

const statusColors = { SCHEDULED: 'blue', COMPLETED: 'green', CANCELLED: 'gray' } as const;

/** Events as a table per day, with who runs them and in which rooms. */
export function AgendaView({ events, lookups, colorBy, onSelect }: AgendaViewProps) {
  const texts = pl.schedule;
  const byDay = new Map<string, ScheduleEvent[]>();
  for (const event of events) {
    const day = toZoned(event.startsAt, lookups.timeZone).date;
    byDay.set(day, [...(byDay.get(day) ?? []), event]);
  }
  return (
    <Stack gap="lg">
      {[...byDay.entries()].map(([day, dayEvents]) => (
        <Stack key={day} gap="xs">
          <Title order={4}>{formatDayLong(day)}</Title>
          <Table highlightOnHover striped layout="fixed">
            <Table.Thead>
              <Table.Tr>
                <Table.Th w={110}>{texts.list.time}</Table.Th>
                <Table.Th w="34%">{texts.list.event}</Table.Th>
                <Table.Th>{texts.list.staff}</Table.Th>
                <Table.Th>{texts.list.resources}</Table.Th>
                <Table.Th w={80}>{texts.list.capacity}</Table.Th>
                <Table.Th w={140}>{texts.list.status}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {dayEvents.map((event) => {
                const gaps = staffingGapsOf(event);
                return (
                  <Table.Tr
                    key={event.id}
                    onClick={() => onSelect(event)}
                    style={{ cursor: 'pointer' }}
                  >
                    <Table.Td>
                      {formatTimeRange(event.startsAt, event.endsAt, lookups.timeZone)}
                    </Table.Td>
                    <Table.Td>
                      <Group gap={6} wrap="nowrap">
                        <Box
                          w={10}
                          h={10}
                          style={{
                            borderRadius: 5,
                            flexShrink: 0,
                            background: eventColor(
                              event,
                              colorBy,
                              lookups.serviceTypeById,
                              lookups.staffById,
                            ),
                          }}
                        />
                        <Stack gap={0}>
                          <Text size="sm" fw={500}>
                            {eventTitle(event)}
                          </Text>
                          <Group gap={4}>
                            <Text size="xs" c="dimmed">
                              {texts.kinds[event.kind]}
                            </Text>
                            {event.seriesId && (
                              <Badge size="xs" variant="light">
                                {event.isSeriesException ? texts.exception : texts.recurring}
                              </Badge>
                            )}
                            {event.visibility === 'PRIVATE' && (
                              <Badge size="xs" variant="light" color="gray">
                                {texts.visibilities.PRIVATE}
                              </Badge>
                            )}
                          </Group>
                        </Stack>
                      </Group>
                    </Table.Td>
                    <Table.Td>
                      {event.staff.length > 0 ? (
                        event.staff
                          .map((member) =>
                            member.role === 'ASSISTANT'
                              ? `${member.displayName} (${texts.roles.ASSISTANT.toLowerCase()})`
                              : member.displayName,
                          )
                          .join(', ')
                      ) : gaps.includes('NO_STAFF') ? (
                        <Text size="sm" c="red.7">
                          {texts.noStaff}
                        </Text>
                      ) : (
                        '—'
                      )}
                    </Table.Td>
                    <Table.Td>
                      {event.resources.length > 0 ? (
                        event.resources.map((resource) => resource.name).join(', ')
                      ) : gaps.includes('NO_ROOM') ? (
                        <Text size="sm" c="red.7">
                          {texts.noRoom}
                        </Text>
                      ) : (
                        '—'
                      )}
                    </Table.Td>
                    <Table.Td>{event.capacity ?? '—'}</Table.Td>
                    <Table.Td>
                      <Badge color={statusColors[event.status]} variant="light">
                        {texts.statuses[event.status]}
                      </Badge>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Stack>
      ))}
    </Stack>
  );
}
