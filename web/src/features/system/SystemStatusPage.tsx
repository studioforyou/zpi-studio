import { Alert, Button, Group, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { env } from '@/shared/config/env';
import { pl } from '@/shared/i18n/pl';
import { pingBackend } from './pingApi';

export function SystemStatusPage() {
  const health = useQuery({
    queryKey: ['system', 'ping'],
    queryFn: pingBackend,
    enabled: false,
    retry: false,
  });

  return (
    <Stack align="flex-start">
      <Title order={2}>{pl.system.title}</Title>
      <Group gap="xs">
        <Text fw={500}>{pl.system.environment}:</Text>
        <Text>{env.appFlavor}</Text>
      </Group>
      <Group gap="xs">
        <Text fw={500}>{pl.system.apiAddress}:</Text>
        <Text>{env.apiBaseUrl || pl.system.sameOrigin}</Text>
      </Group>
      <Button onClick={() => void health.refetch()} loading={health.isFetching}>
        {pl.system.checkButton}
      </Button>
      {health.isFetching ? (
        <Text>{pl.system.checking}</Text>
      ) : health.isError ? (
        <Alert color="red">{errorMessage(health.error)}</Alert>
      ) : health.isSuccess ? (
        <Alert color="green">{pl.system.ok}</Alert>
      ) : (
        <Text c="dimmed">{pl.system.idle}</Text>
      )}
    </Stack>
  );
}

function errorMessage(error: Error): string {
  if (error instanceof ApiError && error.code !== NETWORK_ERROR && error.status !== null) {
    return pl.system.httpError(error.status);
  }
  return pl.system.networkError;
}
