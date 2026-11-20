import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Divider,
  Drawer,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Table,
  Tabs,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { getStudio, listStaff, scheduleKeys } from '@/features/schedule/scheduleApi';
import {
  addDays,
  DEFAULT_TIME_ZONE,
  toInstant,
  todayIn,
  toZoned,
  formatDate,
  formatDayLong,
} from '@/features/schedule/studioTime';
import { formatTimeRange } from '@/features/schedule/scheduleFormat';
import { CancelStudioEventModal } from '@/features/schedule/CancelStudioEventModal';
import {
  decideSubstitution,
  getSubstitution,
  listSubstitutions,
  substitutionCandidates,
  substitutionColors,
  substitutionError,
  substitutionKeys,
  type Substitution,
  type SubstitutionCandidate,
} from './substitutionApi';

type View = 'open' | 'pending' | 'history';
type Decision = 'approve' | 'reject' | 'assign' | 'close';

export function SubstitutionsPage() {
  const t = pl.substitutions;
  const [view, setView] = useState<View>('pending');
  const [staffId, setStaffId] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const staff = useQuery({ queryKey: scheduleKeys.staff, queryFn: listStaff });
  const zone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;
  const [from, setFrom] = useState(() => addDays(todayIn(zone), -14));
  const [to, setTo] = useState(() => addDays(todayIn(zone), 30));
  const start = from ? toInstant(from, '00:00', zone).toISOString() : '';
  const end = to ? toInstant(addDays(to, 1), '00:00', zone).toISOString() : '';
  const span = Date.parse(end) - Date.parse(start);
  const valid = Number.isFinite(span) && span > 0 && span <= 93 * 86400000;
  const query = useQuery({
    queryKey: substitutionKeys.list(start, end),
    queryFn: () => listSubstitutions(start, end),
    enabled: valid,
    refetchInterval: 15000,
  });
  const all = (query.data ?? []).filter((r) => !staffId || r.originalStaffId === staffId);
  const groups = {
    open: all.filter((r) => r.status === 'OPEN'),
    pending: all.filter((r) => r.status === 'CLAIMED'),
    history: all
      .filter((r) => r.status !== 'OPEN' && r.status !== 'CLAIMED')
      .sort((a, b) => b.startsAt.localeCompare(a.startsAt)),
  };
  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={2}>{t.title}</Title>
          <Text c="dimmed" size="sm">
            {t.intro}
          </Text>
        </div>
        <Button variant="subtle" onClick={() => void query.refetch()}>
          {t.refresh}
        </Button>
      </Group>
      <Group align="flex-end">
        <Select
          label={t.trainerFilter}
          placeholder={t.allTrainers}
          searchable
          clearable
          value={staffId}
          onChange={setStaffId}
          data={(staff.data ?? []).map((s) => ({ value: s.id, label: s.displayName }))}
          w={240}
        />
        <TextInput
          type="date"
          label={t.from}
          value={from}
          onChange={(e) => setFrom(e.currentTarget.value)}
        />
        <TextInput
          type="date"
          label={t.to}
          value={to}
          onChange={(e) => setTo(e.currentTarget.value)}
        />
      </Group>
      {!valid && <Alert color="orange">{t.invalidRange}</Alert>}
      <Tabs value={view} onChange={(v) => setView(v as View)}>
        <Tabs.List>
          {(['pending', 'open', 'history'] as const).map((key) => (
            <Tabs.Tab
              key={key}
              value={key}
              rightSection={
                <Badge size="sm" color={key === 'pending' ? 'orange' : 'gray'} variant="light">
                  {groups[key].length}
                </Badge>
              }
            >
              {t[key]}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      {query.isPending && valid && <Loader size="sm" />}
      {query.error && (
        <Alert color="red">
          {t.loadingError}
          <Button variant="subtle" onClick={() => void query.refetch()}>
            {t.refresh}
          </Button>
        </Alert>
      )}
      {valid && query.isSuccess && (
        <Table.ScrollContainer minWidth={760}>
          <Table striped highlightOnHover verticalSpacing="md">
            <Table.Thead>
              <Table.Tr>
                {[t.event, t.when, t.original, t.candidate, t.status, ''].map((label, index) => (
                  <Table.Th key={index}>{label}</Table.Th>
                ))}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groups[view].length === 0 && (
                <Table.Tr>
                  <Table.Td colSpan={6}>
                    <Text c="dimmed" ta="center" py="xl">
                      {t.empty}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              )}
              {groups[view].map((r) => (
                <Table.Tr key={r.id}>
                  <Table.Td>
                    <Text fw={600}>{r.title}</Text>
                    <Text size="xs" c="dimmed">
                      {r.kind === 'PERSONAL_TRAINING' ? t.personal : t.group} · {r.rooms}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{formatDate(toZoned(r.startsAt, zone).date)}</Text>
                    <Text size="xs" c="dimmed">
                      {formatTimeRange(r.startsAt, r.endsAt, zone)}
                    </Text>
                  </Table.Td>
                  <Table.Td>{r.originalTrainer}</Table.Td>
                  <Table.Td>
                    <Text c={r.substituteTrainer ? undefined : 'dimmed'}>
                      {r.substituteTrainer ?? '—'}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Status offer={r} />
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="light"
                      onClick={() => setSelected(r.id)}
                      aria-label={`${t.details}: ${r.title}`}
                    >
                      {t.details}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <SubstitutionDrawer id={selected} zone={zone} onClose={() => setSelected(null)} />
    </Stack>
  );
}

function Status({ offer }: { offer: Substitution }) {
  return (
    <Badge variant="light" color={substitutionColors[offer.status]}>
      {pl.substitutions.statuses[offer.status]}
    </Badge>
  );
}

function CandidateAvailability({ candidate }: { candidate?: SubstitutionCandidate }) {
  const t = pl.substitutions;
  if (!candidate) return null;
  return (
    <Stack gap={4}>
      <Badge color={candidate.available ? 'green' : 'red'} variant="light">
        {candidate.available ? t.available : reasonLabel(candidate.reason)}
      </Badge>
      {candidate.warnings.length > 0 && (
        <Text size="xs" c="orange.8">
          {t.warning}
        </Text>
      )}
    </Stack>
  );
}

function reasonLabel(reason: string | null) {
  const reasons = pl.substitutions.reasons;
  return reason && reason in reasons
    ? reasons[reason as keyof typeof reasons]
    : pl.substitutions.unavailable;
}

function SubstitutionDrawer({
  id,
  zone,
  onClose,
}: {
  id: string | null;
  zone: string;
  onClose: () => void;
}) {
  const t = pl.substitutions;
  const client = useQueryClient();
  const [note, setNote] = useState('');
  const [staffId, setStaffId] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Decision | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const detail = useQuery({
    queryKey: substitutionKeys.detail(id ?? ''),
    queryFn: () => getSubstitution(id!),
    enabled: id !== null,
    refetchInterval: id ? 15000 : false,
  });
  const candidates = useQuery({
    queryKey: substitutionKeys.candidates(id ?? ''),
    queryFn: () => substitutionCandidates(id!),
    enabled: id !== null,
    refetchInterval: id ? 15000 : false,
  });
  const offer = detail.data;
  const active = offer?.status === 'OPEN' || offer?.status === 'CLAIMED';
  async function refresh() {
    await Promise.all([
      client.invalidateQueries({ queryKey: substitutionKeys.all }),
      client.invalidateQueries({ queryKey: scheduleKeys.all }),
    ]);
  }
  const mutation = useMutation({
    mutationFn: (action: Decision) =>
      decideSubstitution(offer!, action, note, staffId ?? undefined),
    onSuccess: async () => {
      setConfirm(null);
      setNote('');
      setStaffId(null);
      await refresh();
    },
    onError: () => {
      void refresh();
    },
  });
  const currentCandidate = candidates.data?.find((c) => c.staffId === offer?.substituteStaffId);
  const chosen = candidates.data?.find((c) => c.staffId === staffId);
  const loading = mutation.isPending;
  function close() {
    if (!loading) {
      setNote('');
      setStaffId(null);
      mutation.reset();
      onClose();
    }
  }
  const headings = {
    approve: t.confirmApprove,
    reject: t.confirmReject,
    assign: t.confirmAssign,
    close: t.confirmClose,
  };
  return (
    <>
      <Drawer
        opened={id !== null}
        onClose={close}
        position="right"
        size="md"
        title={offer?.title ?? t.title}
        closeOnEscape={!loading}
        closeOnClickOutside={!loading}
        withCloseButton={!loading}
      >
        {detail.isPending && <Loader size="sm" />}
        {detail.error && (
          <Alert color="red">
            {t.loadingError}
            <Button variant="subtle" onClick={() => void refresh()}>
              {t.refresh}
            </Button>
          </Alert>
        )}
        {offer && (
          <Stack gap="lg">
            <Group>
              <Status offer={offer} />
              <Text size="sm" c="dimmed">
                {offer.kind === 'PERSONAL_TRAINING' ? t.personal : t.group}
              </Text>
            </Group>
            <div>
              <Text fw={600} size="lg">
                {formatDayLong(toZoned(offer.startsAt, zone).date)}
              </Text>
              <Text>
                {formatTimeRange(offer.startsAt, offer.endsAt, zone)} · {offer.rooms}
              </Text>
            </div>
            <Card withBorder radius="md" padding="md">
              <Stack gap="sm">
                <div>
                  <Text size="xs" c="dimmed">
                    {t.original}
                  </Text>
                  <Text fw={600}>{offer.originalTrainer}</Text>
                </div>
                <Divider />
                <div>
                  <Text size="xs" c="dimmed">
                    {t.currentCandidate}
                  </Text>
                  <Text fw={600}>{offer.substituteTrainer ?? t.noCandidate}</Text>
                </div>
                {active && <CandidateAvailability candidate={currentCandidate} />}
              </Stack>
            </Card>
            {offer.decisionNote && <Text size="sm">{offer.decisionNote}</Text>}
            {mutation.error && <Alert color="red">{substitutionError(mutation.error)}</Alert>}
            {active && (
              <>
                <Textarea
                  label={t.note}
                  description={t.noteHint}
                  value={note}
                  onChange={(e) => setNote(e.currentTarget.value)}
                  maxLength={1000}
                  minRows={2}
                  disabled={loading}
                />
                {offer.status === 'CLAIMED' && (
                  <Group grow>
                    <Button
                      disabled={loading || !currentCandidate?.available}
                      onClick={() => setConfirm('approve')}
                    >
                      {t.approve}
                    </Button>
                    <Button
                      color="red"
                      variant="light"
                      disabled={loading}
                      onClick={() => setConfirm('reject')}
                    >
                      {t.reject}
                    </Button>
                  </Group>
                )}
                <Divider />
                {candidates.error && (
                  <Alert color="red">
                    {t.loadingError}
                    <Button variant="subtle" onClick={() => void candidates.refetch()}>
                      {t.refresh}
                    </Button>
                  </Alert>
                )}
                <Select
                  label={t.select}
                  placeholder={t.selectPlaceholder}
                  searchable
                  clearable
                  value={staffId}
                  onChange={setStaffId}
                  disabled={loading || candidates.isPending}
                  data={(candidates.data ?? []).map((c) => ({
                    value: c.staffId,
                    label: `${c.displayName}${c.available ? '' : ` — ${reasonLabel(c.reason)}`}`,
                    disabled: !c.available,
                  }))}
                />
                {chosen && <CandidateAvailability candidate={chosen} />}
                {candidates.isSuccess && !candidates.data.some((c) => c.available) && (
                  <Text size="sm" c="dimmed">
                    {t.noAvailable}
                  </Text>
                )}
                <Button
                  variant="light"
                  disabled={!chosen?.available || loading}
                  onClick={() => setConfirm('assign')}
                >
                  {t.assign}
                </Button>
                <Divider />
                <Group justify="space-between">
                  <Button
                    variant="subtle"
                    color="gray"
                    disabled={loading}
                    onClick={() => setConfirm('close')}
                  >
                    {t.close}
                  </Button>
                  <Button
                    variant="subtle"
                    color="red"
                    disabled={loading}
                    onClick={() => setCancelling(true)}
                  >
                    {t.cancelEvent}
                  </Button>
                </Group>
              </>
            )}
          </Stack>
        )}
      </Drawer>
      <Modal
        opened={confirm !== null}
        onClose={() => {
          if (!loading) setConfirm(null);
        }}
        title={confirm ? headings[confirm] : ''}
        closeOnEscape={!loading}
        closeOnClickOutside={!loading}
        withCloseButton={!loading}
      >
        <Stack>
          <Text>
            {confirm === 'reject'
              ? t.rejectHint
              : confirm === 'close'
                ? t.closeHint
                : t.approveHint}
          </Text>
          {confirm === 'assign' && <Text fw={600}>{chosen?.displayName}</Text>}
          {mutation.error && <Alert color="red">{substitutionError(mutation.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" disabled={loading} onClick={() => setConfirm(null)}>
              {t.back}
            </Button>
            <Button
              loading={loading}
              color={confirm === 'reject' ? 'red' : undefined}
              onClick={() => {
                if (confirm) mutation.mutate(confirm);
              }}
            >
              {t.confirm}
            </Button>
          </Group>
        </Stack>
      </Modal>
      <CancelStudioEventModal
        target={
          cancelling && offer
            ? { id: offer.eventId, version: offer.eventVersion, title: offer.title }
            : null
        }
        onClose={() => setCancelling(false)}
        onCancelled={() => {
          void refresh();
          close();
        }}
      />
    </>
  );
}
