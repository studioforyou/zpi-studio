import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { getStudio, listStaff, scheduleKeys } from '@/features/schedule/scheduleApi';
import { addDays, DEFAULT_TIME_ZONE, toInstant, todayIn } from '@/features/schedule/studioTime';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import {
  cancelTimeOff,
  listTimeOff,
  timeOffKeys,
  type TimeOff,
  type TimeOffQuery,
} from './timeOffApi';
import {
  formatPeriod,
  isCancellable,
  kindColors,
  statusColors,
  timeOffErrorMessage,
} from './timeOffFormat';
import { RecordTimeOffModal } from './RecordTimeOffModal';
import { TimeOffDecisionDrawer } from './TimeOffDecisionDrawer';
import { useTrainerEventsDuring } from './trainerEvents';

type View = 'pending' | 'upcoming' | 'history';

/** How far back the history reaches. */
const HISTORY_DAYS = 183;

function queryFor(view: View, staffId: string | null, timeZone: string): TimeOffQuery {
  // Rounded to the minute so the query key stays stable between renders.
  const now = new Date(Math.floor(Date.now() / 60000) * 60000).toISOString();
  switch (view) {
    case 'pending':
      return { statuses: ['REQUESTED'], staffId, from: now };
    case 'upcoming':
      return { statuses: ['APPROVED'], staffId, from: now };
    case 'history':
      return {
        staffId,
        from: toInstant(addDays(todayIn(timeZone), -HISTORY_DAYS), '00:00', timeZone).toISOString(),
      };
  }
}

/** History: decided or withdrawn requests and time off that has ended, the latest first. */
function inHistory(entry: TimeOff, now: number): boolean {
  return (
    entry.status === 'REJECTED' ||
    entry.status === 'CANCELLED' ||
    new Date(entry.endsAt).getTime() <= now
  );
}

function historyOf(items: TimeOff[]): TimeOff[] {
  const now = Date.now();
  return items
    .filter((entry) => inHistory(entry, now))
    .sort((a, b) => b.startsAt.localeCompare(a.startsAt));
}

/** How many events the trainer runs during the time off. */
function EventCount({ timeOff }: { timeOff: TimeOff }) {
  const texts = pl.timeOff.events;
  const { events, own } = useTrainerEventsDuring(timeOff);
  if (!events.isSuccess) {
    return <Text c="dimmed">{texts.unknown}</Text>;
  }
  return own.length === 0 ? (
    <Text c="dimmed">{texts.none}</Text>
  ) : (
    <Text c="orange.8" fw={500}>
      {texts.count(own.length)}
    </Text>
  );
}

/**
 * Leave and unavailability of the studio's trainers. Administrators approve or reject the
 * trainers' requests, record time off themselves and cancel it after an early return.
 */
export function TimeOffPage() {
  const texts = pl.timeOff;
  const queryClient = useQueryClient();
  const [view, setView] = useState<View>('pending');
  const [staffId, setStaffId] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState<TimeOff | null>(null);
  const [cancelling, setCancelling] = useState<TimeOff | null>(null);
  const [recording, setRecording] = useState(false);

  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const staff = useQuery({ queryKey: scheduleKeys.staff, queryFn: listStaff });
  const timeZone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;
  const query = queryFor(view, staffId, timeZone);
  const timeOff = useQuery({
    queryKey: timeOffKeys.list(query),
    queryFn: () => listTimeOff(query),
  });
  // The count on the first tab stays visible while another tab is open.
  const pendingQuery = queryFor('pending', null, timeZone);
  const pending = useQuery({
    queryKey: timeOffKeys.list(pendingQuery),
    queryFn: () => listTimeOff(pendingQuery),
  });

  const cancel = useMutation({
    mutationFn: (entry: TimeOff) => cancelTimeOff(entry.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
      setCancelling(null);
    },
  });

  const staffOptions = (staff.data ?? [])
    .filter((member) => member.isActive)
    .map((member) => ({ value: member.id, label: member.displayName }));
  const entries = view === 'history' ? historyOf(timeOff.data ?? []) : (timeOff.data ?? []);
  const pendingCount = pending.data?.length ?? 0;

  return (
    <Stack>
      <Stack gap="xs" maw={720}>
        <Title order={2}>{texts.title}</Title>
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
      </Stack>
      <Group justify="space-between" align="flex-end">
        <Select
          aria-label={texts.staffFilter}
          placeholder={texts.staffFilterPlaceholder}
          clearable
          searchable
          w={260}
          data={staffOptions}
          value={staffId}
          onChange={setStaffId}
        />
        <Button onClick={() => setRecording(true)}>{texts.add}</Button>
      </Group>
      <Tabs value={view} onChange={(value) => value && setView(value as View)}>
        <Tabs.List aria-label={texts.tabsLabel}>
          <Tabs.Tab
            value="pending"
            rightSection={
              pendingCount > 0 ? (
                <Badge size="sm" variant="light" circle>
                  {pendingCount}
                </Badge>
              ) : undefined
            }
          >
            {texts.tabs.pending}
          </Tabs.Tab>
          <Tabs.Tab value="upcoming">{texts.tabs.upcoming}</Tabs.Tab>
          <Tabs.Tab value="history">{texts.tabs.history}</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      {timeOff.isPending ? (
        <Text>{texts.loading}</Text>
      ) : timeOff.isError ? (
        <Alert color="red">{texts.loadError}</Alert>
      ) : entries.length === 0 ? (
        <Text c="dimmed">{texts.empty[view]}</Text>
      ) : (
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.columns.staff}</Table.Th>
              <Table.Th>{texts.columns.kind}</Table.Th>
              <Table.Th>{texts.columns.period}</Table.Th>
              {view === 'pending' && <Table.Th>{texts.columns.reason}</Table.Th>}
              {view !== 'pending' && <Table.Th>{texts.columns.status}</Table.Th>}
              {view !== 'history' && <Table.Th>{texts.columns.events}</Table.Th>}
              {view === 'pending' && <Table.Th>{texts.columns.requestedAt}</Table.Th>}
              {view === 'upcoming' && <Table.Th>{texts.columns.approvedBy}</Table.Th>}
              {view === 'history' && <Table.Th>{texts.columns.note}</Table.Th>}
              {view === 'history' && <Table.Th>{texts.columns.decision}</Table.Th>}
              {view !== 'history' && <Table.Th>{texts.columns.actions}</Table.Th>}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {entries.map((entry) => (
              <Table.Tr key={entry.id}>
                <Table.Td>
                  <Text fw={500}>{entry.staffDisplayName}</Text>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light" color={kindColors[entry.kind]}>
                    {texts.kinds[entry.kind]}
                  </Badge>
                </Table.Td>
                <Table.Td>{formatPeriod(entry.startsAt, entry.endsAt, timeZone)}</Table.Td>
                {view === 'pending' && (
                  <Table.Td maw={260}>
                    <Text size="sm" lineClamp={2}>
                      {entry.reason ?? texts.noReason}
                    </Text>
                  </Table.Td>
                )}
                {view !== 'pending' && (
                  <Table.Td>
                    <Badge color={statusColors[entry.status]} variant="light">
                      {texts.statuses[entry.status]}
                    </Badge>
                  </Table.Td>
                )}
                {view !== 'history' && (
                  <Table.Td>
                    <EventCount timeOff={entry} />
                  </Table.Td>
                )}
                {view === 'pending' && (
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {formatDateTime(entry.requestedAt, timeZone)}
                    </Text>
                  </Table.Td>
                )}
                {view === 'upcoming' && (
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {entry.decidedByName ?? texts.studio}
                      {entry.decidedAt && `, ${formatDateTime(entry.decidedAt, timeZone)}`}
                    </Text>
                    {entry.recordedByStudio && (
                      <Text size="xs" c="dimmed">
                        {texts.recordedByStudio}
                      </Text>
                    )}
                  </Table.Td>
                )}
                {view === 'history' && (
                  <Table.Td maw={320}>
                    <Text size="sm" lineClamp={2}>
                      {entry.decisionNote ?? texts.noReason}
                    </Text>
                  </Table.Td>
                )}
                {view === 'history' && (
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {entry.status === 'CANCELLED'
                        ? texts.withdrawn
                        : entry.decidedAt
                          ? `${entry.decidedByName ?? texts.studio}, ${formatDateTime(entry.decidedAt, timeZone)}`
                          : texts.noReason}
                    </Text>
                  </Table.Td>
                )}
                {view !== 'history' && (
                  <Table.Td>
                    <Group gap={4} wrap="nowrap" justify="flex-end">
                      {entry.status === 'REQUESTED' && (
                        <Button size="xs" variant="default" onClick={() => setReviewing(entry)}>
                          {texts.review}
                        </Button>
                      )}
                      {entry.status === 'APPROVED' && isCancellable(entry) && (
                        <Button
                          size="xs"
                          variant="light"
                          color="red"
                          onClick={() => {
                            cancel.reset();
                            setCancelling(entry);
                          }}
                        >
                          {texts.cancel}
                        </Button>
                      )}
                    </Group>
                  </Table.Td>
                )}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {reviewing && (
        <TimeOffDecisionDrawer
          key={reviewing.id}
          timeOff={reviewing}
          timeZone={timeZone}
          onClose={() => setReviewing(null)}
        />
      )}
      <Modal
        opened={cancelling !== null}
        onClose={() => setCancelling(null)}
        title={texts.cancelConfirm.title}
      >
        {cancelling && (
          <Stack>
            <Text size="sm">
              {texts.cancelConfirm.body(
                cancelling.staffDisplayName,
                formatPeriod(cancelling.startsAt, cancelling.endsAt, timeZone),
              )}
            </Text>
            {cancel.error && <Alert color="red">{timeOffErrorMessage(cancel.error)}</Alert>}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setCancelling(null)}>
                {texts.cancelConfirm.back}
              </Button>
              <Button
                color="red"
                loading={cancel.isPending}
                onClick={() => cancel.mutate(cancelling)}
              >
                {texts.cancelConfirm.confirm}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
      {recording && (
        <RecordTimeOffModal
          staffOptions={staffOptions}
          timeZone={timeZone}
          onClose={() => setRecording(false)}
        />
      )}
    </Stack>
  );
}
