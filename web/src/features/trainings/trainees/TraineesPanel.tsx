import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Pagination,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { searchCustomers, type Customer } from '@/features/passes/customerPassesApi';
import { formatDate } from '@/features/passes/passFormat';
import { pl } from '@/shared/i18n/pl';
import { CustomerTrainersModal } from './CustomerTrainersModal';

const PAGE_SIZE = 20;

/** The studio's participants, each with a button that opens their trainers. */
export function TraineesPanel() {
  const texts = pl.trainees;
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [page, setPage] = useState(1);
  const [customer, setCustomer] = useState<Customer | null>(null);

  const customers = useQuery({
    queryKey: ['customers', debouncedSearch, page],
    queryFn: () => searchCustomers(debouncedSearch, PAGE_SIZE, page - 1),
    placeholderData: keepPreviousData,
  });

  return (
    <Stack>
      <Stack gap="xs" maw={720}>
        <Title order={3}>{texts.title}</Title>
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
      </Stack>
      <TextInput
        label={texts.search}
        placeholder={texts.searchPlaceholder}
        value={search}
        onChange={(event) => {
          setSearch(event.currentTarget.value);
          setPage(1);
        }}
        w={{ base: '100%', sm: 300 }}
      />
      {customers.isPending ? (
        <Text>{texts.loading}</Text>
      ) : customers.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void customers.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : customers.data.items.length === 0 ? (
        <Text c="dimmed">{debouncedSearch.trim() ? texts.noResults : texts.empty}</Text>
      ) : (
        <>
          <Table.ScrollContainer minWidth={500}>
            <Table striped highlightOnHover>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{texts.columns.name}</Table.Th>
                  <Table.Th w={160}>{texts.columns.birthDate}</Table.Th>
                  <Table.Th w={180}>{texts.columns.actions}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {customers.data.items.map((item) => (
                  <Table.Tr key={item.id}>
                    <Table.Td>
                      <Group gap="xs">
                        <Text fw={500}>{item.displayName}</Text>
                        {item.kind === 'CHILD' && (
                          <Badge variant="light" size="sm">
                            {texts.child}
                          </Badge>
                        )}
                      </Group>
                    </Table.Td>
                    <Table.Td>{item.birthDate ? formatDate(item.birthDate) : '—'}</Table.Td>
                    <Table.Td>
                      <Button size="xs" variant="light" onClick={() => setCustomer(item)}>
                        {texts.assignTrainer}
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Table.ScrollContainer>
          {customers.data.totalPages > 1 && (
            <Pagination total={customers.data.totalPages} value={page} onChange={setPage} />
          )}
        </>
      )}
      <CustomerTrainersModal customer={customer} onClose={() => setCustomer(null)} />
    </Stack>
  );
}
