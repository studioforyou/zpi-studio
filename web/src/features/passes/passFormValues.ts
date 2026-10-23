import type { PassProductAudience, PassProductKind } from './passesApi';

export type ProductFormValues = {
  name: string;
  description: string;
  kind: PassProductKind;
  audience: PassProductAudience;
  serviceTypeIds: string[];
  suspensionAllowed: boolean;
  maxSuspensionDays: number | null;
};

export type VariantFormValues = {
  unlimitedEntries: boolean;
  entriesCount: number | null;
  validityDays: number | null;
  price: number | null;
};

export const emptyVariant: VariantFormValues = {
  unlimitedEntries: false,
  entriesCount: null,
  validityDays: 30,
  price: null,
};

/** Whether the variant has every value the API requires. */
export function isVariantComplete(variant: VariantFormValues): boolean {
  return (
    (variant.unlimitedEntries || (variant.entriesCount ?? 0) > 0) &&
    (variant.validityDays ?? 0) > 0 &&
    variant.price !== null &&
    variant.price >= 0
  );
}
