import { Box, Group, Text, Tooltip } from '@mantine/core';
import { formatNumber, formatPercent, ratio } from './statisticsMath';

export type SplitPart = {
  key: string;
  label: string;
  value: number;
  color: string;
  /** Text readable on `color`. */
  textColor: string;
};

type SplitBarProps = {
  parts: SplitPart[];
  /** `tall` prints each part's share on it where it fits; `thin` is a plain figure for table rows. */
  variant: 'tall' | 'thin';
  label: string;
};

/** Narrower parts print no share; the tooltip and the numbers next to the bar still give it. */
const MIN_LABELLED_SHARE = 0.05;

/** One whole split into parts, e.g. what became of the booked places. */
export function SplitBar({ parts, variant, label }: SplitBarProps) {
  const total = parts.reduce((sum, part) => sum + part.value, 0);
  const tall = variant === 'tall';
  const visible = parts.filter((part) => part.value > 0);
  return (
    <Group
      role="img"
      aria-label={label}
      gap={2}
      wrap="nowrap"
      h={tall ? 28 : 8}
      w="100%"
      style={{ borderRadius: 4, overflow: 'hidden' }}
    >
      {total === 0 ? (
        <Box h="100%" w="100%" bg="var(--mantine-color-gray-2)" />
      ) : (
        visible.map((part) => {
          const share = ratio(part.value, total) ?? 0;
          return (
            <Tooltip
              key={part.key}
              label={`${part.label}: ${formatNumber(part.value)} (${formatPercent(share)})`}
              withArrow
            >
              <Box
                h="100%"
                style={{
                  // Widths follow the values; a fixed padding would distort small parts.
                  flex: `${part.value} 1 0`,
                  minWidth: 2,
                  background: part.color,
                  borderRadius: tall ? 4 : 0,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {tall && share >= MIN_LABELLED_SHARE && (
                  <Text size="xs" fw={600} c={part.textColor} style={{ whiteSpace: 'nowrap' }}>
                    {formatPercent(share)}
                  </Text>
                )}
              </Box>
            </Tooltip>
          );
        })
      )}
    </Group>
  );
}

/** Names of the parts in bar order, each with its colour. */
export function SplitLegend({ parts }: { parts: SplitPart[] }) {
  return (
    <Group gap="md" wrap="wrap">
      {parts.map((part) => (
        <Group key={part.key} gap={6} wrap="nowrap">
          <Box w={10} h={10} style={{ borderRadius: 2, background: part.color }} />
          <Text size="sm" c="dimmed">
            {part.label}
          </Text>
        </Group>
      ))}
    </Group>
  );
}
