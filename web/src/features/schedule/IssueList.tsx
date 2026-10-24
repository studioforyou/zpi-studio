import { Stack, Text } from '@mantine/core';
import { formatDayShort } from './studioTime';
import type { OccurrencePreview, ScheduleIssue } from './scheduleApi';
import { describeIssue, formatTimeRange, issueNames } from './scheduleFormat';
import type { ScheduleLookups } from './useScheduleLookups';

type IssueListProps = {
  issues: ScheduleIssue[];
  lookups: ScheduleLookups;
};

/** Problems of a planned event: blocking ones in red, warnings in yellow. */
export function IssueList({ issues, lookups }: IssueListProps) {
  const names = issueNames(lookups.staffById, lookups.resourceById);
  return (
    <Stack gap={2}>
      {issues.map((issue, index) => (
        <Text key={index} size="xs" c={issue.blocking ? 'red.7' : 'yellow.8'}>
          {issue.blocking ? '✕ ' : '⚠ '}
          {describeIssue(issue, names, lookups.timeZone)}
        </Text>
      ))}
    </Stack>
  );
}

type ConflictListProps = {
  conflicts: OccurrencePreview[];
  lookups: ScheduleLookups;
};

/** The occurrences a rejected request lists, each with its blocking problems. */
export function ConflictList({ conflicts, lookups }: ConflictListProps) {
  return (
    <Stack gap="xs">
      {conflicts.map((conflict) => (
        <Stack key={conflict.startsAt} gap={2}>
          <Text size="sm" fw={500}>
            {formatDayShort(conflict.date)},{' '}
            {formatTimeRange(conflict.startsAt, conflict.endsAt, lookups.timeZone)}
          </Text>
          <IssueList issues={conflict.issues} lookups={lookups} />
        </Stack>
      ))}
    </Stack>
  );
}
