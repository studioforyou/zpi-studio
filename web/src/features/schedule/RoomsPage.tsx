import { useState } from 'react';
import { Alert, Badge, Button, Group, Stack, Switch, Table, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { listResources, resourceKeys, type Resource } from './resourcesApi';
import { RoomFormModal } from './RoomFormModal';

type OpenDialog = { type: 'create' } | { type: 'edit'; room: Resource };

export function RoomsPage() {
  const texts = pl.rooms;
  const [showInactive, setShowInactive] = useState(false);
  const [dialog, setDialog] = useState<OpenDialog | null>(null);
  const rooms = useQuery({ queryKey: resourceKeys.list(), queryFn: listResources });

  const allRooms = rooms.data ?? [];
  const visibleRooms = showInactive ? allRooms : allRooms.filter((room) => room.isActive);
  const roomNames = new Map(allRooms.map((room) => [room.id, room.name]));

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Stack gap="xs" maw={720}>
          <Title order={2}>{texts.title}</Title>
          <Text size="sm" c="dimmed">
            {texts.intro}
          </Text>
        </Stack>
        <Button onClick={() => setDialog({ type: 'create' })}>{texts.add}</Button>
      </Group>
      <Switch
        label={texts.showInactive}
        checked={showInactive}
        onChange={(event) => setShowInactive(event.currentTarget.checked)}
      />
      {rooms.isPending ? (
        <Text>{texts.loading}</Text>
      ) : rooms.isError ? (
        <Alert color="red">{texts.loadError}</Alert>
      ) : visibleRooms.length === 0 ? (
        <Text c="dimmed">{texts.empty}</Text>
      ) : (
        <Table striped highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{texts.columns.name}</Table.Th>
              <Table.Th>{texts.columns.kind}</Table.Th>
              <Table.Th>{texts.columns.parent}</Table.Th>
              <Table.Th>{texts.columns.maxPeople}</Table.Th>
              <Table.Th>{texts.columns.status}</Table.Th>
              <Table.Th>{texts.columns.actions}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {visibleRooms.map((room) => (
              <Table.Tr key={room.id}>
                <Table.Td>
                  <Text fw={500}>{room.name}</Text>
                  {room.description && (
                    <Text size="xs" c="dimmed">
                      {room.description}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>{texts.kinds[room.kind]}</Table.Td>
                <Table.Td>
                  {room.parentResourceId ? roomNames.get(room.parentResourceId) : '—'}
                </Table.Td>
                <Table.Td>{room.maxPeople ?? texts.noLimit}</Table.Td>
                <Table.Td>
                  <Badge color={room.isActive ? 'green' : 'gray'} variant="light">
                    {room.isActive ? texts.active : texts.inactive}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Button
                    size="xs"
                    variant="subtle"
                    onClick={() => setDialog({ type: 'edit', room })}
                  >
                    {texts.edit}
                  </Button>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {dialog && (
        <RoomFormModal
          key={dialog.type === 'edit' ? dialog.room.id : 'new'}
          opened
          onClose={() => setDialog(null)}
          room={dialog.type === 'edit' ? dialog.room : undefined}
          rooms={allRooms}
        />
      )}
    </Stack>
  );
}
