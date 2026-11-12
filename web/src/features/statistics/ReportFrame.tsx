import type { ReactNode } from 'react';
import { Alert, Center, Loader, Stack } from '@mantine/core';
import type { UseQueryResult } from '@tanstack/react-query';

type ReportFrameProps<T> = {
  report: UseQueryResult<T>;
  /** Shows the report dimmed: it is older than the filters. */
  stale: boolean;
  loadError: string;
  children: (data: T) => ReactNode;
};

/**
 * A report's loading, error and refresh states: a loader until the first data, then the data,
 * dimmed while newer data loads, so the layout never jumps.
 */
export function ReportFrame<T>({ report, stale, loadError, children }: ReportFrameProps<T>) {
  if (report.isError && !stale) {
    return <Alert color="red">{loadError}</Alert>;
  }
  if (report.data === undefined) {
    return (
      <Center py="xl">
        <Loader />
      </Center>
    );
  }
  return (
    <Stack gap="md" style={{ opacity: stale ? 0.5 : 1, transition: 'opacity 150ms' }}>
      {children(report.data)}
    </Stack>
  );
}
