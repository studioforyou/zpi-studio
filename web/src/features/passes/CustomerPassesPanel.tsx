import { useState } from 'react';
import { Alert, Badge, Button, Group, Loader, Select, Stack, Table, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AssignPassModal } from './AssignPassModal';
import {
  listCustomerPasses,
  searchCustomers,
  type Customer,
  type CustomerPass,
  type CustomerPassStatus,
} from './customerPassesApi';
import { ExtendPassModal } from './ExtendPassModal';
import { formatDate, formatMoney, todayIsoDate } from './passFormat';
import { listPassProducts } from './passesApi';
import { SuspendPassModal } from './SuspendPassModal';

const statusColors: Record<CustomerPassStatus, string> = {
  PENDING_PAYMENT: 'yellow',
  SCHEDULED: 'blue',
  ACTIVE: 'green',
  SUSPENDED: 'orange',
  EXPIRED: 'gray',
  CANCELLED: 'red',
};

/** A pass waiting for its payment or cancelled cannot be extended. */
const extendable: CustomerPassStatus[] = ['SCHEDULED', 'ACTIVE', 'SUSPENDED', 'EXPIRED'];

/** Only a pass that is still valid can be suspended. */
const suspendable: CustomerPassStatus[] = ['SCHEDULED', 'ACTIVE', 'SUSPENDED'];

function customerLabel(customer: Customer): string {
  return customer.kind === 'CHILD'
    ? `${customer.displayName} (${pl.customerPasses.child})`
    : customer.displayName;
}

/**
 * Passes held by one participant, chosen by searching their name: assigning new ones,
 * extending them and suspending them.
 */
export function CustomerPassesPanel() {
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [assigning, setAssigning] = useState(false);
  const [extending, setExtending] = useState<CustomerPass | null>(null);
  const [suspending, setSuspending] = useState<CustomerPass | null>(null);

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
  // All products, also archived ones: a pass keeps the suspension rules of its product.
  const products = useQuery({
    queryKey: ['pass-products', 'all'],
    queryFn: () => listPassProducts(),
    enabled: customer !== null,
  });
  const productOf = (pass: CustomerPass) =>
    products.data?.find((product) =>
      product.variants.some((variant) => variant.id === pass.variantId),
    );
  const today = todayIsoDate();

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
          onChange={(id) => setCustomer(options.find((item) => item.id === id) ?? null)}
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
                  {pass.suspensions
                    .filter((suspension) => suspension.endsOn >= today)
                    .map((suspension) => (
                      <Text key={suspension.id} size="xs" c="orange">
                        {pl.customerPasses.suspendedPeriod(
                          formatDate(suspension.startsOn),
                          formatDate(suspension.endsOn),
                        )}
                      </Text>
                    ))}
                </Table.Td>
                <Table.Td>{formatMoney(pass.pricePaid)}</Table.Td>
                <Table.Td>
                  <Badge color={statusColors[pass.status]} variant="light">
                    {pl.customerPasses.statuses[pass.status]}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    {extendable.includes(pass.status) && (
                      <Button size="xs" variant="light" onClick={() => setExtending(pass)}>
                        {pl.customerPasses.extend}
                      </Button>
                    )}
                    {suspendable.includes(pass.status) && (
                      <Button
                        size="xs"
                        variant="light"
                        color="orange"
                        onClick={() => setSuspending(pass)}
                      >
                        {pl.customerPasses.suspend}
                      </Button>
                    )}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <AssignPassModal customer={assigning ? customer : null} onClose={() => setAssigning(false)} />
      <ExtendPassModal pass={extending} onClose={() => setExtending(null)} />
      <SuspendPassModal
        pass={suspending}
        product={suspending ? productOf(suspending) : undefined}
        onClose={() => setSuspending(null)}
      />
    </Stack>
  );
}
