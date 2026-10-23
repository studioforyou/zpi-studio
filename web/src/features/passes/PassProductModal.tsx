import { useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  Modal,
  NumberInput,
  Stack,
  Switch,
  Table,
  Text,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { formatEntries, formatMoney, passErrorMessage, toMinorUnits } from './passFormat';
import { ProductFields, VariantFields } from './PassProductForm';
import {
  emptyVariant,
  isVariantComplete,
  type ProductFormValues,
  type VariantFormValues,
} from './passFormValues';
import {
  addPassProductVariant,
  archivePassProduct,
  changePassProductVariantPrice,
  deactivatePassProductVariant,
  restorePassProduct,
  updatePassProduct,
  type PassProduct,
  type PassProductVariant,
  type ServiceType,
} from './passesApi';

type Props = {
  product: PassProduct | null;
  onClose: () => void;
  serviceTypes: ServiceType[];
};

export function PassProductModal({ product, onClose, serviceTypes }: Props) {
  return (
    <Modal opened={product !== null} onClose={onClose} title={pl.passes.form.editTitle} size="xl">
      {/* The key resets the form state when another product is opened. */}
      {product && (
        <PassProductDetails
          key={product.id}
          product={product}
          onClose={onClose}
          serviceTypes={serviceTypes}
        />
      )}
    </Modal>
  );
}

function toFormValues(product: PassProduct): ProductFormValues {
  return {
    name: product.name,
    description: product.description ?? '',
    kind: product.kind,
    audience: product.audience,
    serviceTypeIds: product.serviceTypeIds,
    suspensionAllowed: product.suspensionAllowed,
    maxSuspensionDays: product.maxSuspensionDays,
  };
}

function PassProductDetails({
  product,
  onClose,
  serviceTypes,
}: Required<Props> & { product: PassProduct }) {
  const queryClient = useQueryClient();
  const [values, setValues] = useState(() => toFormValues(product));
  const [newVariant, setNewVariant] = useState<VariantFormValues>(emptyVariant);
  const [variantSubmitted, setVariantSubmitted] = useState(false);
  const [showInactive, setShowInactive] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['pass-products'] });

  const save = useMutation({
    mutationFn: () =>
      updatePassProduct(product.id, {
        name: values.name.trim(),
        // An empty description removes it.
        description: values.description.trim(),
        audience: values.audience,
        serviceTypeIds: values.serviceTypeIds,
        suspensionAllowed: values.suspensionAllowed,
        maxSuspensionDays: values.suspensionAllowed ? values.maxSuspensionDays : null,
      }),
    onSuccess: refresh,
  });
  const toggleArchive = useMutation({
    mutationFn: () =>
      product.isActive ? archivePassProduct(product.id) : restorePassProduct(product.id),
    onSuccess: async () => {
      await refresh();
      onClose();
    },
  });
  const addVariant = useMutation({
    mutationFn: () =>
      addPassProductVariant(product.id, {
        entriesCount: newVariant.unlimitedEntries ? null : newVariant.entriesCount,
        validityDays: newVariant.validityDays ?? 0,
        price: { amountMinor: toMinorUnits(newVariant.price ?? 0), currency: 'PLN' },
      }),
    onSuccess: async () => {
      setNewVariant(emptyVariant);
      setVariantSubmitted(false);
      await refresh();
    },
  });

  function submitVariant() {
    setVariantSubmitted(true);
    if (isVariantComplete(newVariant)) {
      addVariant.mutate();
    }
  }

  const variants = product.variants.filter((variant) => showInactive || variant.isActive);
  const error = save.error ?? toggleArchive.error ?? addVariant.error;

  return (
    <Stack>
      {!product.isActive && <Alert color="gray">{pl.passes.archivedNote}</Alert>}
      <ProductFields
        values={values}
        onChange={setValues}
        serviceTypes={serviceTypes}
        kindLocked
        showErrors
      />
      <Group justify="space-between">
        <Button
          variant="light"
          color={product.isActive ? 'red' : 'green'}
          onClick={() => toggleArchive.mutate()}
          loading={toggleArchive.isPending}
        >
          {product.isActive ? pl.passes.archive : pl.passes.restore}
        </Button>
        <Button
          onClick={() => save.mutate()}
          loading={save.isPending}
          disabled={values.name.trim() === ''}
        >
          {pl.passes.form.save}
        </Button>
      </Group>

      <Divider />
      <Group justify="space-between">
        <Text fw={600}>{pl.passes.variants.title}</Text>
        <Switch
          label={pl.passes.variants.showInactive}
          checked={showInactive}
          onChange={(event) => setShowInactive(event.currentTarget.checked)}
        />
      </Group>
      <Table>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{pl.passes.variants.entries}</Table.Th>
            <Table.Th>{pl.passes.variants.validityDays}</Table.Th>
            <Table.Th>{pl.passes.variants.price}</Table.Th>
            <Table.Th>{pl.passes.variants.status}</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {variants.map((variant) => (
            <VariantRow
              key={variant.id}
              productId={product.id}
              productActive={product.isActive}
              variant={variant}
              onChanged={refresh}
            />
          ))}
        </Table.Tbody>
      </Table>

      {product.isActive && (
        <Stack gap="xs">
          <VariantFields
            values={newVariant}
            onChange={setNewVariant}
            showErrors={variantSubmitted}
          />
          <Group justify="flex-end">
            <Button variant="light" onClick={submitVariant} loading={addVariant.isPending}>
              {pl.passes.variants.add}
            </Button>
          </Group>
        </Stack>
      )}
      {error && <Alert color="red">{passErrorMessage(error)}</Alert>}
    </Stack>
  );
}

type VariantRowProps = {
  productId: string;
  productActive: boolean;
  variant: PassProductVariant;
  onChanged: () => Promise<void>;
};

function VariantRow({ productId, productActive, variant, onChanged }: VariantRowProps) {
  const [newPrice, setNewPrice] = useState<number | null>(null);
  const [editingPrice, setEditingPrice] = useState(false);

  const changePrice = useMutation({
    mutationFn: () =>
      changePassProductVariantPrice(productId, variant.id, {
        amountMinor: toMinorUnits(newPrice ?? 0),
        currency: variant.price.currency,
      }),
    onSuccess: async () => {
      setEditingPrice(false);
      await onChanged();
    },
  });
  const deactivate = useMutation({
    mutationFn: () => deactivatePassProductVariant(productId, variant.id),
    onSuccess: onChanged,
  });
  const error = changePrice.error ?? deactivate.error;

  return (
    <>
      <Table.Tr>
        <Table.Td>{formatEntries(variant.entriesCount)}</Table.Td>
        <Table.Td>{pl.passes.days(variant.validityDays)}</Table.Td>
        <Table.Td>{formatMoney(variant.price)}</Table.Td>
        <Table.Td>
          <Badge color={variant.isActive ? 'green' : 'gray'} variant="light">
            {variant.isActive ? pl.passes.status.active : pl.passes.status.inactive}
          </Badge>
        </Table.Td>
        <Table.Td>
          {productActive && variant.isActive && (
            <Group gap="xs" justify="flex-end" wrap="nowrap">
              <Button size="xs" variant="subtle" onClick={() => setEditingPrice((open) => !open)}>
                {pl.passes.variants.changePrice}
              </Button>
              <Button
                size="xs"
                variant="subtle"
                color="red"
                onClick={() => deactivate.mutate()}
                loading={deactivate.isPending}
              >
                {pl.passes.variants.deactivate}
              </Button>
            </Group>
          )}
        </Table.Td>
      </Table.Tr>
      {(editingPrice || error) && (
        <Table.Tr>
          <Table.Td colSpan={5}>
            <Stack gap="xs">
              {editingPrice && (
                <Group align="flex-end">
                  <NumberInput
                    label={pl.passes.variants.newPrice}
                    description={pl.passes.variants.changePriceHint}
                    min={0}
                    decimalScale={2}
                    decimalSeparator=","
                    value={newPrice ?? ''}
                    onChange={(price) => setNewPrice(typeof price === 'number' ? price : null)}
                  />
                  <Button
                    onClick={() => changePrice.mutate()}
                    loading={changePrice.isPending}
                    disabled={newPrice === null}
                  >
                    {pl.passes.form.save}
                  </Button>
                </Group>
              )}
              {error && <Alert color="red">{passErrorMessage(error)}</Alert>}
            </Stack>
          </Table.Td>
        </Table.Tr>
      )}
    </>
  );
}
