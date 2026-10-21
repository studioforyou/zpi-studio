import { Stack, Text, Title } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';

export function PlaceholderPage({ title }: { title: string }) {
  return (
    <Stack gap="xs">
      <Title order={2}>{title}</Title>
      <Text c="dimmed">{pl.common.placeholder}</Text>
    </Stack>
  );
}
