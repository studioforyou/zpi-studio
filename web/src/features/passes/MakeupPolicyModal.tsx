import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Group,
  Modal,
  NumberInput,
  SegmentedControl,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import {
  createMakeupPolicy,
  updateMakeupPolicy,
  type MakeupExpiryCheck,
  type MakeupPolicy,
  type MakeupPolicyInput,
} from './makeupPoliciesApi';
import { makeupPolicyErrorMessage } from './makeupPolicyFormat';

const t = pl.makeupPolicies;

type FormValues = {
  name: string;
  validityDays: number | null;
  unlimited: boolean;
  maxPerPass: number | null;
  grantOnInTimeCancel: boolean;
  grantOnStudioCancel: boolean;
  expiryCheckedAgainst: MakeupExpiryCheck;
};

const emptyPolicy: FormValues = {
  name: '',
  validityDays: 30,
  unlimited: true,
  maxPerPass: null,
  grantOnInTimeCancel: true,
  grantOnStudioCancel: true,
  expiryCheckedAgainst: 'EVENT_DATE',
};

function toFormValues(policy: MakeupPolicy): FormValues {
  return {
    name: policy.name,
    validityDays: policy.validityDays,
    unlimited: policy.maxPerPass === null,
    maxPerPass: policy.maxPerPass,
    grantOnInTimeCancel: policy.grantOnInTimeCancel,
    grantOnStudioCancel: policy.grantOnStudioCancel,
    expiryCheckedAgainst: policy.expiryCheckedAgainst,
  };
}

type Props = {
  opened: boolean;
  /** The policy to edit; `null` creates a new one. */
  policy: MakeupPolicy | null;
  onClose: () => void;
};

export function MakeupPolicyModal({ opened, policy, onClose }: Props) {
  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={policy ? t.form.editTitle : t.form.createTitle}
      size="lg"
    >
      {/* The key resets the form when another policy is opened. */}
      {opened && <MakeupPolicyForm key={policy?.id ?? 'new'} policy={policy} onClose={onClose} />}
    </Modal>
  );
}

function MakeupPolicyForm({ policy, onClose }: Omit<Props, 'opened'>) {
  const queryClient = useQueryClient();
  const [values, setValues] = useState<FormValues>(() =>
    policy ? toFormValues(policy) : emptyPolicy,
  );
  const [submitted, setSubmitted] = useState(false);
  const set = <K extends keyof FormValues>(key: K, value: FormValues[K]) =>
    setValues((current) => ({ ...current, [key]: value }));

  const nameError = values.name.trim() === '' ? t.form.required : undefined;
  const daysError =
    values.validityDays === null
      ? t.form.required
      : values.validityDays <= 0
        ? t.form.positive
        : undefined;
  const limitError =
    values.unlimited || (values.maxPerPass !== null && values.maxPerPass > 0)
      ? undefined
      : t.form.positive;

  const save = useMutation({
    mutationFn: () => {
      const input: MakeupPolicyInput = {
        name: values.name.trim(),
        validityDays: values.validityDays ?? 0,
        maxPerPass: values.unlimited ? null : values.maxPerPass,
        grantOnInTimeCancel: values.grantOnInTimeCancel,
        grantOnStudioCancel: values.grantOnStudioCancel,
        expiryCheckedAgainst: values.expiryCheckedAgainst,
      };
      return policy ? updateMakeupPolicy(policy.id, input) : createMakeupPolicy(input);
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['makeup-policies'] }),
        queryClient.invalidateQueries({ queryKey: ['pass-products'] }),
      ]);
      onClose();
    },
  });

  function submit() {
    setSubmitted(true);
    if (!nameError && !daysError && !limitError) {
      save.mutate();
    }
  }

  const toNumber = (value: string | number) => (typeof value === 'number' ? value : null);

  return (
    <Stack>
      <TextInput
        label={t.form.name}
        placeholder={t.form.namePlaceholder}
        required
        maxLength={120}
        value={values.name}
        onChange={(event) => set('name', event.currentTarget.value)}
        error={submitted ? nameError : undefined}
      />
      <Group grow align="flex-start">
        <NumberInput
          label={t.form.validityDays}
          description={t.form.validityHint}
          inputWrapperOrder={['label', 'input', 'description', 'error']}
          required
          min={1}
          allowDecimal={false}
          value={values.validityDays ?? ''}
          onChange={(days) => set('validityDays', toNumber(days))}
          error={submitted ? daysError : undefined}
        />
        <NumberInput
          label={t.form.maxPerPass}
          min={1}
          allowDecimal={false}
          disabled={values.unlimited}
          value={values.unlimited ? '' : (values.maxPerPass ?? '')}
          onChange={(max) => set('maxPerPass', toNumber(max))}
          error={submitted ? limitError : undefined}
          inputContainer={(input) => (
            <Group gap="md" wrap="nowrap">
              <Box w={120}>{input}</Box>
              <Checkbox
                label={t.form.noLimit}
                checked={values.unlimited}
                onChange={(event) => set('unlimited', event.currentTarget.checked)}
              />
            </Group>
          )}
        />
      </Group>
      <Stack gap={6}>
        <Text size="sm" fw={500}>
          {t.form.grantFor}
        </Text>
        <Checkbox
          label={t.form.grantOnInTimeCancel}
          checked={values.grantOnInTimeCancel}
          onChange={(event) => set('grantOnInTimeCancel', event.currentTarget.checked)}
        />
        <Checkbox
          label={t.form.grantOnStudioCancel}
          checked={values.grantOnStudioCancel}
          onChange={(event) => set('grantOnStudioCancel', event.currentTarget.checked)}
        />
      </Stack>
      <Stack gap={6}>
        <Text size="sm" fw={500}>
          {t.form.expiryCheck}
        </Text>
        <SegmentedControl
          value={values.expiryCheckedAgainst}
          onChange={(value) => set('expiryCheckedAgainst', value as MakeupExpiryCheck)}
          data={[
            { value: 'EVENT_DATE', label: t.expiryChecks.EVENT_DATE },
            { value: 'BOOK_DATE', label: t.expiryChecks.BOOK_DATE },
          ]}
        />
        <Text size="xs" c="dimmed">
          {t.form.expiryCheckHint[values.expiryCheckedAgainst]}
        </Text>
      </Stack>
      {policy && <Alert color="blue">{t.form.changeNote}</Alert>}
      {save.isError && <Alert color="red">{makeupPolicyErrorMessage(save.error)}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>
          {t.form.cancel}
        </Button>
        <Button onClick={submit} loading={save.isPending}>
          {t.form.save}
        </Button>
      </Group>
    </Stack>
  );
}
