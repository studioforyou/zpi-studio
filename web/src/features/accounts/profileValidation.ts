import { pl } from '@/shared/i18n/pl';

// Same limits and character rules as the backend.
export const NAME_MAX_LENGTH = 80;
const PHONE_MAX_LENGTH = 20;
const PERSON_NAME_PATTERN = /^[\p{L}\p{M}]+(?:[ '’.-]+[\p{L}\p{M}]+)*\.?$/u;
const PHONE_PATTERN = /^\+?[0-9][0-9 ()-]*[0-9]$/;

const texts = pl.accounts.details.form;

export type NameFields = 'firstName' | 'lastName' | 'both';

/** Which of the names a save changes, or `null` if neither. */
export function changedNames(
  initial: { firstName: string; lastName: string },
  values: { firstName: string; lastName: string },
): NameFields | null {
  const firstName = values.firstName.trim() !== initial.firstName;
  const lastName = values.lastName.trim() !== initial.lastName;
  if (firstName && lastName) {
    return 'both';
  }
  return firstName ? 'firstName' : lastName ? 'lastName' : null;
}

/** A required person's name: letters joined by spaces, hyphens, apostrophes or dots. */
export function nameError(value: string, maxLength = NAME_MAX_LENGTH): string | undefined {
  const name = value.trim();
  if (!name) {
    return pl.auth.fieldRequired;
  }
  if (name.length > maxLength) {
    return pl.accounts.invite.tooLong(maxLength);
  }
  return PERSON_NAME_PATTERN.test(name) ? undefined : texts.invalidName;
}

/** An optional phone number. */
export function phoneError(value: string): string | undefined {
  const phone = value.trim();
  if (!phone) {
    return undefined;
  }
  if (phone.length > PHONE_MAX_LENGTH) {
    return pl.accounts.invite.tooLong(PHONE_MAX_LENGTH);
  }
  return PHONE_PATTERN.test(phone) ? undefined : texts.invalidPhone;
}

/** Today's date as `YYYY-MM-DD`, the format of date inputs. */
export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

/** The height of the tallest profile form's fields, so the save buttons stay in place. */
export const FIELDS_MIN_HEIGHT = 270;
