import { useState } from 'react';
import { Accordion, Alert, Button, Loader, SimpleGrid, Stack, Text, Timeline } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { getEventStaffHistory, scheduleKeys, type AssignedStaff } from './scheduleApi';
import { formatDateTime } from './scheduleFormat';

export function EventStaffHistorySection({
  eventId,
  timeZone,
}: {
  eventId: string;
  timeZone: string;
}) {
  const texts = pl.schedule.staffHistory;
  const [opened, setOpened] = useState(false);
  const [visible, setVisible] = useState(5);
  const history = useQuery({
    queryKey: scheduleKeys.staffHistory(eventId),
    queryFn: () => getEventStaffHistory(eventId),
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
                    <SimpleGrid cols={2} spacing="sm">
                      <StaffList label={texts.before} members={entry.before} />
                      <StaffList label={texts.after} members={entry.after} />
                    </SimpleGrid>
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

function StaffList({ label, members }: { label: string; members: AssignedStaff[] }) {
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      {members.length === 0 && (
        <Text size="sm" c="dimmed">
          {pl.schedule.details.none}
        </Text>
      )}
      {members.map((member) => (
        <Text key={member.staffId} size="sm">
          {member.displayName ?? pl.schedule.unknownStaff}
          <Text component="span" size="xs" c="dimmed">
            {' '}
            · {pl.schedule.roles[member.role]}
          </Text>
        </Text>
      ))}
    </Stack>
  );
}
