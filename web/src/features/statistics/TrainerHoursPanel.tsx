import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Paper,
  Popover,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  UnstyledButton,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { listStaff, scheduleKeys } from '@/features/schedule/scheduleApi';
import { pl } from '@/shared/i18n/pl';
import { ChartCard } from './ChartCard';
import { PeriodPicker } from './PeriodPicker';
import { ReportFrame } from './ReportFrame';
import { StatTile } from './StatTile';
import { formatNumber, formatWorkedTime } from './statisticsMath';
import { getTrainerHours, workKinds, type WorkKind } from './trainerHoursApi';
import { TrainerHoursDetails } from './TrainerHoursDetails';
import { TrainerHoursExportModal } from './TrainerHoursExportModal';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.hours;

/** Worked time of each instructor, using the period picker already shared by the reports. */
export function TrainerHoursPanel({ filters }: { filters: StatisticsFilters }) {
  const [params, setParams] = useSearchParams();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [sort, setSort] = useState<'hours' | 'name'>('hours');
  const [exportOpened, setExportOpened] = useState(false);
  const kind = workKinds.find((value) => value === params.get('hoursKind')) ?? null;
  const staffId = params.get('hoursStaff');
  const confirmedOnly = params.get('hoursConfirmed') === 'true';
  const query = { ...filters.period.period, kind, staffId, confirmedOnly };
  const staff = useQuery({ queryKey: scheduleKeys.staff, queryFn: listStaff });
  const report = useQuery({
    queryKey: ['reports', 'trainer-hours', query],
    queryFn: () => getTrainerHours(query),
    enabled: filters.period.valid !== null,
    placeholderData: keepPreviousData,
  });

  function update(name: string, value: string | null) {
    setSelectedId(null);
    setParams(
      (current) => {
        const next = new URLSearchParams(current);
        if (value) next.set(name, value);
        else next.delete(name);
        return next;
      },
      { replace: true },
    );
  }

  return (
    <Stack gap="md">
      <Paper withBorder radius="md" p="md" component="section" aria-label={texts.filters}>
        <Stack gap="md">
          <Group justify="space-between" align="flex-start">
            <PeriodPicker state={filters.period} />
            <Button variant="light" onClick={() => setExportOpened(true)} disabled={!staff.data}>
              {texts.export.title}
            </Button>
          </Group>
          <Group align="flex-end" gap="md">
            <Select
              label={texts.instructor}
              placeholder={texts.allInstructors}
              data={(staff.data ?? []).map((member) => ({
                value: member.id,
                label: member.displayName,
              }))}
              value={staffId}
              onChange={(value) => update('hoursStaff', value)}
              searchable
              clearable
              nothingFoundMessage={texts.noResults}
              style={{ flex: '1 1 200px' }}
            />
            <Select
              label={texts.kind}
              value={kind ?? 'all'}
              data={[
                { value: 'all', label: texts.allKinds },
                ...workKinds.map((value) => ({ value, label: texts.kinds[value] })),
              ]}
              onChange={(value) =>
                update('hoursKind', value === 'all' ? null : (value as WorkKind))
              }
              style={{ flex: '1 1 200px' }}
            />
            <Switch
              label={texts.confirmedOnly}
              checked={confirmedOnly}
              onChange={(event) =>
                update('hoursConfirmed', event.currentTarget.checked ? 'true' : null)
              }
              mb={8}
            />
          </Group>
          {staff.isError && (
            <Text size="sm" c="orange">
              {texts.staffError}
            </Text>
          )}
        </Stack>
      </Paper>
      {exportOpened && (
        <TrainerHoursExportModal
          initialMonth={filters.period.period.to.slice(0, 7)}
          initialStaffId={staffId}
          staff={staff.data ?? []}
          onClose={() => setExportOpened(false)}
        />
      )}
      {filters.period.valid !== null && (
        <ReportFrame report={report} stale={report.isPlaceholderData} loadError={texts.loadError}>
          {(data) => {
            const rows = [...data.instructors].sort((a, b) =>
              sort === 'name'
                ? a.displayName.localeCompare(b.displayName, 'pl')
                : b.counts.seconds - a.counts.seconds ||
                  a.displayName.localeCompare(b.displayName, 'pl'),
            );
            const selected = data.instructors.find((row) => row.id === selectedId);
            return (
              <Stack
                gap="md"
                aria-busy={report.isFetching}
                style={{ pointerEvents: report.isPlaceholderData ? 'none' : undefined }}
              >
                <SimpleGrid cols={{ base: 1, sm: 3 }}>
                  <StatTile
                    label={texts.total}
                    value={formatWorkedTime(data.totals.seconds)}
                    details={[
                      `${texts.lead}: ${formatWorkedTime(data.totals.leadSeconds)} · ${texts.assistant}: ${formatWorkedTime(data.totals.assistantSeconds)}`,
                    ]}
                  />
                  <StatTile
                    label={texts.events}
                    value={formatNumber(data.totals.events)}
                    details={[
                      texts.workingInstructors(
                        data.instructors.filter((row) => row.counts.events > 0).length,
                      ),
                    ]}
                  />
                  <StatTile
                    label={texts.unconfirmed}
                    value={formatWorkedTime(data.totals.unconfirmedSeconds)}
                    details={[
                      data.totals.unconfirmedEvents
                        ? texts.unconfirmedCount(data.totals.unconfirmedEvents)
                        : texts.allConfirmed,
                    ]}
                  />
                </SimpleGrid>
                {data.totals.events === 0 && <Alert color="gray">{texts.empty}</Alert>}
                <ChartCard title={texts.tableTitle} actions={<HoursRules />}>
                  <Table.ScrollContainer minWidth={600}>
                    <Table verticalSpacing="sm" highlightOnHover>
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th aria-sort={sort === 'name' ? 'ascending' : undefined}>
                            <UnstyledButton fw={600} fz="sm" onClick={() => setSort('name')}>
                              {texts.instructor}
                              {sort === 'name' ? ' ↑' : ''}
                            </UnstyledButton>
                          </Table.Th>
                          <Table.Th ta="right">{texts.lead}</Table.Th>
                          <Table.Th ta="right">{texts.assistant}</Table.Th>
                          <Table.Th
                            ta="right"
                            aria-sort={sort === 'hours' ? 'descending' : undefined}
                          >
                            <UnstyledButton fw={600} fz="sm" onClick={() => setSort('hours')}>
                              {texts.totalShort}
                              {sort === 'hours' ? ' ↓' : ''}
                            </UnstyledButton>
                          </Table.Th>
                          <Table.Th ta="right">{texts.events}</Table.Th>
                          <Table.Th>
                            <Text span size="sm" fw={600}>
                              {texts.details}
                            </Text>
                          </Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {rows.map((row) => (
                          <Table.Tr key={row.id}>
                            <Table.Td>
                              <Group gap="xs">
                                <Text size="sm" fw={500}>
                                  {row.displayName}
                                </Text>
                                {!row.active && (
                                  <Badge color="gray" size="xs">
                                    {texts.inactive}
                                  </Badge>
                                )}
                              </Group>
                            </Table.Td>
                            <Table.Td ta="right">
                              {formatWorkedTime(row.counts.leadSeconds)}
                            </Table.Td>
                            <Table.Td ta="right">
                              {formatWorkedTime(row.counts.assistantSeconds)}
                            </Table.Td>
                            <Table.Td ta="right">
                              <Text size="sm" fw={600}>
                                {formatWorkedTime(row.counts.seconds)}
                              </Text>
                              {row.counts.unconfirmedEvents > 0 && (
                                <Text size="xs" c="orange.8">
                                  {texts.includingUnconfirmed(
                                    formatWorkedTime(row.counts.unconfirmedSeconds),
                                  )}
                                </Text>
                              )}
                            </Table.Td>
                            <Table.Td ta="right">{row.counts.events}</Table.Td>
                            <Table.Td>
                              <Button
                                variant="subtle"
                                size="compact-sm"
                                onClick={() => setSelectedId(row.id)}
                                aria-label={texts.detailsFor(row.displayName)}
                                disabled={row.sessions.length === 0}
                              >
                                {texts.details} →
                              </Button>
                            </Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                  {rows.length === 0 && (
                    <Text size="sm" c="dimmed">
                      {texts.noResults}
                    </Text>
                  )}
                </ChartCard>
                {selected && (
                  <TrainerHoursDetails
                    key={`${selected.id}-${data.from}-${data.to}-${kind}-${confirmedOnly}`}
                    instructor={selected}
                    report={data}
                    onClose={() => setSelectedId(null)}
                  />
                )}
              </Stack>
            );
          }}
        </ReportFrame>
      )}
    </Stack>
  );
}

function HoursRules() {
  return (
    <Popover width={370} position="bottom-end" withArrow>
      <Popover.Target>
        <ActionIcon variant="light" radius="xl" aria-label={texts.rulesTitle}>
          i
        </ActionIcon>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap="xs">
          <Text size="sm" fw={600}>
            {texts.rulesTitle}
          </Text>
          <Text size="sm">{texts.rules}</Text>
          <Text size="sm">{texts.counting}</Text>
          <Text size="sm">{texts.confirmationRule}</Text>
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}
