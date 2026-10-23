import { apiFetch } from '@/shared/api/apiClient';

export const passProductKinds = [
  'ENTRY_PASS',
  'SINGLE_ENTRY',
  'PT_PACKAGE',
  'TIME_PASS',
  'VOUCHER',
] as const;
export type PassProductKind = (typeof passProductKinds)[number];

export const passProductAudiences = ['ANY', 'ADULT', 'CHILD'] as const;
export type PassProductAudience = (typeof passProductAudiences)[number];

/** Money in minor units (API conventions, section 7): 12000 PLN = 120.00 PLN. */
export type Money = { amountMinor: number; currency: string };

export type PassProductVariant = {
  id: string;
  /** `null` = unlimited entries. */
  entriesCount: number | null;
  validityDays: number;
  price: Money;
  isActive: boolean;
  validFrom: string;
  validTo: string | null;
};

export type PassProduct = {
  id: string;
  name: string;
  description: string | null;
  kind: PassProductKind;
  audience: PassProductAudience;
  suspensionAllowed: boolean;
  maxSuspensionDays: number | null;
  isActive: boolean;
  serviceTypeIds: string[];
  variants: PassProductVariant[];
};

export type ServiceType = {
  id: string;
  name: string;
  category: string;
  level: string | null;
  isActive: boolean;
};

export type VariantInput = {
  entriesCount: number | null;
  validityDays: number;
  price: Money;
};

export type CreatePassProduct = {
  name: string;
  description: string | null;
  kind: PassProductKind;
  audience: PassProductAudience;
  suspensionAllowed: boolean;
  maxSuspensionDays: number | null;
  serviceTypeIds: string[];
  variants: VariantInput[];
};

/** Fields left out stay unchanged; an empty description removes it. */
export type UpdatePassProduct = Partial<Omit<CreatePassProduct, 'kind' | 'variants'>>;

/** `active`: `true` active products, `false` archived ones, `undefined` all. */
export async function listPassProducts(active?: boolean): Promise<PassProduct[]> {
  const query = active === undefined ? '' : `?active=${active}`;
  const response = await apiFetch<{ items: PassProduct[] }>(`/api/v1/pass-products${query}`);
  return response.items;
}

export function createPassProduct(product: CreatePassProduct): Promise<PassProduct> {
  return apiFetch<PassProduct>('/api/v1/pass-products', { method: 'POST', body: product });
}

export function updatePassProduct(id: string, changes: UpdatePassProduct): Promise<PassProduct> {
  return apiFetch<PassProduct>(`/api/v1/pass-products/${id}`, { method: 'PATCH', body: changes });
}

export function archivePassProduct(id: string): Promise<PassProduct> {
  return apiFetch<PassProduct>(`/api/v1/pass-products/${id}/archive`, { method: 'POST' });
}

export function restorePassProduct(id: string): Promise<PassProduct> {
  return apiFetch<PassProduct>(`/api/v1/pass-products/${id}/restore`, { method: 'POST' });
}

export function addPassProductVariant(
  productId: string,
  variant: VariantInput,
): Promise<PassProductVariant> {
  return apiFetch<PassProductVariant>(`/api/v1/pass-products/${productId}/variants`, {
    method: 'POST',
    body: variant,
  });
}

export function deactivatePassProductVariant(
  productId: string,
  variantId: string,
): Promise<PassProductVariant> {
  return apiFetch<PassProductVariant>(
    `/api/v1/pass-products/${productId}/variants/${variantId}/deactivate`,
    { method: 'POST' },
  );
}

/** Creates a new variant with the new price; the old one stays in history as inactive. */
export function changePassProductVariantPrice(
  productId: string,
  variantId: string,
  price: Money,
): Promise<PassProductVariant> {
  return apiFetch<PassProductVariant>(
    `/api/v1/pass-products/${productId}/variants/${variantId}/change-price`,
    { method: 'POST', body: { price } },
  );
}

export async function listServiceTypes(): Promise<ServiceType[]> {
  const response = await apiFetch<{ items: ServiceType[] }>('/api/v1/service-types');
  return response.items;
}
