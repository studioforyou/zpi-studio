import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Pagination,
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getStudio, scheduleKeys } from '@/features/schedule/scheduleApi';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import { DEFAULT_TIME_ZONE } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  countOpenDisputes,
  disputeKeys,
  listDisputes,
  type Dispute,
  type DisputeStatus,
} from './disputesApi';
import { decidedBy, formatSession, resolutionColors, trainerNames } from './disputeFormat';
import { DisputeDrawer } from './DisputeDrawer';

/**
 * Discrepancies clients report about held personal sessions (#113). The administrator decides on
 * each, so the session is settled correctly: the entry comes back, a make-up is granted, or the
 * entry stays used.
 */
export function DisputesPage() {
  const texts = pl.disputes;
  const [view, setView] = useState<DisputeStatus>('OPEN');
  // 1-based, as Mantine's pagination counts pages.
  const [page, setPage] = useState(1);
  const [reviewing, setReviewing] = useState<Dispute | null>(null);

  const studio = useQuery({
    queryKey: scheduleKeys.studio,
    queryFn: getStudio,
    staleTime: Infinity,
  });
  const timeZone = studio.data?.timeZone ?? DEFAULT_TIME_ZONE;
  const disputes = useQuery({
    queryKey: disputeKeys.list(view, page - 1),
    queryFn: () => listDisputes(view, page - 1),
  });
  // The count on the first tab stays visible while the history is open.
  const open = useQuery({ queryKey: disputeKeys.openCount(), queryFn: countOpenDisputes });
  const openCount = open.data ?? 0;
  const items = disputes.data?.items ?? [];
  const resolved = view === 'RESOLVED';

  function changeView(value: string | null) {
    if (!value) return;
    setView(value as DisputeStatus);
    setPage(1);
  }

  return (
    <Stack>
      <Stack gap={4}>
        <Title order={2}>{texts.title}</Title>
        <Text c="dimmed" size="sm">
          {texts.intro}
        </Text>
      </Stack>
      <Tabs value={view} onChange={changeView}>
        <Tabs.List aria-label={texts.tabsLabel}>
          <Tabs.Tab
            value="OPEN"
            rightSection={
              openCount > 0 ? (
                <Badge size="sm" variant="light" circle>
                  {openCount}
                </Badge>
              ) : undefined
            }
          >
            {texts.tabs.OPEN}
          </Tabs.Tab>
          <Tabs.Tab value="RESOLVED">{texts.tabs.RESOLVED}</Tabs.Tab>
        </Tabs.List>
      </Tabs>
      {disputes.isPending ? (
        <Text>{texts.loading}</Text>
      ) : disputes.isError ? (
        <Alert color="red">
          {texts.loadError}{' '}
          <Button variant="subtle" size="compact-sm" onClick={() => void disputes.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : items.length === 0 ? (
        <Text c="dimmed">{texts.empty[view]}</Text>
      ) : (
        <Table highlightOnHover verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.columns.customer}</Table.Th>
              <Table.Th>{texts.columns.session}</Table.Th>
              <Table.Th>{texts.columns.trainer}</Table.Th>
              <Table.Th>{texts.columns.reason}</Table.Th>
              {resolved ? (
                <>
                  <Table.Th>{texts.columns.resolution}</Table.Th>
                  <Table.Th>{texts.columns.resolvedAt}</Table.Th>
                </>
              ) : (
                <Table.Th>{texts.columns.raisedAt}</Table.Th>
              )}
              <Table.Th>{texts.columns.actions}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {items.map((dispute) => (
              <Table.Tr key={dispute.id}>
                <Table.Td>
                  <Group gap={6} wrap="nowrap">
                    <Text fw={500}>{dispute.customerName}</Text>
                    {dispute.customerKind === 'CHILD' && (
                      <Badge size="xs" variant="light" color="grape">
                        {texts.child}
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{dispute.title}</Text>
                  <Text size="xs" c="dimmed">
                    {formatSession(dispute, timeZone)}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{trainerNames(dispute)}</Text>
                </Table.Td>
                <Table.Td maw={280}>
                  <Text size="sm" lineClamp={2}>
                    {dispute.reason}
                  </Text>
                </Table.Td>
                {resolved ? (
                  <>
                    <Table.Td>
                      {dispute.resolution && (
                        <Badge variant="light" color={resolutionColors[dispute.resolution]}>
                          {texts.resolutions[dispute.resolution]}
                        </Badge>
                      )}
                      {dispute.resolutionEffect && (
                        <Text size="xs" c="dimmed" mt={4}>
                          {texts.effects[dispute.resolutionEffect]}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="dimmed">
                        {decidedBy(dispute, timeZone)}
                      </Text>
                    </Table.Td>
                  </>
                ) : (
                  <Table.Td>
                    <Text size="sm" c="dimmed">
                      {formatDateTime(dispute.raisedAt, timeZone)}
                    </Text>
                  </Table.Td>
                )}
                <Table.Td>
                  <Group justify="flex-end" wrap="nowrap">
                    <Button
                      size="xs"
                      variant={resolved ? 'subtle' : 'default'}
                      onClick={() => setReviewing(dispute)}
                    >
                      {resolved ? texts.details : texts.review}
                    </Button>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {disputes.data && disputes.data.totalPages > 1 && (
        <Pagination total={disputes.data.totalPages} value={page} onChange={setPage} />
      )}
      {reviewing && (
        <DisputeDrawer
          key={reviewing.id}
          dispute={reviewing}
          timeZone={timeZone}
          onClose={() => setReviewing(null)}
        />
      )}
    </Stack>
  );
}
