import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Pagination,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AssignPassModal } from './AssignPassModal';
import { CustomerContact } from './CustomerContact';
import {
  searchCustomerPasses,
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

const PAGE_SIZE = 20;

/**
 * Passes of all participants matching a search of their name, phone number or e-mail address,
 * with each holder's name and e-mail address: assigning new passes, extending and suspending them.
 */
export function CustomerPassesPanel() {
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search.trim(), 300);
  const [page, setPage] = useState(1);
  const [assigning, setAssigning] = useState(false);
  const [extending, setExtending] = useState<CustomerPass | null>(null);
  const [suspending, setSuspending] = useState<CustomerPass | null>(null);
  const searching = debouncedSearch !== '';

  const passes = useQuery({
    queryKey: ['customer-passes', 'search', debouncedSearch, page],
    queryFn: () => searchCustomerPasses(debouncedSearch, PAGE_SIZE, page - 1),
    enabled: searching,
    placeholderData: keepPreviousData,
  });
  // All products, also archived ones: a pass keeps the suspension rules of its product.
  const products = useQuery({
    queryKey: ['pass-products', 'all'],
    queryFn: () => listPassProducts(),
    enabled: searching,
  });
  const productOf = (pass: CustomerPass) =>
    products.data?.find((product) =>
      product.variants.some((variant) => variant.id === pass.variantId),
    );
  const today = todayIsoDate();

  return (
    <Stack>
      <Group align="flex-end" justify="space-between">
        <TextInput
          label={pl.customerPasses.search}
          placeholder={pl.customerPasses.customerPlaceholder}
          w={360}
          value={search}
          onChange={(event) => {
            setSearch(event.currentTarget.value);
            setPage(1);
          }}
        />
        <Button onClick={() => setAssigning(true)}>{pl.customerPasses.assign}</Button>
      </Group>
      {!searching ? (
        <Text c="dimmed">{pl.customerPasses.selectCustomer}</Text>
      ) : passes.isPending ? (
        <Loader />
      ) : passes.isError ? (
        <Alert color="red">{pl.customerPasses.loadError}</Alert>
      ) : passes.data.items.length === 0 ? (
        <Text c="dimmed">{pl.customerPasses.empty}</Text>
      ) : (
        <>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{pl.customerPasses.columns.customer}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.product}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.entries}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.validity}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.price}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.status}</Table.Th>
                <Table.Th>{pl.customerPasses.columns.actions}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {passes.data.items.map(({ holder, pass }) => (
                <Table.Tr key={pass.id}>
                  <Table.Td>
                    <CustomerContact
                      displayName={holder.displayName}
                      email={holder.email}
                      emailOfGuardian={holder.emailOfGuardian}
                    />
                  </Table.Td>
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
          {passes.data.totalPages > 1 && (
            <Pagination total={passes.data.totalPages} value={page} onChange={setPage} />
          )}
        </>
      )}
      <AssignPassModal opened={assigning} onClose={() => setAssigning(false)} />
      <ExtendPassModal pass={extending} onClose={() => setExtending(null)} />
      <SuspendPassModal
        pass={suspending}
        product={suspending ? productOf(suspending) : undefined}
        onClose={() => setSuspending(null)}
      />
    </Stack>
  );
}
