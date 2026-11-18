import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Drawer,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  Textarea,
  Tooltip,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { ApiError } from '@/shared/api/ApiError';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import { formatDateTime, formatTimeRange } from '@/features/schedule/scheduleFormat';
import { substitutionKeys, substitutionColors } from './substitutionApi';
import {
  chosenTimeOffEvents,
  decideTimeOffCover,
  getTimeOffPlan,
  saveTimeOffSelection,
  timeOffCandidates,
  timeOffCoverKeys,
  timeOffKeys,
  type TimeOff,
  type TimeOffBatch,
  type TimeOffLesson,
  type TimeOffPlan,
} from './timeOffApi';

export function TimeOffSessionPicker({
  plan,
  choices,
  onChange,
  zone,
  disabled = false,
}: {
  plan: TimeOffPlan;
  choices: Record<string, boolean>;
  onChange: (choices: Record<string, boolean>) => void;
  zone: string;
  disabled?: boolean;
}) {
  const t = pl.timeOffCover;
  const items = plan.items;
  const chosen = chosenTimeOffEvents(plan, choices);
  const count = items.filter((i) => i.selectable).length;
  const needsManual = items.some(
    (i) => i.status === 'MANUAL' || (i.selectable && !chosen.includes(i.eventId)),
  );
  function all(selected: boolean) {
    onChange({
      ...choices,
      ...Object.fromEntries(items.filter((i) => i.selectable).map((i) => [i.eventId, selected])),
    });
  }
  return (
    <Stack gap="xs">
      <Group justify="space-between" wrap="wrap">
        <Group gap="xs">
          <Text fw={600}>{t.manage}</Text>
          <Badge variant="light">
            {chosen.length}/{count}
          </Badge>
          <Tooltip
            label={plan.status === 'APPROVED' ? t.managementInfo : t.info}
            multiline
            w={320}
            withArrow
          >
            <Button variant="subtle" size="compact-xs" aria-label={t.title}>
              ⓘ
            </Button>
          </Tooltip>
        </Group>
        <Group gap="xs">
          <Button
            size="compact-xs"
            variant="subtle"
            disabled={disabled || count === 0}
            onClick={() => all(true)}
          >
            {t.all}
          </Button>
          <Button
            size="compact-xs"
            variant="subtle"
            disabled={disabled || count === 0}
            onClick={() => all(false)}
          >
            {t.none}
          </Button>
        </Group>
      </Group>
      {needsManual && (
        <Alert color="orange" variant="light" p="xs">
          {t.manualWarning}
        </Alert>
      )}
      {items.length === 0 ? (
        <Text c="dimmed" size="sm">
          {t.empty}
        </Text>
      ) : (
        <Table.ScrollContainer minWidth={460} maxHeight={320}>
          <Table verticalSpacing="xs" highlightOnHover stickyHeader>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t.selected}</Table.Th>
                <Table.Th>{t.session}</Table.Th>
                <Table.Th>{t.status}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {items.map((item) => (
                <Table.Tr key={item.eventId}>
                  <Table.Td w={45}>
                    <Checkbox
                      aria-label={`${t.selected}: ${item.title}, ${formatDateTime(item.startsAt, zone)}`}
                      checked={item.selectable && chosen.includes(item.eventId)}
                      disabled={disabled || !item.selectable}
                      onChange={(e) =>
                        onChange({ ...choices, [item.eventId]: e.currentTarget.checked })
                      }
                    />
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" fw={600}>
                      {formatDateTime(item.startsAt, zone)}–
                      {formatTimeRange(item.startsAt, item.endsAt, zone).split('–').at(-1)}
                    </Text>
                    <Text size="sm">{item.title}</Text>
                    <Text size="xs" c="dimmed">
                      {item.rooms}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <CoverageStatus item={item} selected={chosen.includes(item.eventId)} />
                    {!item.selectable && ['OPEN', 'CLAIMED'].includes(item.status) && (
                      <Text size="xs" c="dimmed" mt={4}>
                        {t.already}
                      </Text>
                    )}
                    {item.substituteTrainer && (
                      <Text size="xs" mt={4}>
                        {item.substituteTrainer}
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

function CoverageStatus({ item, selected }: { item: TimeOffLesson; selected: boolean }) {
  const t = pl.timeOffCover;
  if (item.status === 'MANUAL')
    return (
      <Badge color="orange" variant="light">
        {t.manual}
      </Badge>
    );
  if (item.status in pl.substitutions.statuses)
    return (
      <Badge
        variant="light"
        color={substitutionColors[item.status as keyof typeof substitutionColors]}
      >
        {pl.substitutions.statuses[item.status as keyof typeof pl.substitutions.statuses]}
      </Badge>
    );
  return (
    <Text size="xs" c="dimmed">
      {item.status === 'CLOSED' ? t.closed : selected ? t.waiting : t.unselected}
    </Text>
  );
}

function coverError(error: unknown) {
  if (error instanceof ApiError && error.code === 'VERSION_CONFLICT')
    return pl.timeOffCover.changed;
  if (error instanceof ApiError && error.code === 'SUBSTITUTION_NOT_ELIGIBLE')
    return pl.timeOffCover.unavailable;
  return pl.timeOffCover.error;
}

type Confirmation =
  | { action: 'select'; version: number; ids: string[] }
  | {
      action: 'approve' | 'assign';
      input: TimeOffBatch;
      lessons: TimeOffLesson[];
      name?: string;
    };

export function TimeOffSubstitutionsDrawer({
  timeOff,
  zone,
  onClose,
}: {
  timeOff: TimeOff;
  zone: string;
  onClose: () => void;
}) {
  const t = pl.timeOffCover;
  const client = useQueryClient();
  const [choices, setChoices] = useState<Record<string, boolean>>({});
  const [staffId, setStaffId] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const plan = useQuery({
    queryKey: timeOffCoverKeys.plan(timeOff.id),
    queryFn: () => getTimeOffPlan(timeOff.id),
    refetchInterval: 15000,
  });
  const selected = chosenTimeOffEvents(plan.data, choices);
  const chosen = (plan.data?.items ?? []).filter((i) => selected.includes(i.eventId));
  const pending = chosen.filter((i) => i.status === 'CLAIMED');
  const unassigned = chosen.filter((i) => i.status === 'OPEN' || i.status === 'NOT_REQUESTED');
  const version = plan.data?.version ?? 0;
  const ids = unassigned.map((i) => i.eventId);
  const candidates = useQuery({
    queryKey: timeOffCoverKeys.candidates(timeOff.id, version, ids),
    queryFn: () => timeOffCandidates(timeOff.id, version, ids),
    enabled: ids.length > 0 && plan.data?.status === 'APPROVED',
  });
  const candidate = candidates.data?.find((c) => c.staffId === staffId);
  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: timeOffKeys.all }),
      client.invalidateQueries({ queryKey: scheduleKeys.all }),
      client.invalidateQueries({ queryKey: substitutionKeys.all }),
    ]);
  }
  const action = useMutation({
    mutationFn: (snapshot: Confirmation) =>
      snapshot.action === 'select'
        ? saveTimeOffSelection(timeOff.id, snapshot.version, snapshot.ids)
        : decideTimeOffCover(timeOff.id, snapshot.action, snapshot.input),
    onSuccess: async () => {
      setConfirmation(null);
      setChoices({});
      setStaffId(null);
      setNote('');
      await refresh();
    },
    onError: () => {
      setConfirmation(null);
      void refresh();
    },
  });
  const saving = action.isPending;
  function confirm(which: 'approve' | 'assign') {
    const lessons = which === 'approve' ? pending : unassigned;
    setConfirmation({
      action: which,
      lessons,
      name: which === 'assign' ? candidate?.displayName : undefined,
      input: {
        version,
        items: lessons.map(({ eventId, eventVersion, requestId, requestVersion }) => ({
          eventId,
          eventVersion,
          requestId,
          requestVersion,
        })),
        ...(which === 'assign' && staffId ? { staffId } : {}),
        note,
      },
    });
  }
  return (
    <>
      <Drawer
        opened
        onClose={onClose}
        position="right"
        size="xl"
        title={`${t.title} · ${timeOff.staffDisplayName}`}
        closeOnEscape={!saving}
        closeOnClickOutside={!saving}
        withCloseButton={!saving}
      >
        <Stack>
          <Text size="sm" c="dimmed">
            {formatDateTime(timeOff.startsAt, zone)} — {formatDateTime(timeOff.endsAt, zone)}
          </Text>
          {plan.isPending && <Loader size="sm" />}
          {plan.error && (
            <Alert color="red">
              {t.error}
              <Button variant="subtle" onClick={() => void refresh()}>
                {t.retry}
              </Button>
            </Alert>
          )}
          {plan.data && (
            <>
              <TimeOffSessionPicker
                plan={plan.data}
                choices={choices}
                onChange={setChoices}
                zone={zone}
                disabled={saving || plan.data.status !== 'APPROVED'}
              />
              {action.error && <Alert color="red">{coverError(action.error)}</Alert>}
              {plan.data.status === 'APPROVED' ? (
                <>
                  <Button
                    variant="light"
                    disabled={saving}
                    onClick={() => setConfirmation({ action: 'select', version, ids: selected })}
                  >
                    {chosen.some((i) => i.status === 'NOT_REQUESTED') ? t.start : t.save} (
                    {selected.length})
                  </Button>
                  <Textarea
                    label={t.note}
                    rows={1}
                    value={note}
                    onChange={(e) => setNote(e.currentTarget.value)}
                    maxLength={1000}
                    disabled={saving}
                  />
                  {pending.length > 0 && (
                    <Button color="green" disabled={saving} onClick={() => confirm('approve')}>
                      {t.approve} ({pending.length})
                    </Button>
                  )}
                  {unassigned.length > 0 && (
                    <>
                      {candidates.error && <Alert color="red">{t.error}</Alert>}
                      <Select
                        label={t.select}
                        placeholder={t.selectPlaceholder}
                        value={staffId}
                        onChange={setStaffId}
                        disabled={saving || candidates.isPending}
                        searchable
                        clearable
                        data={(candidates.data ?? []).map((c) => ({
                          value: c.staffId,
                          disabled: !c.available,
                          label:
                            c.displayName +
                            (c.available
                              ? ''
                              : ` — ${c.reason === 'SELECTED_EVENTS_OVERLAP' ? t.overlapping : (pl.substitutions.reasons[c.reason as keyof typeof pl.substitutions.reasons] ?? pl.substitutions.unavailable)}`),
                        }))}
                      />
                      {candidate?.warnings.length ? (
                        <Text size="xs" c="orange.8">
                          {pl.substitutions.warning}
                        </Text>
                      ) : null}
                      <Button
                        disabled={saving || !candidate?.available}
                        onClick={() => confirm('assign')}
                      >
                        {t.assign} ({unassigned.length})
                      </Button>
                    </>
                  )}
                </>
              ) : (
                <Alert color="blue">{t.pendingOnly}</Alert>
              )}
            </>
          )}
        </Stack>
      </Drawer>
      <Modal
        opened={confirmation !== null}
        onClose={() => !saving && setConfirmation(null)}
        closeOnEscape={!saving}
        closeOnClickOutside={!saving}
        withCloseButton={!saving}
        title={
          confirmation?.action === 'select'
            ? t.selectionTitle
            : confirmation?.action === 'approve'
              ? t.approveTitle
              : t.assignTitle
        }
      >
        {confirmation && (
          <Stack>
            <Text size="sm">
              {confirmation.action === 'select' ? t.selectionInfo : t.changeInfo}
            </Text>
            {confirmation.action !== 'select' && (
              <>
                {confirmation.name && <Text fw={600}>{confirmation.name}</Text>}
                {confirmation.lessons.map((i) => (
                  <Text size="sm" key={i.eventId}>
                    {formatDateTime(i.startsAt, zone)} · {i.title}
                    {confirmation.action === 'approve' &&
                      i.substituteTrainer &&
                      ` — ${i.substituteTrainer}`}
                  </Text>
                ))}
              </>
            )}
            <Group justify="flex-end">
              <Button variant="default" disabled={saving} onClick={() => setConfirmation(null)}>
                {t.back}
              </Button>
              <Button loading={saving} onClick={() => action.mutate(confirmation)}>
                {t.confirm}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </>
  );
}
