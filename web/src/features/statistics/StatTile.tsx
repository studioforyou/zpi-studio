import type { ReactNode } from 'react';
import { Paper, Stack, Text } from '@mantine/core';

type StatTileProps = {
  label: string;
  value: string;
  /** Lines below the value, e.g. what it is made of. */
  details?: (string | null | undefined)[];
  /** A figure below the value, e.g. a meter. */
  children?: ReactNode;
};

/** One headline number of a report. */
export function StatTile({ label, value, details = [], children }: StatTileProps) {
  return (
    <Paper withBorder radius="md" p="md" role="group" aria-label={label}>
      <Stack gap={6}>
        <Text size="sm" fw={500} c="dimmed">
          {label}
        </Text>
        <Text fz={32} fw={600} lh={1.1}>
          {value}
        </Text>
        {children}
        {details
          .filter((detail): detail is string => Boolean(detail))
          .map((detail) => (
            <Text key={detail} size="sm" c="dimmed">
              {detail}
            </Text>
          ))}
      </Stack>
    </Paper>
  );
}
