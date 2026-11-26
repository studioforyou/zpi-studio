import { useMemo, useState } from 'react';
import {
  Anchor,
  Badge,
  Center,
  Checkbox,
  Group,
  Paper,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  VisuallyHidden,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link } from 'react-router';
import { paths } from '@/app/paths';
import {
  NewAnnouncementModal,
  type AnnouncementRecipient,
} from '@/features/communication/announcements/NewAnnouncementModal';
import { formatDate } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  defaultInactiveDays,
  getLapsedCustomers,
  inactiveDayOptions,
  lapsedCustomersKeys,
  type InactiveDays,
  type LapsedCustomer,
  type LapsedCustomerPass,
} from './lapsedCustomersApi';
import { hasNoPassLeft, hasUsablePass, matches } from './lapsedCustomersFilters';
import { ReportFrame } from './ReportFrame';
import { StatTile } from './StatTile';

const texts = pl.statistics.lapsed;

/**
 * Customers who stopped coming, as of today, so the studio can contact them (#171). The page's
 * period and event filters do not apply to it.
 */
export function LapsedCustomersPanel() {
  const [inactiveDays, setInactiveDays] = useState<InactiveDays>(defaultInactiveDays);
  const [search, setSearch] = useState('');
  const [validPassOnly, setValidPassOnly] = useState(false);
  const [recipient, setRecipient] = useState<AnnouncementRecipient | null>(null);
  const report = useQuery({
    queryKey: lapsedCustomersKeys.threshold(inactiveDays),
    queryFn: () => getLapsedCustomers(inactiveDays),
    placeholderData: keepPreviousData,
  });

  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {texts.note}
      </Text>
      <ReportFrame report={report} stale={report.isPlaceholderData} loadError={texts.loadError}>
        {(data) => (
          <>
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <StatTile
                label={texts.lapsedTile(data.inactiveDays)}
                value={formatNumber(data.customers.length)}
                details={[texts.lapsedDetail(data.everVisited)]}
              />
              <StatTile
                label={texts.withPassTile}
                value={formatNumber(data.customers.filter(hasUsablePass).length)}
                details={[texts.withPassDetail]}
              />
              <StatTile
                label={texts.withoutPassTile}
                value={formatNumber(data.customers.filter(hasNoPassLeft).length)}
                details={[texts.withoutPassDetail]}
              />
            </SimpleGrid>
            <Paper withBorder radius="md" p="md">
              <Stack gap="md">
                <Group align="flex-end" gap="md" wrap="wrap">
                  <Stack gap={6}>
                    <Text size="sm" fw={500} id="lapsed-threshold">
                      {texts.threshold}
                    </Text>
                    <SegmentedControl
                      aria-labelledby="lapsed-threshold"
                      value={String(inactiveDays)}
                      onChange={(value) => setInactiveDays(Number(value) as InactiveDays)}
                      data={inactiveDayOptions.map((days) => ({
                        value: String(days),
                        label: texts.days(days),
                      }))}
                    />
                  </Stack>
                  <TextInput
                    label={texts.search}
                    placeholder={texts.searchPlaceholder}
                    value={search}
                    onChange={(event) => setSearch(event.currentTarget.value)}
                    w={280}
                  />
                  <Checkbox
                    label={texts.validPassOnly}
                    checked={validPassOnly}
                    onChange={(event) => setValidPassOnly(event.currentTarget.checked)}
                    mb={8}
                  />
                </Group>
                <LapsedTable
                  customers={data.customers}
                  inactiveDays={data.inactiveDays}
                  search={search}
                  validPassOnly={validPassOnly}
                  onSend={setRecipient}
                />
              </Stack>
            </Paper>
          </>
        )}
      </ReportFrame>
      <NewAnnouncementModal
        opened={recipient !== null}
        onClose={() => setRecipient(null)}
        recipient={recipient}
      />
    </Stack>
  );
}

type LapsedTableProps = {
  customers: LapsedCustomer[];
  inactiveDays: number;
  search: string;
  validPassOnly: boolean;
  onSend: (recipient: AnnouncementRecipient) => void;
};

function LapsedTable({ customers, inactiveDays, search, validPassOnly, onSend }: LapsedTableProps) {
  const shown = useMemo(
    () =>
      customers
        .filter((row) => !validPassOnly || hasUsablePass(row))
        .filter((row) => matches(row, search)),
    [customers, search, validPassOnly],
  );
  if (customers.length === 0) {
    return (
      <Center py="xl">
        <Stack gap={6} align="center">
          <Text fw={500}>{texts.empty}</Text>
          <Text size="sm" c="dimmed">
            {texts.emptyDetail(inactiveDays)}
          </Text>
        </Stack>
      </Center>
    );
  }
  return (
    <Stack gap="xs">
      <Table.ScrollContainer minWidth={1000}>
        <Table verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.columns.customer}</Table.Th>
              <Table.Th>{texts.columns.contact}</Table.Th>
              <Table.Th>{texts.columns.lastVisit}</Table.Th>
              <Table.Th ta="right">
                {texts.columns.visits}
                <Text size="xs" c="dimmed" fw={400}>
                  {texts.columns.visitsHint}
                </Text>
              </Table.Th>
              <Table.Th>{texts.columns.pass}</Table.Th>
              <Table.Th>{texts.columns.marketing}</Table.Th>
              <Table.Th>{texts.columns.lastAnnouncement}</Table.Th>
              <Table.Th>
                <VisuallyHidden>{texts.columns.actions}</VisuallyHidden>
              </Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {shown.map((row) => (
              <LapsedRow key={row.profileId} row={row} onSend={onSend} />
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      {shown.length === 0 && (
        <Text size="sm" c="dimmed" ta="center" py="md">
          {texts.noMatches}
        </Text>
      )}
      <Text size="xs" c="dimmed">
        {texts.summary(shown.length, customers.length)}
      </Text>
    </Stack>
  );
}

function LapsedRow({
  row,
  onSend,
}: {
  row: LapsedCustomer;
  onSend: (recipient: AnnouncementRecipient) => void;
}) {
  const name = `${row.firstName} ${row.lastName}`;
  const contactName = row.guardianName ?? name;
  return (
    <Table.Tr>
      <Table.Td>
        <Text fw={500}>{name}</Text>
        {row.kind === 'CHILD' && (
          <Text size="xs" c="dimmed">
            {texts.child(row.guardianName)}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        {row.phone && <Text size="sm">{row.phone}</Text>}
        {row.email ? (
          <Text size="xs" c="dimmed">
            {row.email}
          </Text>
        ) : (
          !row.phone && (
            <Text size="xs" c="dimmed">
              {texts.noContact}
            </Text>
          )
        )}
      </Table.Td>
      <Table.Td>
        <Text size="sm">{formatDate(row.lastVisit)}</Text>
        <Text size="xs" fw={600} c={row.daysSinceLastVisit >= 60 ? 'orange.8' : 'dimmed'}>
          {texts.daysAgo(row.daysSinceLastVisit)}
        </Text>
      </Table.Td>
      <Table.Td ta="right">{row.recentVisits}</Table.Td>
      <Table.Td>
        <PassBadge pass={row.pass} />
      </Table.Td>
      <Table.Td>
        {row.marketingConsent ? (
          texts.yes
        ) : (
          <Text span size="sm" c="dimmed">
            {texts.no}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        {row.lastAnnouncement ? (
          formatDate(row.lastAnnouncement)
        ) : (
          <Text span c="dimmed">
            {texts.none}
          </Text>
        )}
      </Table.Td>
      <Table.Td>
        {row.contactAccountId && (
          <Group gap="md" wrap="nowrap" justify="flex-end">
            <Anchor
              component="button"
              type="button"
              size="sm"
              fw={600}
              aria-label={`${texts.send}: ${name}`}
              onClick={() =>
                onSend({
                  accountId: row.contactAccountId!,
                  label: `${contactName} · ${pl.communication.announcements.form.roleClient}`,
                })
              }
            >
              {texts.send}
            </Anchor>
            <Anchor
              component={Link}
              to={paths.accountDetails(
                row.contactAccountId,
                row.kind === 'CHILD' ? row.profileId : null,
              )}
              size="sm"
              fw={600}
              aria-label={`${texts.account}: ${name}`}
            >
              {texts.account} →
            </Anchor>
          </Group>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

function PassBadge({ pass }: { pass: LapsedCustomerPass | null }) {
  if (pass === null) {
    return (
      <Badge variant="outline" color="gray" tt="none">
        {texts.noPass}
      </Badge>
    );
  }
  const until = pass.validUntil ? formatDate(pass.validUntil) : null;
  if (pass.state === 'VALID') {
    return (
      <Badge variant="light" color="teal" tt="none" title={pass.productName}>
        {texts.passValid(until, pass.entriesRemaining)}
      </Badge>
    );
  }
  if (pass.state === 'SUSPENDED') {
    return (
      <Badge variant="light" color="yellow" tt="none" title={pass.productName}>
        {texts.passSuspended(until)}
      </Badge>
    );
  }
  return (
    <Badge variant="light" color="gray" tt="none" title={pass.productName}>
      {pass.entriesRemaining === 0 ? texts.passUsedUp : texts.passEnded(until)}
    </Badge>
  );
}

function formatNumber(value: number): string {
  return value.toLocaleString('pl-PL');
}
