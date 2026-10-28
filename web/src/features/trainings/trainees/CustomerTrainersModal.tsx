import { useState } from 'react';
import { Alert, Badge, Button, Group, Modal, Select, Stack, Table, Text } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Customer } from '@/features/passes/customerPassesApi';
import { DEFAULT_TIME_ZONE, formatDate, toZoned } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import {
  assignCustomerTrainer,
  listAssignableTrainers,
  listCustomerTrainers,
  traineeErrorMessage,
  traineeKeys,
  unassignCustomerTrainer,
} from './traineesApi';

type Props = {
  /** The participant whose trainers are managed; `null` keeps the modal closed. */
  customer: Customer | null;
  onClose: () => void;
};

/** The participant's trainers: adds a new one or removes an assigned one. */
export function CustomerTrainersModal({ customer, onClose }: Props) {
  const texts = pl.trainees.modal;
  const queryClient = useQueryClient();
  const [staffId, setStaffId] = useState<string | null>(null);

  const trainers = useQuery({
    queryKey: traineeKeys.trainers(customer?.id ?? ''),
    queryFn: () => listCustomerTrainers(customer!.id),
    enabled: customer !== null,
  });
  const assignable = useQuery({
    queryKey: traineeKeys.assignable,
    queryFn: listAssignableTrainers,
    enabled: customer !== null,
  });
  // Trainers already assigned to the participant are not offered again.
  const assignedIds = new Set((trainers.data ?? []).map((trainer) => trainer.staffId));
  const options = (assignable.data ?? []).filter((trainer) => !assignedIds.has(trainer.staffId));

  const refresh = () =>
    queryClient.invalidateQueries({ queryKey: traineeKeys.trainers(customer!.id) });
  const assign = useMutation({
    mutationFn: (id: string) => assignCustomerTrainer(customer!.id, id),
    onSuccess: async () => {
      setStaffId(null);
      await refresh();
    },
  });
  const unassign = useMutation({
    mutationFn: (id: string) => unassignCustomerTrainer(customer!.id, id),
    onSuccess: refresh,
  });
  const failed = assign.isError ? assign.error : unassign.isError ? unassign.error : null;

  function close() {
    setStaffId(null);
    assign.reset();
    unassign.reset();
    onClose();
  }

  return (
    <Modal
      opened={customer !== null}
      onClose={close}
      title={customer ? texts.title(customer.displayName) : ''}
      size="lg"
    >
      <Stack>
        <Text fw={500}>{texts.assigned}</Text>
        {trainers.isPending ? (
          <Text>{texts.loading}</Text>
        ) : trainers.isError ? (
          <Alert color="red">{texts.loadError}</Alert>
        ) : trainers.data.length === 0 ? (
          <Text c="dimmed">{texts.none}</Text>
        ) : (
          <Table>
            <Table.Tbody>
              {trainers.data.map((trainer) => (
                <Table.Tr key={trainer.staffId}>
                  <Table.Td>
                    <Group gap="xs">
                      <Text>{trainer.displayName}</Text>
                      {!trainer.isActive && (
                        <Badge color="gray" variant="light" size="sm">
                          {texts.inactive}
                        </Badge>
                      )}
                    </Group>
                    <Text size="xs" c="dimmed">
                      {texts.since(formatDate(toZoned(trainer.assignedAt, DEFAULT_TIME_ZONE).date))}
                    </Text>
                  </Table.Td>
                  <Table.Td w={100} ta="right">
                    <Button
                      size="xs"
                      variant="subtle"
                      color="red"
                      aria-label={texts.removeLabel(trainer.displayName)}
                      loading={unassign.isPending && unassign.variables === trainer.staffId}
                      onClick={() => unassign.mutate(trainer.staffId)}
                    >
                      {texts.remove}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
        <Group align="flex-end">
          <Select
            label={texts.newTrainer}
            placeholder={texts.newTrainerPlaceholder}
            data={options.map((trainer) => ({
              value: trainer.staffId,
              label: trainer.displayName,
            }))}
            value={staffId}
            onChange={setStaffId}
            searchable
            nothingFoundMessage={texts.noTrainers}
            disabled={assignable.isPending}
            style={{ flex: 1 }}
          />
          <Button
            disabled={staffId === null}
            loading={assign.isPending}
            onClick={() => staffId && assign.mutate(staffId)}
          >
            {texts.add}
          </Button>
        </Group>
        {failed && <Alert color="red">{traineeErrorMessage(failed)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {texts.close}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
