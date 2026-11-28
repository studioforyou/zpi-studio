import { useState } from 'react';
import {
  Accordion,
  Alert,
  Button,
  Checkbox,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation, useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { ApiError } from '@/shared/api/ApiError';
import { formatMonth } from '@/features/schedule/studioTime';
import { formatWorkedTime } from './statisticsMath';
import { getTrainerHours } from './trainerHoursApi';
import { monthlyPeriod } from './trainerHoursExport';
import { TrainerHoursSessions } from './TrainerHoursDetails';
import {
  approveSettlement,
  correctSettlement,
  previewSettlement,
  previewSettlementRevision,
  settlementAmount,
  settlementError,
  type TrainerSettlement,
} from './trainerSettlementsApi';

const texts = pl.statistics.settlements;

export function TrainerSettlementForm({
  month,
  today,
  existing,
  occupied,
  onClose,
  onSaved,
  onRefresh,
}: {
  month: string;
  today: string;
  existing?: TrainerSettlement;
  occupied: string[];
  onClose: () => void;
  onSaved: (data: TrainerSettlement) => void;
  onRefresh: () => Promise<unknown>;
}) {
  const latest = existing?.revisions[0];
  const [staffId, setStaffId] = useState<string | null>(existing?.summary.staffId ?? null);
  const [amount, setAmount] = useState(
    latest ? (latest.amount.amountMinor / 100).toFixed(2).replace('.', ',') : '',
  );
  const [include, setInclude] = useState(latest?.includeUnconfirmed ?? false);
  const [reason, setReason] = useState('');
  const [refreshHours, setRefreshHours] = useState(false);
  // A retry, including after an uncertain network failure, keeps the same operation UUID.
  const [operationId] = useState(() => crypto.randomUUID());
  const period = monthlyPeriod(month)!;
  const choices = useQuery({
    queryKey: ['settlement-instructors', month],
    queryFn: () => getTrainerHours({ ...period, kind: null, staffId: null, confirmedOnly: false }),
    enabled: !existing,
  });
  const preview = useQuery({
    queryKey: ['settlement-preview', existing?.summary.id ?? month, staffId],
    queryFn: () =>
      existing
        ? previewSettlementRevision(existing.summary.id)
        : previewSettlement(month, staffId!),
    enabled: !!staffId && (!existing || refreshHours),
  });
  const basis = existing && !refreshHours ? latest?.snapshot : preview.data?.snapshot;
  const currency = existing?.summary.amount.currency ?? preview.data?.currency;
  const minor = settlementAmount(amount);
  const tooSmall = existing && minor !== null && minor < existing.summary.paid.amountMinor;
  const validInput =
    !!basis &&
    !!currency &&
    minor !== null &&
    !tooSmall &&
    (!existing || !!reason.trim()) &&
    !preview.isFetching &&
    (!staffId || (existing && !refreshHours) || !preview.isError);
  const save = useMutation({
    retry: false,
    mutationFn: async () => {
      const money = { amountMinor: minor!, currency: currency! };
      return existing
        ? correctSettlement(existing.summary.id, {
            operationId,
            version: existing.summary.version,
            amount: money,
            includeUnconfirmed: include,
            reason: reason.trim(),
            fingerprint: refreshHours ? preview.data!.fingerprint : null,
          })
        : approveSettlement({
            operationId,
            month,
            staffId: staffId!,
            amount: money,
            includeUnconfirmed: include,
            fingerprint: preview.data!.fingerprint,
            note: reason.trim(),
          });
    },
    onSuccess: onSaved,
  });
  const counts = basis?.report.totals;
  async function refresh() {
    await onRefresh();
    if (!existing || refreshHours) await preview.refetch();
    save.reset();
  }

  return (
    <Modal
      opened
      title={existing ? texts.correction : texts.create}
      onClose={onClose}
      size="lg"
      centered
      closeOnClickOutside={!save.isPending}
      closeOnEscape={!save.isPending}
      withCloseButton={!save.isPending}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          {formatMonth(month + '-01')}
        </Text>
        {!existing && (
          <Select
            label={texts.employee}
            placeholder={texts.employee}
            searchable
            value={staffId}
            onChange={setStaffId}
            disabled={save.isPending}
            data={(choices.data?.instructors ?? [])
              .filter((row) => !occupied.includes(row.id))
              .map((row) => ({ value: row.id, label: row.displayName }))}
            nothingFoundMessage={texts.noResults}
          />
        )}
        {existing && <Text fw={600}>{existing.summary.displayName}</Text>}
        {existing && (
          <Checkbox
            label={texts.refreshHours}
            checked={refreshHours}
            onChange={(event) => setRefreshHours(event.currentTarget.checked)}
            disabled={save.isPending}
          />
        )}
        {month === today.slice(0, 7) && (!existing || refreshHours) && (
          <Text size="xs" c="orange.8">
            {texts.currentMonth}
          </Text>
        )}
        {(choices.isError || preview.isError) && <Alert color="red">{texts.loadError}</Alert>}
        {preview.isFetching && <Loader size="sm" />}
        {counts && (
          <Stack gap="xs">
            <Group justify="space-between">
              <Text size="sm">{texts.hours}</Text>
              <Text fw={600}>
                {formatWorkedTime(counts.seconds - (include ? 0 : counts.unconfirmedSeconds))}
              </Text>
            </Group>
            {counts.unconfirmedSeconds > 0 && (
              <Checkbox
                label={texts.includeUnconfirmedTime(formatWorkedTime(counts.unconfirmedSeconds))}
                checked={include}
                onChange={(event) => setInclude(event.currentTarget.checked)}
                disabled={save.isPending}
              />
            )}
            <TextInput
              label={`${texts.amount}${currency ? ` (${currency})` : ''}`}
              description={texts.manualAmount}
              value={amount}
              onChange={(event) => setAmount(event.currentTarget.value)}
              inputMode="decimal"
              disabled={save.isPending}
              error={
                tooSmall
                  ? texts.belowPaid
                  : amount && minor === null
                    ? texts.invalidAmount
                    : undefined
              }
            />
            <Accordion variant="separated">
              <Accordion.Item value="sessions">
                <Accordion.Control>{texts.liveHours}</Accordion.Control>
                <Accordion.Panel>
                  <TrainerHoursSessions
                    instructor={basis!.report.instructors[0]}
                    report={basis!.report}
                  />
                </Accordion.Panel>
              </Accordion.Item>
              {!existing && (
                <Accordion.Item value="note">
                  <Accordion.Control>{texts.note}</Accordion.Control>
                  <Accordion.Panel>
                    <Textarea
                      aria-label={texts.note}
                      value={reason}
                      onChange={(event) => setReason(event.currentTarget.value)}
                      maxLength={1000}
                      disabled={save.isPending}
                      rows={2}
                    />
                  </Accordion.Panel>
                </Accordion.Item>
              )}
            </Accordion>
          </Stack>
        )}
        {existing && (
          <Textarea
            label={texts.reason}
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
            required
            maxLength={1000}
            disabled={save.isPending}
            rows={2}
          />
        )}
        {save.isError && (
          <Alert color="red" role="alert">
            <Stack gap="xs">
              <Text size="sm">{settlementError(save.error)}</Text>
              <Button variant="light" size="xs" onClick={() => void refresh()}>
                {texts.refresh}
              </Button>
            </Stack>
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={save.isPending}>
            {texts.cancel}
          </Button>
          <Button
            onClick={() => save.mutate()}
            loading={save.isPending}
            disabled={
              !validInput ||
              (save.error instanceof ApiError && save.error.code === 'SETTLEMENT_SOURCE_CHANGED')
            }
          >
            {existing ? texts.saveCorrection : texts.approve}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
