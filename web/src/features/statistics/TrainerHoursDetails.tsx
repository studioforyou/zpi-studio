import { useState } from 'react';
import { Badge, Drawer, Group, Pagination, Stack, Table, Text } from '@mantine/core';
import { formatDate, formatTime, toZoned } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { formatWorkedTime } from './statisticsMath';
import type { InstructorHours, TrainerHoursReport } from './trainerHoursApi';

const texts = pl.statistics.hours;
const PAGE_SIZE = 15;

/** The exact occurrences behind one instructor's total, newest first. */
export function TrainerHoursDetails({
  instructor,
  report,
  onClose,
}: {
  instructor: InstructorHours;
  report: TrainerHoursReport;
  onClose: () => void;
}) {
  return (
    <Drawer opened onClose={onClose} position="right" size="xl" title={instructor.displayName}>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <Stack gap={2}>
            <Text size="sm" c="dimmed">
              {formatDate(report.from)} – {formatDate(report.to)}
            </Text>
            <Text size="xl" fw={600}>
              {formatWorkedTime(instructor.counts.seconds)}
            </Text>
          </Stack>
          <Group gap="xs">
            <Badge variant="light" color="teal">
              {texts.lead}: {formatWorkedTime(instructor.counts.leadSeconds)}
            </Badge>
            <Badge variant="light" color="blue">
              {texts.assistant}: {formatWorkedTime(instructor.counts.assistantSeconds)}
            </Badge>
          </Group>
        </Group>
        <TrainerHoursSessions instructor={instructor} report={report} />
      </Stack>
    </Drawer>
  );
}

/** The same session list for live hours and the immutable hours saved in a settlement. */
export function TrainerHoursSessions({
  instructor,
  report,
}: {
  instructor: InstructorHours;
  report: TrainerHoursReport;
}) {
  const [page, setPage] = useState(1);
  const pages = Math.max(1, Math.ceil(instructor.sessions.length / PAGE_SIZE));
  const current = Math.min(page, pages);
  const sessions = instructor.sessions.slice((current - 1) * PAGE_SIZE, current * PAGE_SIZE);
  return (
    <Stack gap="md">
      {sessions.length === 0 ? (
        <Text c="dimmed">{texts.emptyInstructor}</Text>
      ) : (
        <Table.ScrollContainer minWidth={640}>
          <Table verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{texts.date}</Table.Th>
                <Table.Th>{texts.session}</Table.Th>
                <Table.Th>{texts.role}</Table.Th>
                <Table.Th ta="right">{texts.time}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {sessions.map((session) => (
                <Table.Tr key={session.eventId}>
                  <Table.Td>
                    <Text size="sm" fw={500}>
                      {formatDate(toZoned(session.startsAt, report.timeZone).date)}
                    </Text>
                    <Text size="xs" c="dimmed">
                      {formatTime(session.startsAt, report.timeZone)}–
                      {formatTime(session.endsAt, report.timeZone)}
                    </Text>
                    {toZoned(session.startsAt, report.timeZone).date !==
                      toZoned(session.endsAt, report.timeZone).date && (
                      <Text size="xs" c="dimmed">
                        {texts.endsOn(formatDate(toZoned(session.endsAt, report.timeZone).date))}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Stack gap={3}>
                      <Text size="sm" fw={500}>
                        {session.title || texts.kinds[session.kind]}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {[texts.kinds[session.kind], session.rooms].filter(Boolean).join(' · ')}
                      </Text>
                      <Badge
                        size="xs"
                        variant="light"
                        color={session.confirmed ? 'teal' : 'yellow'}
                      >
                        {session.confirmed ? texts.confirmed : texts.unconfirmed}
                      </Badge>
                    </Stack>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{session.role === 'LEAD' ? texts.lead : texts.assistant}</Text>
                  </Table.Td>
                  <Table.Td ta="right">
                    <Text size="sm" fw={500} style={{ whiteSpace: 'nowrap' }}>
                      {formatWorkedTime(session.seconds)}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {pages > 1 && <Pagination value={current} onChange={setPage} total={pages} size="sm" />}
    </Stack>
  );
}
