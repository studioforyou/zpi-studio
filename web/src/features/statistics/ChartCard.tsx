import type { ReactNode } from 'react';
import { Box, Group, Paper, Stack, Text, Title } from '@mantine/core';

type ChartCardProps = {
  title: string;
  subtitle?: string;
  /** Controls of this card only, e.g. how its table is grouped. */
  actions?: ReactNode;
  children: ReactNode;
};

/** A titled card holding one chart or table of a report. */
export function ChartCard({ title, subtitle, actions, children }: ChartCardProps) {
  return (
    <Paper withBorder radius="md" p="md" component="section" aria-label={title}>
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start" gap="sm">
          <Stack gap={2}>
            <Title order={4}>{title}</Title>
            {subtitle && (
              <Text size="sm" c="dimmed">
                {subtitle}
              </Text>
            )}
          </Stack>
          {actions}
        </Group>
        {children}
      </Stack>
    </Paper>
  );
}

export type TooltipRow = { key: string; label: string; value: string; color: string };

/** Tooltip of a chart: the period, then each series' value with a short line of its colour. */
export function TooltipCard({ title, rows }: { title: string; rows: TooltipRow[] }) {
  return (
    <Paper withBorder shadow="sm" radius="sm" px="sm" py={6}>
      <Text size="xs" c="dimmed" mb={4}>
        {title}
      </Text>
      <Stack gap={2}>
        {rows.map((row) => (
          <Group key={row.key} gap={8} wrap="nowrap">
            <Box w={12} h={2} style={{ background: row.color, borderRadius: 1 }} />
            <Text size="sm" fw={600}>
              {row.value}
            </Text>
            <Text size="sm" c="dimmed">
              {row.label}
            </Text>
          </Group>
        ))}
      </Stack>
    </Paper>
  );
}
