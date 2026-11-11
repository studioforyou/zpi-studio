import { useState } from 'react';
import {
  Box,
  Group,
  Progress,
  SegmentedControl,
  Stack,
  Table,
  Text,
  UnstyledButton,
} from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { ClassAttendanceClass } from './attendanceReportsApi';
import { ChartCard } from './ChartCard';
import { chartColors } from './chartColors';
import {
  attendanceRate,
  averageAttendees,
  formatNumber,
  formatPercent,
  occupancy,
  sortSummaries,
  summarizeClasses,
  type ClassGrouping,
  type ClassSortKey,
  type ClassSummary,
} from './statisticsMath';

const texts = pl.statistics.attendance;

const groupings: ClassGrouping[] = ['classes', 'services', 'groups'];

type Column = { key: ClassSortKey; label: string; numeric: boolean };

const columns: Column[] = [
  { key: 'name', label: texts.columns.name, numeric: false },
  { key: 'events', label: texts.columns.events, numeric: true },
  { key: 'attendees', label: texts.columns.attendees, numeric: true },
  { key: 'occupancy', label: texts.columns.occupancy, numeric: false },
  { key: 'attendance', label: texts.columns.attendance, numeric: true },
  { key: 'waitlisted', label: texts.columns.waitlisted, numeric: true },
];

/** Occupancy and attendance of each class, kind of classes or fixed group; fullest first. */
export function ClassesTable({ classes }: { classes: ClassAttendanceClass[] }) {
  const [grouping, setGrouping] = useState<ClassGrouping>('classes');
  const [sort, setSort] = useState<{ key: ClassSortKey; descending: boolean }>({
    key: 'occupancy',
    descending: true,
  });
  const rows = sortSummaries(summarizeClasses(classes, grouping), sort.key, sort.descending);

  function sortBy(key: ClassSortKey) {
    setSort((current) =>
      current.key === key
        ? { key, descending: !current.descending }
        : // Names read best A to Z, numbers highest first.
          { key, descending: key !== 'name' },
    );
  }

  return (
    <ChartCard
      title={texts.tableTitle}
      actions={
        <SegmentedControl
          size="xs"
          aria-label={texts.groupByLabel}
          value={grouping}
          onChange={(value) => setGrouping(value as ClassGrouping)}
          data={groupings.map((value) => ({ value, label: texts.groupBy[value] }))}
        />
      }
    >
      <Table.ScrollContainer minWidth={720}>
        <Table verticalSpacing="xs" highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              {columns.map((column) => (
                <Table.Th
                  key={column.key}
                  aria-sort={
                    sort.key === column.key
                      ? sort.descending
                        ? 'descending'
                        : 'ascending'
                      : undefined
                  }
                  style={{
                    textAlign: column.numeric ? 'right' : 'left',
                    width: column.key === 'occupancy' ? '28%' : undefined,
                  }}
                >
                  <UnstyledButton
                    onClick={() => sortBy(column.key)}
                    aria-label={texts.sortBy(column.label)}
                    fz="sm"
                    fw={600}
                  >
                    {column.label}
                    {sort.key === column.key ? (sort.descending ? ' ↓' : ' ↑') : ''}
                  </UnstyledButton>
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((row) => (
              <ClassRow key={row.key} row={row} />
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
    </ChartCard>
  );
}

function ClassRow({ row }: { row: ClassSummary }) {
  const fill = occupancy(row.counts);
  const average = averageAttendees(row.counts);
  return (
    <Table.Tr>
      <Table.Td>
        <Group gap={8} wrap="nowrap" align="flex-start">
          <Box
            mt={6}
            w={8}
            h={8}
            style={{
              flexShrink: 0,
              borderRadius: '50%',
              background: row.color ?? 'var(--mantine-color-gray-4)',
            }}
          />
          <Stack gap={0}>
            <Text size="sm" fw={500}>
              {row.name}
            </Text>
            {row.detail && (
              <Text size="xs" c="dimmed">
                {row.detail}
              </Text>
            )}
          </Stack>
        </Group>
      </Table.Td>
      <Table.Td style={numeric}>{formatNumber(row.counts.events)}</Table.Td>
      <Table.Td style={numeric}>{average === null ? '—' : formatNumber(average, 1)}</Table.Td>
      <Table.Td>
        {fill === null ? (
          <Text size="sm" c="dimmed">
            {texts.noLimit}
          </Text>
        ) : (
          <Group gap="sm" wrap="nowrap">
            <Progress
              aria-hidden
              value={Math.min(100, fill * 100)}
              color={chartColors.occupancy}
              size="sm"
              radius="xl"
              style={{ flex: 1 }}
              styles={{ root: { backgroundColor: chartColors.track } }}
            />
            <Group gap={6} wrap="nowrap" justify="flex-end" miw={92}>
              <Text size="sm" style={numeric}>
                {formatPercent(fill)}
              </Text>
              <Text size="xs" c="dimmed" style={numeric}>
                {`${formatNumber(row.counts.placesBooked)}/${formatNumber(row.counts.places)}`}
              </Text>
            </Group>
          </Group>
        )}
      </Table.Td>
      <Table.Td style={numeric}>{formatPercent(attendanceRate(row.counts))}</Table.Td>
      <Table.Td style={numeric}>{formatNumber(row.counts.waitlisted)}</Table.Td>
    </Table.Tr>
  );
}

const numeric = {
  textAlign: 'right',
  fontVariantNumeric: 'tabular-nums',
  whiteSpace: 'nowrap',
} as const;
