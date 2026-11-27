import { useState, type ReactNode } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Drawer,
  Group,
  NumberInput,
  Paper,
  Radio,
  Stack,
  Text,
  Textarea,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import { formatDate } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  disputeKeys,
  disputeResolutions,
  getDispute,
  resolveDispute,
  type Dispute,
  type DisputeOption,
  type DisputeOptions,
  type DisputeResolution,
  type ResolveDisputeInput,
} from './disputesApi';
import {
  decidedBy,
  disputeErrorMessage,
  formatSession,
  makeupValidUntil,
  resolutionColors,
  trainerNames,
} from './disputeFormat';

type DisputeDrawerProps = {
  /** The dispute as listed; the drawer loads its details and options. */
  dispute: Dispute;
  timeZone: string;
  onClose: () => void;
};

const MAX_NOTE = 1000;
const MAX_DAYS = 365;

const OPTION_LABELS = {
  UPHELD: 'upheld',
  UPHELD_MAKEUP: 'upheldMakeup',
  REJECTED: 'rejected',
} as const satisfies Record<DisputeResolution, keyof typeof pl.disputes.drawer>;

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Group gap="md" wrap="nowrap" align="flex-start">
      <Text c="dimmed" w={150} style={{ flexShrink: 0 }}>
        {label}
      </Text>
      <div style={{ minWidth: 0 }}>{children}</div>
    </Group>
  );
}

function fundingText(options: DisputeOptions, zone: string): string {
  const texts = pl.disputes.drawer;
  switch (options.funding) {
    case 'PASS': {
      const balance = texts.fundingPass(options.passName ?? '', options.entriesRemaining ?? 0);
      return options.passValidUntil
        ? `${balance} · ${texts.fundingPassUntil(formatDate(options.passValidUntil))}`
        : balance;
    }
    case 'UNLIMITED_PASS':
      return texts.fundingUnlimited(options.passName ?? '');
    case 'MAKEUP':
      return texts.fundingMakeup(
        options.passName ?? '',
        options.makeupExpiresAt ? formatDateTime(options.makeupExpiresAt, zone) : pl.disputes.none,
      );
    case 'NONE':
      return texts.fundingNone;
  }
}

function optionFor(options: DisputeOptions, resolution: DisputeResolution): DisputeOption {
  switch (resolution) {
    case 'UPHELD':
      return options.upheld;
    case 'UPHELD_MAKEUP':
      return options.upheldMakeup;
    case 'REJECTED':
      return options.rejected;
  }
}

/** What the decision does to the client's package, with what to know before choosing it. */
function OptionDescription({
  resolution,
  option,
}: {
  resolution: DisputeResolution;
  option: DisputeOption;
}) {
  const texts = pl.disputes.drawer;
  const effect =
    resolution === 'UPHELD'
      ? texts.upheldEffects[option.effect]
      : resolution === 'UPHELD_MAKEUP'
        ? texts.upheldMakeupEffect
        : texts.rejectedEffect;
  return (
    <Stack gap={2}>
      {option.allowed && <Text size="sm">{effect}</Text>}
      {option.notice && (
        <Text size="sm" c={option.allowed ? 'orange.8' : 'dimmed'}>
          {texts.notices[option.notice]}
        </Text>
      )}
    </Stack>
  );
}

function ResolutionForm({
  dispute,
  options,
  timeZone,
  onResolved,
}: {
  dispute: Dispute;
  options: DisputeOptions;
  timeZone: string;
  onResolved: () => void;
}) {
  const texts = pl.disputes.drawer;
  const queryClient = useQueryClient();
  const [resolution, setResolution] = useState<DisputeResolution | null>(null);
  const [days, setDays] = useState<number | string>(options.makeupValidityDays ?? 30);
  const [note, setNote] = useState('');

  const resolve = useMutation({
    mutationFn: (input: ResolveDisputeInput) => resolveDispute(dispute.id, input),
    retry: false,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: disputeKeys.all });
      onResolved();
    },
    // The options or the dispute itself may have changed; fresh ones replace them.
    onError: () => queryClient.invalidateQueries({ queryKey: disputeKeys.all }),
  });

  const makeup = resolution === 'UPHELD_MAKEUP';
  const validDays =
    typeof days === 'number' && Number.isInteger(days) && days >= 1 && days <= MAX_DAYS;
  const chosen = resolution === null ? null : optionFor(options, resolution);
  const ready =
    chosen !== null &&
    chosen.allowed &&
    note.trim() !== '' &&
    (!makeup || validDays) &&
    !resolve.isPending;

  function submit() {
    if (!ready || resolution === null) return;
    resolve.mutate({
      resolution,
      note: note.trim(),
      makeupValidityDays: makeup ? (days as number) : null,
      version: dispute.reservationVersion,
    });
  }

  return (
    <Stack>
      <Radio.Group
        label={texts.decision}
        value={resolution}
        onChange={(value) => setResolution(value as DisputeResolution)}
      >
        <Stack mt="xs" gap="sm">
          {disputeResolutions.map((value) => {
            const option = optionFor(options, value);
            return (
              <Radio
                key={value}
                value={value}
                label={texts[OPTION_LABELS[value]]}
                description={<OptionDescription resolution={value} option={option} />}
                disabled={!option.allowed || resolve.isPending}
              />
            );
          })}
        </Stack>
      </Radio.Group>
      {makeup && (
        <NumberInput
          label={texts.validityDays}
          min={1}
          max={MAX_DAYS}
          allowDecimal={false}
          value={days}
          onChange={setDays}
          disabled={resolve.isPending}
          error={validDays ? undefined : texts.validityRange}
          description={
            validDays ? texts.validityUntil(makeupValidUntil(days, timeZone)) : undefined
          }
          w={260}
        />
      )}
      <Textarea
        label={texts.note}
        placeholder={texts.notePlaceholder}
        description={texts.noteHint}
        required
        maxLength={MAX_NOTE}
        rows={4}
        value={note}
        onChange={(event) => setNote(event.currentTarget.value)}
        disabled={resolve.isPending}
      />
      {resolve.error && <Alert color="red">{disputeErrorMessage(resolve.error)}</Alert>}
      <Group justify="flex-end">
        <Button loading={resolve.isPending} disabled={!ready} onClick={submit}>
          {texts.submit}
        </Button>
      </Group>
    </Stack>
  );
}

function Resolution({ dispute, timeZone }: { dispute: Dispute; timeZone: string }) {
  const texts = pl.disputes;
  return (
    <Paper withBorder radius="md" p="sm">
      <Stack gap="xs">
        <Text fw={600}>{texts.drawer.resolvedTitle}</Text>
        {dispute.resolution && (
          <Group gap="xs">
            <Badge variant="light" color={resolutionColors[dispute.resolution]}>
              {texts.resolutions[dispute.resolution]}
            </Badge>
            {dispute.resolutionEffect && (
              <Text size="sm">{texts.effects[dispute.resolutionEffect]}</Text>
            )}
          </Group>
        )}
        {dispute.resolutionNote && (
          <Row label={texts.drawer.resolvedNote}>
            <Text style={{ whiteSpace: 'pre-wrap' }}>{dispute.resolutionNote}</Text>
          </Row>
        )}
        <Row label={texts.drawer.resolvedBy}>
          <Text>{decidedBy(dispute, timeZone)}</Text>
        </Row>
      </Stack>
    </Paper>
  );
}

/**
 * The client's dispute with the session and how it was paid for. An open one is decided here: the
 * administrator sees what each decision would do to the client's package before choosing it.
 */
export function DisputeDrawer({ dispute: listed, timeZone, onClose }: DisputeDrawerProps) {
  const texts = pl.disputes;
  const details = useQuery({
    queryKey: disputeKeys.detail(listed.id),
    queryFn: () => getDispute(listed.id),
  });
  const dispute = details.data ?? listed;

  return (
    <Drawer
      opened
      onClose={onClose}
      position="right"
      size={560}
      title={
        <Text fw={700} size="lg">
          {texts.drawer.title}
        </Text>
      }
    >
      <Stack>
        <Row label={texts.drawer.customer}>
          <Group gap="xs">
            <Text fw={500}>{dispute.customerName}</Text>
            {dispute.customerKind === 'CHILD' && (
              <Badge size="sm" variant="light" color="grape">
                {texts.child}
              </Badge>
            )}
            {dispute.customerAccountId && (
              <Anchor
                component={Link}
                to={paths.accountDetails(dispute.customerAccountId, dispute.customerId)}
                size="sm"
              >
                {texts.drawer.openAccount}
              </Anchor>
            )}
          </Group>
        </Row>
        <Row label={texts.drawer.session}>
          <Text>{dispute.title}</Text>
          <Text size="sm" c="dimmed">
            {formatSession(dispute, timeZone)} · {dispute.serviceTypeName}
          </Text>
        </Row>
        <Row label={texts.drawer.trainer}>
          <Text>{trainerNames(dispute)}</Text>
        </Row>
        <Row label={texts.drawer.attendance}>
          <Text>{texts.attendance[dispute.attendance]}</Text>
          <Text size="sm" c="dimmed">
            {dispute.trainerConfirmedAt
              ? texts.drawer.confirmed(formatDateTime(dispute.trainerConfirmedAt, timeZone))
              : texts.drawer.notConfirmed}
          </Text>
        </Row>
        <Row label={texts.drawer.reason}>
          <Text style={{ whiteSpace: 'pre-wrap' }}>{dispute.reason}</Text>
          <Text size="sm" c="dimmed">
            {formatDateTime(dispute.raisedAt, timeZone)}
            {dispute.raisedByName ? ` · ${texts.reportedBy(dispute.raisedByName)}` : ''}
          </Text>
        </Row>
        {dispute.options && (
          <Row label={texts.drawer.funding}>
            <Text>{fundingText(dispute.options, timeZone)}</Text>
          </Row>
        )}
        {details.isPending ? (
          <Text size="sm">{texts.drawer.loading}</Text>
        ) : details.isError ? (
          <Alert color="red">
            {texts.drawer.loadError}{' '}
            <Button variant="subtle" size="compact-sm" onClick={() => void details.refetch()}>
              {texts.retry}
            </Button>
          </Alert>
        ) : dispute.status === 'RESOLVED' ? (
          <Resolution dispute={dispute} timeZone={timeZone} />
        ) : dispute.options ? (
          <ResolutionForm
            dispute={dispute}
            options={dispute.options}
            timeZone={timeZone}
            onResolved={onClose}
          />
        ) : null}
      </Stack>
    </Drawer>
  );
}
