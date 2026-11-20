import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  activateMakeupPolicy,
  deactivateMakeupPolicy,
  listMakeupPolicies,
  type MakeupPolicy,
} from './makeupPoliciesApi';
import { grantedForLabel, limitLabel, makeupPolicyErrorMessage } from './makeupPolicyFormat';
import { MakeupPolicyModal } from './MakeupPolicyModal';

const t = pl.makeupPolicies;

type Editing = { policy: MakeupPolicy | null } | null;

export function MakeupPoliciesPanel() {
  const [editing, setEditing] = useState<Editing>(null);
  const [deactivating, setDeactivating] = useState<MakeupPolicy | null>(null);
  const policies = useQuery({ queryKey: ['makeup-policies'], queryFn: listMakeupPolicies });

  return (
    <Stack>
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <Text c="dimmed" size="sm" maw={720}>
          {t.intro}
        </Text>
        {policies.data && policies.data.length > 0 && (
          <Button onClick={() => setEditing({ policy: null })}>{t.add}</Button>
        )}
      </Group>
      {policies.isPending ? (
        <Loader />
      ) : policies.isError ? (
        <Alert color="red">{t.loadError}</Alert>
      ) : policies.data.length === 0 ? (
        <Paper withBorder p="xl" radius="md">
          <Stack align="center" gap="xs">
            <Title order={4}>{t.emptyTitle}</Title>
            <Text c="dimmed" ta="center" maw={480}>
              {t.emptyText}
            </Text>
            <Button mt="sm" onClick={() => setEditing({ policy: null })}>
              {t.add}
            </Button>
          </Stack>
        </Paper>
      ) : (
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t.columns.name}</Table.Th>
              <Table.Th>{t.columns.validity}</Table.Th>
              <Table.Th>{t.columns.limit}</Table.Th>
              <Table.Th>{t.columns.grantedFor}</Table.Th>
              <Table.Th>{t.columns.expiryCheck}</Table.Th>
              <Table.Th>{t.columns.products}</Table.Th>
              <Table.Th>{t.columns.status}</Table.Th>
              <Table.Th>{t.columns.actions}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {policies.data.map((policy) => (
              <PolicyRow
                key={policy.id}
                policy={policy}
                onEdit={() => setEditing({ policy })}
                onDeactivate={() => setDeactivating(policy)}
              />
            ))}
          </Table.Tbody>
        </Table>
      )}
      <MakeupPolicyModal
        opened={editing !== null}
        policy={editing?.policy ?? null}
        onClose={() => setEditing(null)}
      />
      <DeactivateDialog policy={deactivating} onClose={() => setDeactivating(null)} />
    </Stack>
  );
}

type RowProps = {
  policy: MakeupPolicy;
  onEdit: () => void;
  onDeactivate: () => void;
};

function PolicyRow({ policy, onEdit, onDeactivate }: RowProps) {
  const queryClient = useQueryClient();
  const activate = useMutation({
    mutationFn: () => activateMakeupPolicy(policy.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['makeup-policies'] }),
  });

  return (
    <Table.Tr c={policy.isActive ? undefined : 'dimmed'}>
      <Table.Td fw={500}>{policy.name}</Table.Td>
      <Table.Td>{t.days(policy.validityDays)}</Table.Td>
      <Table.Td>{limitLabel(policy.maxPerPass)}</Table.Td>
      <Table.Td>{grantedForLabel(policy)}</Table.Td>
      <Table.Td>{t.expiryChecks[policy.expiryCheckedAgainst]}</Table.Td>
      <Table.Td>{t.productCount(policy.productCount)}</Table.Td>
      <Table.Td>
        <Badge color={policy.isActive ? 'green' : 'gray'} variant="light">
          {policy.isActive ? t.status.active : t.status.inactive}
        </Badge>
      </Table.Td>
      <Table.Td>
        <Group gap="xs" wrap="nowrap">
          <Button size="xs" variant="subtle" onClick={onEdit}>
            {t.edit}
          </Button>
          {policy.isActive ? (
            <Button size="xs" variant="subtle" color="red" onClick={onDeactivate}>
              {t.deactivate}
            </Button>
          ) : (
            <Button
              size="xs"
              variant="subtle"
              color="green"
              onClick={() => activate.mutate()}
              loading={activate.isPending}
            >
              {t.activate}
            </Button>
          )}
        </Group>
        {activate.isError && (
          <Text size="xs" c="red">
            {makeupPolicyErrorMessage(activate.error)}
          </Text>
        )}
      </Table.Td>
    </Table.Tr>
  );
}

function DeactivateDialog({
  policy,
  onClose,
}: {
  policy: MakeupPolicy | null;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const deactivate = useMutation({
    mutationFn: (id: string) => deactivateMakeupPolicy(id),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['makeup-policies'] }),
        queryClient.invalidateQueries({ queryKey: ['pass-products'] }),
      ]);
      close();
    },
  });

  function close() {
    deactivate.reset();
    onClose();
  }

  return (
    <Modal opened={policy !== null} onClose={close} title={t.confirmDeactivate.title}>
      {policy && (
        <Stack>
          <Text size="sm">{t.confirmDeactivate.text(policy.name)}</Text>
          {policy.productCount > 0 && (
            <Alert color="orange">
              {t.confirmDeactivate.products(t.productCount(policy.productCount))}
            </Alert>
          )}
          {deactivate.isError && (
            <Alert color="red">{makeupPolicyErrorMessage(deactivate.error)}</Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t.confirmDeactivate.cancel}
            </Button>
            <Button
              color="red"
              onClick={() => deactivate.mutate(policy.id)}
              loading={deactivate.isPending}
            >
              {t.confirmDeactivate.confirm}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
