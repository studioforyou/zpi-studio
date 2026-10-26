import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { ServiceTypeFormModal } from './ServiceTypeFormModal';
import { listServiceTypes, serviceTypeKeys, type ServiceType } from './serviceTypesApi';

type Dialog = { type: 'create' } | { type: 'edit'; service: ServiceType };

export function ServiceTypesPanel() {
  const texts = pl.trainings;
  const [search, setSearch] = useState('');
  const [showInactive, setShowInactive] = useState(false);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const services = useQuery({ queryKey: serviceTypeKeys.all, queryFn: listServiceTypes });
  const query = search.trim().toLocaleLowerCase('pl');
  const visible = (services.data ?? []).filter(
    (service) =>
      (showInactive || service.isActive) &&
      `${service.name} ${service.description ?? ''}`.toLocaleLowerCase('pl').includes(query),
  );

  function range(min: number | null, max: number | null, emptyLabel: string = texts.noLimit) {
    if (min === null && max === null) return emptyLabel;
    if (min === null) return `${texts.upTo} ${max}`;
    if (max === null) return `${texts.from} ${min}`;
    return min === max ? String(min) : `${min}–${max}`;
  }

  return (
    <Stack>
      <Stack gap="xs" maw={720}>
        <Title order={3}>{texts.catalogTitle}</Title>
        <Text size="sm" c="dimmed">
          {texts.intro}
        </Text>
      </Stack>
      <Group justify="space-between" align="flex-end">
        <TextInput
          label={texts.search}
          placeholder={texts.searchPlaceholder}
          value={search}
          onChange={(event) => setSearch(event.currentTarget.value)}
          w={{ base: '100%', sm: 300 }}
        />
        <Group>
          <Switch
            label={texts.showInactive}
            checked={showInactive}
            onChange={(event) => setShowInactive(event.currentTarget.checked)}
          />
          <Button onClick={() => setDialog({ type: 'create' })}>{texts.add}</Button>
        </Group>
      </Group>
      {services.isPending ? (
        <Text>{texts.loading}</Text>
      ) : services.isError ? (
        <Alert color="red" title={texts.loadError}>
          <Button variant="subtle" onClick={() => void services.refetch()}>
            {texts.retry}
          </Button>
        </Alert>
      ) : visible.length === 0 ? (
        <Text c="dimmed">{query ? texts.noResults : texts.empty}</Text>
      ) : (
        <Table.ScrollContainer minWidth={650}>
          <Table striped highlightOnHover layout="fixed">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{texts.columns.name}</Table.Th>
                <Table.Th w={145}>{texts.columns.participation}</Table.Th>
                <Table.Th w={70}>{texts.columns.duration}</Table.Th>
                <Table.Th w={80}>{texts.columns.participants}</Table.Th>
                <Table.Th w={110}>{texts.columns.status}</Table.Th>
                <Table.Th w={80}>{texts.columns.actions}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {visible.map((service) => {
                const level = service.level
                  ? (texts.levels[service.level as keyof typeof texts.levels] ?? service.level)
                  : undefined;

                return (
                  <Table.Tr key={service.id}>
                    <Table.Td>
                      <Text fw={500} lineClamp={2} title={service.name}>
                        {service.name}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {texts.categories[service.category] ?? service.category}
                      </Text>
                      {service.description && (
                        <Text size="xs" c="dimmed" lineClamp={2} title={service.description}>
                          {service.description}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" lineClamp={1} title={level}>
                        {level ?? '—'}
                      </Text>
                      <Text size="xs" c="dimmed">
                        {texts.ageLabel}: {range(service.minAge, service.maxAge)}
                      </Text>
                    </Table.Td>
                    <Table.Td>
                      {service.defaultDurationMinutes} {texts.minutes}
                    </Table.Td>
                    <Table.Td>
                      {range(
                        service.minParticipants,
                        service.maxParticipants,
                        texts.noParticipantDefaults,
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Badge color={service.isActive ? 'green' : 'gray'} variant="light" size="sm">
                        {service.isActive ? texts.active : texts.inactive}
                      </Badge>
                    </Table.Td>
                    <Table.Td>
                      <Button
                        size="xs"
                        variant="subtle"
                        onClick={() => setDialog({ type: 'edit', service })}
                      >
                        {texts.edit}
                      </Button>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {dialog && (
        <ServiceTypeFormModal
          key={dialog.type === 'edit' ? dialog.service.id : 'new'}
          opened
          onClose={() => setDialog(null)}
          service={dialog.type === 'edit' ? dialog.service : undefined}
        />
      )}
    </Stack>
  );
}
