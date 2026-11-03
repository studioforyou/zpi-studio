import { useState } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  NumberInput,
  Select,
  Stack,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { CustomerContact } from './CustomerContact';
import { assignCustomerPass, searchCustomers, type Customer } from './customerPassesApi';
import {
  addDays,
  customerPassErrorMessage,
  formatDate,
  formatEntries,
  formatMoney,
  todayIsoDate,
  toMinorUnits,
} from './passFormat';
import { listPassProducts, type PassProduct } from './passesApi';

type Props = {
  opened: boolean;
  onClose: () => void;
};

function customerLabel(customer: Customer): string {
  const name =
    customer.kind === 'CHILD'
      ? `${customer.displayName} (${pl.customerPasses.child})`
      : customer.displayName;
  return customer.email ? `${name} · ${customer.email}` : name;
}

/** Products on sale that can be assigned to the participant: matching audience, with an active variant. */
function assignableProducts(products: PassProduct[], customer: Customer): PassProduct[] {
  return products.filter(
    (product) =>
      (product.audience === 'ANY' || product.audience === customer.kind) &&
      product.variants.some((variant) => variant.isActive),
  );
}

/** Assigns a pass to a participant chosen by searching their name or e-mail address. */
export function AssignPassModal({ opened, onClose }: Props) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [customer, setCustomer] = useState<Customer | null>(null);
  const [productId, setProductId] = useState<string | null>(null);
  const [variantId, setVariantId] = useState<string | null>(null);
  const [validFrom, setValidFrom] = useState(todayIsoDate);
  const [price, setPrice] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  const customers = useQuery({
    queryKey: ['customers', debouncedSearch],
    queryFn: () => searchCustomers(debouncedSearch),
    enabled: opened,
    placeholderData: keepPreviousData,
  });
  // The chosen participant stays an option even when the current search no longer matches them.
  const found = customers.data?.items ?? [];
  const customerOptions =
    customer && !found.some((item) => item.id === customer.id) ? [customer, ...found] : found;

  const products = useQuery({
    queryKey: ['pass-products', 'active'],
    queryFn: () => listPassProducts(true),
    enabled: opened,
  });
  const options = customer ? assignableProducts(products.data ?? [], customer) : [];
  const product = options.find((candidate) => candidate.id === productId) ?? null;
  const activeVariants = product?.variants.filter((variant) => variant.isActive) ?? [];
  const variant = activeVariants.find((candidate) => candidate.id === variantId) ?? null;

  const assign = useMutation({
    mutationFn: () =>
      assignCustomerPass(customer!.id, {
        variantId: variant!.id,
        validFrom,
        pricePaid:
          price === null
            ? null
            : { amountMinor: toMinorUnits(price), currency: variant!.price.currency },
        reason: reason.trim() || null,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['customer-passes'] });
      close();
    },
  });

  function close() {
    setSearch('');
    setCustomer(null);
    setProductId(null);
    setVariantId(null);
    setValidFrom(todayIsoDate());
    setPrice(null);
    setReason('');
    setSubmitted(false);
    assign.reset();
    onClose();
  }

  function selectCustomer(id: string | null) {
    setCustomer(customerOptions.find((item) => item.id === id) ?? null);
    // Another participant may not suit the chosen product.
    setProductId(null);
    selectVariant(null, []);
  }

  function selectProduct(id: string | null) {
    setProductId(id);
    const variants = options.find((candidate) => candidate.id === id)?.variants ?? [];
    const active = variants.filter((candidate) => candidate.isActive);
    selectVariant(active.length === 1 ? active[0].id : null, active);
  }

  function selectVariant(id: string | null, variants = activeVariants) {
    setVariantId(id);
    const selected = variants.find((candidate) => candidate.id === id);
    setPrice(selected ? selected.price.amountMinor / 100 : null);
  }

  function submit() {
    setSubmitted(true);
    if (customer !== null && variant !== null && validFrom !== '' && price !== null && price >= 0) {
      assign.mutate();
    }
  }

  const required = (missing: boolean) =>
    submitted && missing ? pl.customerPasses.form.required : undefined;

  return (
    <Modal opened={opened} onClose={close} title={pl.customerPasses.form.title} size="lg">
      <Stack>
        <Select
          label={pl.customerPasses.form.customer}
          placeholder={pl.customerPasses.form.customerPlaceholder}
          required
          searchable
          clearable
          searchValue={search}
          onSearchChange={setSearch}
          // The server filters by name, phone and e-mail; the options are not filtered again here.
          filter={({ options: all }) => all}
          data={customerOptions.map((item) => ({ value: item.id, label: customerLabel(item) }))}
          renderOption={({ option }) => {
            const item = customerOptions.find((candidate) => candidate.id === option.value);
            return item ? (
              <CustomerContact
                displayName={
                  item.kind === 'CHILD'
                    ? `${item.displayName} (${pl.customerPasses.child})`
                    : item.displayName
                }
                email={item.email}
                emailOfGuardian={item.emailOfGuardian}
              />
            ) : (
              option.label
            );
          }}
          value={customer?.id ?? null}
          onChange={selectCustomer}
          nothingFoundMessage={pl.customerPasses.noCustomers}
          error={required(customer === null)}
        />
        <Select
          label={pl.customerPasses.form.product}
          required
          data={options.map((option) => ({ value: option.id, label: option.name }))}
          value={productId}
          onChange={selectProduct}
          nothingFoundMessage={pl.customerPasses.form.noProducts}
          disabled={products.isPending || customer === null}
          error={required(customer !== null && product === null)}
        />
        <Select
          label={pl.customerPasses.form.variant}
          required
          data={activeVariants.map((option) => ({
            value: option.id,
            label: pl.customerPasses.form.variantLabel(
              formatEntries(option.entriesCount),
              option.validityDays,
              formatMoney(option.price),
            ),
          }))}
          value={variantId}
          onChange={(id) => selectVariant(id)}
          disabled={product === null}
          error={required(product !== null && variant === null)}
        />
        <TextInput
          type="date"
          label={pl.customerPasses.form.validFrom}
          required
          value={validFrom}
          onChange={(event) => setValidFrom(event.currentTarget.value)}
          description={
            variant && validFrom !== ''
              ? pl.customerPasses.form.validUntil(
                  formatDate(addDays(validFrom, variant.validityDays - 1)),
                )
              : undefined
          }
          error={required(validFrom === '')}
        />
        {validFrom > todayIsoDate() && (
          <Alert color="blue">{pl.customerPasses.form.scheduled}</Alert>
        )}
        <NumberInput
          label={pl.customerPasses.form.price}
          description={pl.customerPasses.form.priceHint}
          required
          min={0}
          decimalScale={2}
          decimalSeparator=","
          value={price ?? ''}
          onChange={(value) => setPrice(typeof value === 'number' ? value : null)}
          disabled={variant === null}
          error={required(variant !== null && price === null)}
        />
        <Textarea
          label={pl.customerPasses.form.reason}
          placeholder={pl.customerPasses.form.reasonPlaceholder}
          maxLength={500}
          rows={2}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
        />
        {assign.isError && <Alert color="red">{customerPassErrorMessage(assign.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {pl.customerPasses.form.cancel}
          </Button>
          <Button onClick={submit} loading={assign.isPending}>
            {pl.customerPasses.form.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
