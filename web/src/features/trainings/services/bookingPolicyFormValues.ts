import { pl } from '@/shared/i18n/pl';
import type { BookingPolicyValues } from './serviceTypesApi';

export type NumericField =
  | 'freeCancelHours'
  | 'bookingOpensDaysBefore'
  | 'bookingClosesMinutesBefore'
  | 'waitlistConfirmMinutes'
  | 'autoCancelHoursBefore';

export type BookingPolicyFormValues = Omit<BookingPolicyValues, NumericField> &
  Record<NumericField, number | string>;
export type BookingPolicyErrors = Partial<Record<keyof BookingPolicyValues, string>>;

export function validateBookingPolicy(values: BookingPolicyFormValues, groupRulesAllowed: boolean) {
  const errors: BookingPolicyErrors = {};
  const fields: NumericField[] = ['bookingOpensDaysBefore', 'bookingClosesMinutesBefore'];
  if (values.cancellationAllowed) fields.push('freeCancelHours');
  if (groupRulesAllowed && values.waitlistEnabled && values.waitlistConfirmMinutes !== 0) {
    fields.push('waitlistConfirmMinutes');
  }
  if (groupRulesAllowed && values.autoCancelEnabled) fields.push('autoCancelHoursBefore');
  for (const field of fields) {
    const value = values[field];
    const minimum = field === 'waitlistConfirmMinutes' ? 1 : 0;
    if (typeof value !== 'number' || !Number.isInteger(value) || value < minimum || value > 32767) {
      errors[field] = pl.trainings.form.nonnegativeNumber;
    }
  }
  return errors;
}

export function bookingPolicyInput(
  values: BookingPolicyFormValues,
  groupRulesAllowed: boolean,
): BookingPolicyValues {
  return {
    ...values,
    freeCancelHours: Number(values.freeCancelHours),
    bookingOpensDaysBefore: Number(values.bookingOpensDaysBefore),
    bookingClosesMinutesBefore: Number(values.bookingClosesMinutesBefore),
    waitlistEnabled: groupRulesAllowed && values.waitlistEnabled,
    waitlistConfirmMinutes:
      groupRulesAllowed && values.waitlistEnabled ? Number(values.waitlistConfirmMinutes) : 0,
    autoCancelEnabled: groupRulesAllowed && values.autoCancelEnabled,
    autoCancelMinParticipants: groupRulesAllowed ? values.autoCancelMinParticipants : null,
    autoCancelHoursBefore:
      groupRulesAllowed && values.autoCancelEnabled ? Number(values.autoCancelHoursBefore) : null,
  };
}
