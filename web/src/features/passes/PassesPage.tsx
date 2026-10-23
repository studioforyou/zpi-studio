import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  SegmentedControl,
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { CreatePassProductModal } from './CreatePassProductModal';
import { CustomerPassesPanel } from './CustomerPassesPanel';
import { PassProductModal } from './PassProductModal';
import { listPassProducts, listServiceTypes } from './passesApi';

type Filter = 'active' | 'archived' | 'all';

const filterToActive: Record<Filter, boolean | undefined> = {
  active: true,
  archived: false,
  all: undefined,
};

export function PassesPage() {
  return (
    <Stack>
      <Title order={2}>{pl.passes.title}</Title>
      <Tabs defaultValue="offer">
        <Tabs.List>
          <Tabs.Tab value="offer">{pl.customerPasses.tabs.offer}</Tabs.Tab>
          <Tabs.Tab value="assigned">{pl.customerPasses.tabs.assigned}</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="offer" pt="md">
          <PassProductsPanel />
        </Tabs.Panel>
        <Tabs.Panel value="assigned" pt="md">
          <CustomerPassesPanel />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}

function PassProductsPanel() {
  const [filter, setFilter] = useState<Filter>('active');
  const [creating, setCreating] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const products = useQuery({
    queryKey: ['pass-products', filter],
    queryFn: () => listPassProducts(filterToActive[filter]),
  });
  const serviceTypes = useQuery({ queryKey: ['service-types'], queryFn: listServiceTypes });
  const serviceNames = new Map(
    (serviceTypes.data ?? []).map((service) => [service.id, service.name]),
  );

  return (
    <Stack>
      <Group justify="space-between">
        <SegmentedControl
          value={filter}
          onChange={(value) => setFilter(value as Filter)}
          data={[
            { value: 'active', label: pl.passes.filter.active },
            { value: 'archived', label: pl.passes.filter.archived },
            { value: 'all', label: pl.passes.filter.all },
          ]}
        />
        <Button onClick={() => setCreating(true)}>{pl.passes.add}</Button>
      </Group>
      {products.isPending ? (
        <Loader />
      ) : products.isError ? (
        <Alert color="red">{pl.passes.loadError}</Alert>
      ) : products.data.length === 0 ? (
        <Text c="dimmed">{pl.passes.empty}</Text>
      ) : (
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{pl.passes.columns.name}</Table.Th>
              <Table.Th>{pl.passes.columns.kind}</Table.Th>
              <Table.Th>{pl.passes.columns.audience}</Table.Th>
              <Table.Th>{pl.passes.columns.services}</Table.Th>
              <Table.Th>{pl.passes.columns.variants}</Table.Th>
              <Table.Th>{pl.passes.columns.status}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {products.data.map((product) => (
              <Table.Tr
                key={product.id}
                style={{ cursor: 'pointer' }}
                onClick={() => setSelectedId(product.id)}
              >
                <Table.Td>{product.name}</Table.Td>
                <Table.Td>{pl.passes.kinds[product.kind]}</Table.Td>
                <Table.Td>{pl.passes.audiences[product.audience]}</Table.Td>
                <Table.Td>
                  {product.serviceTypeIds.map((id) => serviceNames.get(id) ?? id).join(', ')}
                </Table.Td>
                <Table.Td>{product.variants.filter((variant) => variant.isActive).length}</Table.Td>
                <Table.Td>
                  <Badge color={product.isActive ? 'green' : 'gray'} variant="light">
                    {product.isActive ? pl.passes.status.active : pl.passes.status.archived}
                  </Badge>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <CreatePassProductModal
        opened={creating}
        onClose={() => setCreating(false)}
        serviceTypes={serviceTypes.data ?? []}
      />
      <PassProductModal
        product={products.data?.find((product) => product.id === selectedId) ?? null}
        onClose={() => setSelectedId(null)}
        serviceTypes={serviceTypes.data ?? []}
      />
    </Stack>
  );
}
