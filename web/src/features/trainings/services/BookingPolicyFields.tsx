import { Divider, NumberInput, Select, SimpleGrid, Stack, Switch, Text } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type {
  NumericField,
  BookingPolicyFormValues,
  BookingPolicyErrors,
} from './bookingPolicyFormValues';

type Props = {
  values: BookingPolicyFormValues;
  onChange: (values: BookingPolicyFormValues) => void;
  groupRulesAllowed: boolean;
  minimumParticipants: number;
  disabled: boolean;
  errors: BookingPolicyErrors;
};

export function BookingPolicyFields({
  values,
  onChange,
  groupRulesAllowed,
  minimumParticipants,
  disabled,
  errors,
}: Props) {
  const texts = pl.trainings.policy;
  const set = <K extends keyof BookingPolicyFormValues>(
    field: K,
    value: BookingPolicyFormValues[K],
  ) => onChange({ ...values, [field]: value });

  function number(field: NumericField, minimum = 0) {
    return (
      <NumberInput
        label={texts[field]}
        description={texts.hints[field]}
        min={minimum}
        max={32767}
        allowDecimal={false}
        required
        value={values[field] ?? ''}
        onChange={(value) => set(field, value)}
        error={errors[field]}
        disabled={disabled}
      />
    );
  }

  return (
    <Stack>
      <Divider label={texts.bookingTitle} labelPosition="left" />
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        {number('bookingOpensDaysBefore')}
        {number('bookingClosesMinutesBefore')}
      </SimpleGrid>
      {groupRulesAllowed && (
        <>
          <Switch
            label={texts.waitlistEnabled}
            description={texts.waitlistHint}
            checked={values.waitlistEnabled}
            onChange={(event) => set('waitlistEnabled', event.currentTarget.checked)}
            disabled={disabled}
          />
          {values.waitlistEnabled && (
            <>
              <Switch
                label={texts.waitlistConfirmation}
                description={texts.waitlistConfirmationHint}
                checked={values.waitlistConfirmMinutes !== 0}
                onChange={(event) =>
                  set('waitlistConfirmMinutes', event.currentTarget.checked ? 30 : 0)
                }
                disabled={disabled}
              />
              {values.waitlistConfirmMinutes !== 0 && number('waitlistConfirmMinutes', 1)}
            </>
          )}
        </>
      )}
      <Divider label={texts.cancellationTitle} labelPosition="left" />
      <Switch
        label={texts.cancellationAllowed}
        description={texts.cancellationHint}
        checked={values.cancellationAllowed}
        onChange={(event) => set('cancellationAllowed', event.currentTarget.checked)}
        disabled={disabled}
      />
      {values.cancellationAllowed && (
        <>
          {number('freeCancelHours')}
          <Select
            label={texts.afterDeadline}
            description={texts.afterDeadlineHint}
            allowDeselect={false}
            data={[
              { value: 'BLOCK', label: texts.blockLateCancellation },
              { value: 'FORFEIT', label: texts.forfeitLateCancellation },
              { value: 'FREE', label: texts.freeLateCancellation },
            ]}
            value={
              !values.lateCancelAllowed
                ? 'BLOCK'
                : values.lateCancelForfeitsEntry
                  ? 'FORFEIT'
                  : 'FREE'
            }
            onChange={(value) =>
              value &&
              onChange({
                ...values,
                lateCancelAllowed: value !== 'BLOCK',
                lateCancelForfeitsEntry: value === 'FORFEIT',
              })
            }
            disabled={disabled}
          />
        </>
      )}
      {groupRulesAllowed && (
        <>
          <Divider label={texts.autoCancelTitle} labelPosition="left" />
          <Switch
            label={texts.autoCancelEnabled}
            description={texts.autoCancelHint}
            checked={values.autoCancelEnabled}
            onChange={(event) => set('autoCancelEnabled', event.currentTarget.checked)}
            error={errors.autoCancelEnabled}
            disabled={disabled}
          />
          {values.autoCancelEnabled && (
            <>
              <Text size="sm">
                {texts.minimumSummary.replace('{count}', String(minimumParticipants))}
              </Text>
              {errors.autoCancelMinParticipants && (
                <Text size="sm" c="red">
                  {errors.autoCancelMinParticipants}
                </Text>
              )}
              {number('autoCancelHoursBefore')}
            </>
          )}
        </>
      )}
    </Stack>
  );
}
