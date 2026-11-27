import type { LapsedCustomer } from './lapsedCustomersApi';

/** A pass the customer can use now or later; a suspended one does not count, it was paused on purpose. */
export function hasUsablePass(row: LapsedCustomer): boolean {
  return row.pass?.state === 'VALID';
}

/** No pass at all, or only one that expired or ran out of entries. */
export function hasNoPassLeft(row: LapsedCustomer): boolean {
  return row.pass === null || row.pass.state === 'ENDED';
}

/** Whether the row matches the search: name, guardian, e-mail or phone, ignoring case and spaces. */
export function matches(row: LapsedCustomer, search: string): boolean {
  const query = normalize(search);
  if (query === '') {
    return true;
  }
  return [
    `${row.firstName} ${row.lastName}`,
    `${row.lastName} ${row.firstName}`,
    row.guardianName,
    row.email,
    row.phone,
  ].some((value) => value !== null && normalize(value).includes(query));
}

function normalize(value: string): string {
  return value.toLocaleLowerCase('pl-PL').replace(/\s+/g, '');
}
