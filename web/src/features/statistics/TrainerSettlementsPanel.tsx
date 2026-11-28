import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Paper,
  Popover,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { formatMoney } from '@/features/passes/passFormat';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import {
  addMonths,
  DEFAULT_TIME_ZONE,
  formatDate,
  formatMonth,
  todayIn,
} from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { ChartCard } from './ChartCard';
import { formatWorkedTime } from './statisticsMath';
import { monthlyPeriod } from './trainerHoursExport';
import { TrainerHoursSessions } from './TrainerHoursDetails';
import { TrainerSettlementForm } from './TrainerSettlementForm';
import { TrainerPayoutForm } from './TrainerPayoutForm';
import {
  exportSettlements,
  getSettlement,
  listSettlements,
  settlementKeys,
  type SettlementStatus,
  type SettlementSummary,
  type TrainerPayout,
  type TrainerSettlement,
} from './trainerSettlementsApi';

const texts = pl.statistics.settlements;
const colors = { UNPAID: 'orange', PARTIAL: 'blue', PAID: 'teal' };

function Status({ row, compact = false }: { row: SettlementSummary; compact?: boolean }) {
  return (
    <Badge
      variant="light"
      color={colors[row.status]}
      title={texts.statuses[row.status]}
      tt="none"
      size="sm"
    >
      {row.amount.amountMinor === 0
        ? texts.zeroAmount
        : compact
          ? texts.shortStatuses[row.status]
          : texts.statuses[row.status]}
    </Badge>
  );
}

export function TrainerSettlementsPanel() {
  const [params, setParams] = useSearchParams();
  const client = useQueryClient();
  const studio = useQuery({ queryKey: scheduleKeys.studio, queryFn: getStudio });
  const today = todayIn(studio.data?.timeZone ?? DEFAULT_TIME_ZONE);
  const month = params.get('settlementMonth') ?? addMonths(today, -1).slice(0, 7);
  const id = params.get('settlement');
  const [staffId, setStaffId] = useState<string | null>(null);
  const [status, setStatus] = useState<SettlementStatus | null>(null);
  const [create, setCreate] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const valid = !!monthlyPeriod(month) && month >= '1900-01' && month <= today.slice(0, 7);
  const list = useQuery({
    queryKey: settlementKeys.month(month),
    queryFn: () => listSettlements(month),
    enabled: valid,
  });
  const rows = (list.data ?? []).filter(
    (row) => (!staffId || row.staffId === staffId) && (!status || row.status === status),
  );
  function update(values: Record<string, string | null>) {
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        for (const [key, value] of Object.entries(values)) {
          if (value !== null) next.set(key, value);
          else next.delete(key);
        }
        return next;
      },
      { replace: true },
    );
  }
  async function saved(data: TrainerSettlement) {
    client.setQueryData(settlementKeys.detail(data.summary.id), data);
    setCreate(false);
    update({ settlement: data.summary.id });
    await client.invalidateQueries({ queryKey: settlementKeys.all });
  }
  async function download(mode: 'pdf' | 'csv' | 'print') {
    const printWindow = mode === 'print' ? window.open('', '_blank') : null;
    if (mode === 'print' && !printWindow) {
      setError(pl.statistics.hours.export.popupBlocked);
      return;
    }
    setExporting(true);
    setError(null);
    try {
      const all = await exportSettlements(month);
      const selected = all.filter(
        ({ summary: row }) =>
          (!staffId || row.staffId === staffId) && (!status || row.status === status),
      );
      if (!selected.length) {
        printWindow?.close();
        setError(texts.exportEmpty);
        return;
      }
      const { exportSettlementFiles } = await import('./trainerSettlementsExport');
      await exportSettlementFiles(selected, mode, !!staffId, printWindow ?? undefined);
    } catch {
      printWindow?.close();
      setError(texts.exportError);
    } finally {
      setExporting(false);
    }
  }
  const remaining = new Map<string, number>();
  for (const row of rows)
    remaining.set(
      row.remaining.currency,
      (remaining.get(row.remaining.currency) ?? 0) + row.remaining.amountMinor,
    );
  return (
    <Stack gap="md">
      <Paper withBorder radius="md" p="md">
        <Stack gap="md">
          <Group justify="space-between" align="flex-end">
            <Group align="flex-end">
              <TextInput
                type="month"
                label={texts.month}
                value={month}
                min="1900-01"
                max={today.slice(0, 7)}
                onChange={(event) => {
                  setStaffId(null);
                  setStatus(null);
                  update({ settlementMonth: event.currentTarget.value, settlement: null });
                }}
                error={!valid ? texts.invalidMonth : undefined}
                disabled={exporting}
              />
              <Select
                label={texts.employee}
                placeholder={texts.allEmployees}
                data={(list.data ?? []).map((row) => ({
                  value: row.staffId,
                  label: row.displayName,
                }))}
                value={staffId}
                onChange={setStaffId}
                clearable
                searchable
                disabled={exporting}
              />
              <Select
                label={texts.status}
                placeholder={texts.allStatuses}
                data={Object.entries(texts.statuses).map(([value, label]) => ({ value, label }))}
                value={status}
                onChange={(value) => setStatus(value as SettlementStatus | null)}
                clearable
                disabled={exporting}
              />
            </Group>
          </Group>
          <Group justify="space-between">
            <Group gap="xs">
              <Button
                variant="light"
                size="xs"
                onClick={() => void download('pdf')}
                disabled={exporting || !rows.length}
              >
                {texts.exportPdf}
              </Button>
              <Button
                variant="light"
                size="xs"
                onClick={() => void download('csv')}
                disabled={exporting || !rows.length}
              >
                {texts.exportCsv}
              </Button>
              <Button
                variant="default"
                size="xs"
                onClick={() => void download('print')}
                disabled={exporting || !rows.length}
              >
                {texts.print}
              </Button>
              {exporting && <Loader size="xs" />}
            </Group>
            <Button size="sm" onClick={() => setCreate(true)} disabled={!valid || !list.data}>
              {texts.create}
            </Button>
            <Popover width={350} position="bottom-end" withArrow>
              <Popover.Target>
                <ActionIcon radius="xl" variant="light" aria-label={texts.rulesTitle}>
                  i
                </ActionIcon>
              </Popover.Target>
              <Popover.Dropdown>
                <Text size="sm">{texts.rules}</Text>
              </Popover.Dropdown>
            </Popover>
          </Group>
        </Stack>
      </Paper>
      {error && <Alert color="red">{error}</Alert>}
      {list.isError && (
        <Alert color="red">
          <Group justify="space-between">
            <Text size="sm">{texts.loadError}</Text>
            <Button variant="light" onClick={() => void list.refetch()}>
              {texts.refresh}
            </Button>
          </Group>
        </Alert>
      )}
      {list.isPending && valid && <Loader />}
      {list.data && !list.isError && valid && (
        <>
          <Paper withBorder radius="md" px="md" py="sm">
            <Group justify="space-between" gap="sm">
              <Stack gap={1}>
                <Text size="xs" c="dimmed">
                  {texts.remaining}
                </Text>
                <Text fw={600} size="lg">
                  {[...remaining]
                    .map(([currency, amountMinor]) => formatMoney({ currency, amountMinor }))
                    .join(' · ') || '—'}
                </Text>
              </Stack>
              <Group gap="xs">
                <Text size="sm">{texts.statuses.UNPAID}</Text>
                <Badge color="orange" variant="light">
                  {rows.filter((row) => row.status === 'UNPAID').length}
                </Badge>
              </Group>
              <Group gap="xs">
                <Text size="sm">{texts.statuses.PARTIAL}</Text>
                <Badge color="blue" variant="light">
                  {rows.filter((row) => row.status === 'PARTIAL').length}
                </Badge>
              </Group>
            </Group>
          </Paper>
          <ChartCard title={formatMonth(month + '-01')}>
            <Table.ScrollContainer minWidth={600}>
              <Table highlightOnHover verticalSpacing="sm">
                <Table.Thead>
                  <Table.Tr>
                    {[
                      texts.employee,
                      texts.shortHours,
                      texts.shortAmount,
                      texts.paid,
                      texts.remaining,
                      texts.status,
                    ].map((label, index) => (
                      <Table.Th key={index}>{label}</Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((row) => (
                    <Table.Tr key={row.id}>
                      <Table.Td>
                        <UnstyledButton
                          c="blue.8"
                          aria-label={`${texts.open}: ${row.displayName}`}
                          onClick={() => update({ settlement: row.id })}
                        >
                          <Text fw={500} size="sm">
                            {row.displayName}
                          </Text>
                        </UnstyledButton>
                      </Table.Td>
                      <Table.Td>{formatWorkedTime(row.countedSeconds)}</Table.Td>
                      <Table.Td>{formatMoney(row.amount)}</Table.Td>
                      <Table.Td>{formatMoney(row.paid)}</Table.Td>
                      <Table.Td>
                        <Text fw={600} size="sm">
                          {formatMoney(row.remaining)}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Status row={row} compact />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            {!rows.length && (
              <Text c="dimmed" size="sm">
                {texts.empty}
              </Text>
            )}
          </ChartCard>
        </>
      )}
      {create && (
        <TrainerSettlementForm
          month={month}
          today={today}
          occupied={(list.data ?? []).map((row) => row.staffId)}
          onClose={() => setCreate(false)}
          onSaved={(data) => void saved(data)}
          onRefresh={() => list.refetch()}
        />
      )}
      {id && (
        <SettlementDrawer
          key={id}
          id={id}
          today={today}
          onClose={() => update({ settlement: null })}
          onSaved={(data) => void saved(data)}
        />
      )}
    </Stack>
  );
}

function SettlementDrawer({
  id,
  today,
  onClose,
  onSaved,
}: {
  id: string;
  today: string;
  onClose: () => void;
  onSaved: (data: TrainerSettlement) => void;
}) {
  const detail = useQuery({
    queryKey: settlementKeys.detail(id),
    queryFn: () => getSettlement(id),
  });
  const [action, setAction] = useState<'correct' | 'payout' | TrainerPayout | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState(false);
  const data = detail.data;
  const latest = data?.revisions[0];
  function saved(value: TrainerSettlement) {
    setAction(null);
    onSaved(value);
  }
  async function download(mode: 'pdf' | 'csv') {
    setExporting(true);
    setExportError(false);
    try {
      const value = await getSettlement(id);
      const { exportSettlementFiles } = await import('./trainerSettlementsExport');
      await exportSettlementFiles([value], mode, true);
    } catch {
      setExportError(true);
    } finally {
      setExporting(false);
    }
  }
  const when = (date: string) =>
    new Intl.DateTimeFormat('pl-PL', {
      timeZone: latest?.snapshot.report.timeZone ?? DEFAULT_TIME_ZONE,
      dateStyle: 'short',
      timeStyle: 'short',
    }).format(new Date(date));
  return (
    <Drawer
      opened
      position="right"
      size="xl"
      title={data?.summary.displayName ?? texts.details}
      onClose={onClose}
      closeOnEscape={!action}
      closeOnClickOutside={!action}
    >
      {detail.isPending && <Loader />}
      {detail.isError && (
        <Alert color="red">
          <Stack>
            <Text size="sm">{texts.loadError}</Text>
            <Button onClick={() => void detail.refetch()}>{texts.refresh}</Button>
          </Stack>
        </Alert>
      )}
      {data && latest && !detail.isError && (
        <Stack gap="md">
          <Group justify="space-between">
            <Text fw={600}>{formatMonth(data.summary.month + '-01')}</Text>
            <Status row={data.summary} />
          </Group>
          <SimpleGrid cols={3}>
            {[
              [texts.amount, data.summary.amount],
              [texts.paid, data.summary.paid],
              [texts.remaining, data.summary.remaining],
            ].map(([label, money]) => (
              <Stack key={label as string} gap={2}>
                <Text size="xs" c="dimmed">
                  {label as string}
                </Text>
                <Text fw={600}>{formatMoney(money as SettlementSummary['amount'])}</Text>
              </Stack>
            ))}
          </SimpleGrid>
          <Group gap="xs">
            <Button
              onClick={() => setAction('payout')}
              disabled={data.summary.remaining.amountMinor === 0}
            >
              {texts.payout}
            </Button>
            <Button variant="light" onClick={() => setAction('correct')}>
              {texts.correction}
            </Button>
            <Button
              variant="default"
              size="xs"
              onClick={() => void download('pdf')}
              disabled={exporting}
            >
              {texts.exportPdf}
            </Button>
            <Button
              variant="default"
              size="xs"
              onClick={() => void download('csv')}
              disabled={exporting}
            >
              {texts.exportCsv}
            </Button>
          </Group>
          {exportError && <Alert color="red">{texts.exportError}</Alert>}
          <Tabs defaultValue="payouts" keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="payouts">{texts.payouts}</Tabs.Tab>
              <Tabs.Tab value="hours">{texts.savedHours}</Tabs.Tab>
              <Tabs.Tab value="history">{texts.history}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="payouts" pt="md">
              <Stack gap="sm">
                {!data.payments.length && (
                  <Text size="sm" c="dimmed">
                    {texts.noPayouts}
                  </Text>
                )}
                {data.payments.map((payment) => (
                  <Paper key={payment.id} withBorder radius="md" p="sm">
                    <Stack gap={4}>
                      <Group justify="space-between" align="flex-start">
                        <Stack gap={2}>
                          <Text
                            size="sm"
                            fw={600}
                            td={payment.reversed ? 'line-through' : undefined}
                          >
                            {payment.reversesId ? '−' : ''}
                            {formatMoney(payment.amount)}
                          </Text>
                          <Text size="xs">
                            {payment.reversesId ? texts.reversal : texts.methods[payment.method]} ·{' '}
                            {formatDate(payment.paidOn)}
                          </Text>
                        </Stack>
                        {payment.reversed ? (
                          <Badge color="gray" size="sm">
                            {texts.reversed}
                          </Badge>
                        ) : (
                          !payment.reversesId && (
                            <Button
                              size="compact-xs"
                              variant="subtle"
                              color="red"
                              onClick={() => setAction(payment)}
                            >
                              {texts.reverse}
                            </Button>
                          )
                        )}
                      </Group>
                      {payment.note && (
                        <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                          {payment.note}
                        </Text>
                      )}
                      <Text size="xs" c="dimmed">
                        {texts.recorded}: {when(payment.createdAt)} · {payment.actor}
                      </Text>
                    </Stack>
                  </Paper>
                ))}
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="hours" pt="md">
              <Stack gap="sm">
                <Text fw={600}>{formatWorkedTime(data.summary.countedSeconds)}</Text>
                <Text size="xs" c="dimmed">
                  {texts.savedOn(when(latest.createdAt))} · {latest.actor}
                </Text>
                {data.summary.unconfirmedSeconds > 0 && (
                  <Text size="sm" c="orange.8">
                    {texts.unconfirmed(
                      formatWorkedTime(data.summary.unconfirmedSeconds),
                      data.summary.includeUnconfirmed,
                    )}
                  </Text>
                )}
                <TrainerHoursSessions
                  key={latest.id}
                  instructor={latest.snapshot.report.instructors[0]}
                  report={latest.snapshot.report}
                />
              </Stack>
            </Tabs.Panel>
            <Tabs.Panel value="history" pt="md">
              <Stack gap="sm">
                {data.revisions.map((revision) => (
                  <Paper key={revision.id} withBorder radius="md" p="sm">
                    <Stack gap={4}>
                      <Group justify="space-between">
                        <Text fw={600} size="sm">
                          {texts.revision(revision.revision)}
                        </Text>
                        <Text size="sm">{formatMoney(revision.amount)}</Text>
                      </Group>
                      <Text size="sm">
                        {texts.hours}:{' '}
                        {formatWorkedTime(
                          revision.snapshot.report.totals.seconds -
                            (revision.includeUnconfirmed
                              ? 0
                              : revision.snapshot.report.totals.unconfirmedSeconds),
                        )}
                      </Text>
                      {revision.reason && (
                        <Text size="sm" style={{ overflowWrap: 'anywhere' }}>
                          {revision.reason}
                        </Text>
                      )}
                      <Text size="xs" c="dimmed">
                        {when(revision.createdAt)} · {revision.actor}
                      </Text>
                    </Stack>
                  </Paper>
                ))}
              </Stack>
            </Tabs.Panel>
          </Tabs>
          {action === 'correct' && (
            <TrainerSettlementForm
              month={data.summary.month}
              today={today}
              existing={data}
              occupied={[]}
              onClose={() => setAction(null)}
              onSaved={saved}
              onRefresh={() => detail.refetch()}
            />
          )}
          {(action === 'payout' || (action && typeof action === 'object')) && (
            <TrainerPayoutForm
              settlement={data}
              today={today}
              reverse={typeof action === 'object' ? action : undefined}
              onClose={() => setAction(null)}
              onSaved={saved}
              onRefresh={() => detail.refetch()}
            />
          )}
        </Stack>
      )}
    </Drawer>
  );
}
