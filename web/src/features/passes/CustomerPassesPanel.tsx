import { useState } from 'react';
import { Alert, Badge, Button, Group, Loader, Select, Stack, Table, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AssignPassModal } from './AssignPassModal';
import { CorrectPassEntriesModal } from './CorrectPassEntriesModal';
import {
  listCustomerPasses,
  searchCustomers,
  type Customer,
  type CustomerPass,
  type CustomerPassStatus,
} from './customerPassesApi';
import { formatDate, formatMoney } from './passFormat';

const statusColors: Record<CustomerPassStatus, string> = {
  PENDING_PAYMENT: 'yellow',
  SCHEDULED: 'blue',
  ACTIVE: 'green',
  SUSPENDED: 'orange',
  EXPIRED: 'gray',
  CANCELLED: 'red',
};

function customerLabel(customer: Customer): string {
  return customer.kind === 'CHILD'
    ? `${customer.displayName} (${pl.customerPasses.child})`
    : customer.displayName;
}

/** Passes held by one participant, chosen by searching their name, and assigning new ones. */
export function CustomerPassesPanel() {
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [assigning, setAssigning] = useState(false);
  const [correcting, setCorrecting] = useState<CustomerPass | null>(null);

  const customers = useQuery({
    queryKey: ['customers', debouncedSearch],
    queryFn: () => searchCustomers(debouncedSearch),
    placeholderData: keepPreviousData,
  });
  // The chosen participant stays an option even when the current search no longer matches them.
  const found = customers.data?.items ?? [];
  const options =
    customer && !found.some((item) => item.id === customer.id) ? [customer, ...found] : found;

  const passes = useQuery({
    queryKey: ['customer-passes', customer?.id],
    queryFn: () => listCustomerPasses(customer!.id),
    enabled: customer !== null,
  });

  return (
    <Stack>
      <Group align="flex-end" justify="space-between">
        <Select
          label={pl.customerPasses.customer}
          placeholder={pl.customerPasses.customerPlaceholder}
          w={360}
          searchable
          clearable
          searchValue={search}
          onSearchChange={setSearch}
          // The server filters by name and phone; the options are not filtered again here.
          filter={({ options: all }) => all}
          data={options.map((item) => ({ value: item.id, label: customerLabel(item) }))}
          value={customer?.id ?? null}
          onChange={(id) => {
            setCorrecting(null);
            setCustomer(options.find((item) => item.id === id) ?? null);
          }}
          nothingFoundMessage={pl.customerPasses.noCustomers}
        />
        <Button disabled={customer === null} onClick={() => setAssigning(true)}>
          {pl.customerPasses.assign}
        </Button>
      </Group>
      {customer === null ? (
        <Text c="dimmed">{pl.customerPasses.selectCustomer}</Text>
      ) : passes.isPending ? (
        <Loader />
      ) : passes.isError ? (
        <Alert color="red">{pl.customerPasses.loadError}</Alert>
      ) : passes.data.length === 0 ? (
        <Text c="dimmed">{pl.customerPasses.empty}</Text>
      ) : (
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{pl.customerPasses.columns.product}</Table.Th>
              <Table.Th>{pl.customerPasses.columns.entries}</Table.Th>
              <Table.Th>{pl.customerPasses.columns.validity}</Table.Th>
              <Table.Th>{pl.customerPasses.columns.price}</Table.Th>
              <Table.Th>{pl.customerPasses.columns.status}</Table.Th>
              <Table.Th>{pl.customerPasses.columns.actions}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {passes.data.map((pass) => (
              <Table.Tr key={pass.id}>
                <Table.Td>{pass.productName}</Table.Td>
                <Table.Td>
                  {pass.entriesTotal === null || pass.entriesRemaining === null
                    ? pl.passes.unlimited
                    : pl.customerPasses.entriesLeft(pass.entriesRemaining, pass.entriesTotal)}
                </Table.Td>
                <Table.Td>
                  {pass.validFrom && pass.validUntil
                    ? `${formatDate(pass.validFrom)} – ${formatDate(pass.validUntil)}`
                    : '—'}
                </Table.Td>
                <Table.Td>{formatMoney(pass.pricePaid)}</Table.Td>
                <Table.Td>
                  <Badge color={statusColors[pass.status]} variant="light">
                    {pl.customerPasses.statuses[pass.status]}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  {pass.entriesRemaining !== null && (
                    <Button variant="light" size="xs" onClick={() => setCorrecting(pass)}>
                      {pl.customerPasses.correction.title}
                    </Button>
                  )}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <AssignPassModal customer={assigning ? customer : null} onClose={() => setAssigning(false)} />
      {customer && correcting && (
        <CorrectPassEntriesModal
          key={`${customer.id}:${correcting.id}`}
          customer={customer}
          pass={correcting}
          onClose={() => setCorrecting((current) => (current === correcting ? null : current))}
        />
      )}
    </Stack>
  );
}
