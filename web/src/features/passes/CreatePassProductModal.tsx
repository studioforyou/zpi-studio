import { useState } from 'react';
import { Alert, Button, Divider, Group, Modal, Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { passErrorMessage, toMinorUnits } from './passFormat';
import { ProductFields, VariantFields } from './PassProductForm';
import {
  emptyVariant,
  isVariantComplete,
  type ProductFormValues,
  type VariantFormValues,
} from './passFormValues';
import { createPassProduct, type ServiceType } from './passesApi';

const emptyProduct: ProductFormValues = {
  name: '',
  description: '',
  kind: 'ENTRY_PASS',
  audience: 'ANY',
  serviceTypeIds: [],
  suspensionAllowed: false,
  maxSuspensionDays: null,
};

type Props = {
  opened: boolean;
  onClose: () => void;
  serviceTypes: ServiceType[];
};

export function CreatePassProductModal({ opened, onClose, serviceTypes }: Props) {
  const queryClient = useQueryClient();
  const [product, setProduct] = useState(emptyProduct);
  const [variant, setVariant] = useState<VariantFormValues>(emptyVariant);
  const [submitted, setSubmitted] = useState(false);

  const create = useMutation({
    mutationFn: () =>
      createPassProduct({
        name: product.name.trim(),
        description: product.description.trim() || null,
        kind: product.kind,
        audience: product.audience,
        serviceTypeIds: product.serviceTypeIds,
        suspensionAllowed: product.suspensionAllowed,
        maxSuspensionDays: product.suspensionAllowed ? product.maxSuspensionDays : null,
        variants: [
          {
            entriesCount: variant.unlimitedEntries ? null : variant.entriesCount,
            validityDays: variant.validityDays ?? 0,
            price: { amountMinor: toMinorUnits(variant.price ?? 0), currency: 'PLN' },
          },
        ],
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['pass-products'] });
      close();
    },
  });

  function close() {
    setProduct(emptyProduct);
    setVariant(emptyVariant);
    setSubmitted(false);
    create.reset();
    onClose();
  }

  function submit() {
    setSubmitted(true);
    if (product.name.trim() !== '' && isVariantComplete(variant)) {
      create.mutate();
    }
  }

  return (
    <Modal opened={opened} onClose={close} title={pl.passes.form.createTitle} size="lg">
      <Stack>
        <ProductFields
          values={product}
          onChange={setProduct}
          serviceTypes={serviceTypes}
          showErrors={submitted}
        />
        <Divider label={pl.passes.form.firstVariant} labelPosition="left" />
        <VariantFields values={variant} onChange={setVariant} />
        {submitted && !isVariantComplete(variant) && (
          <Text c="red" size="sm">
            {pl.passes.form.required}
          </Text>
        )}
        {create.isError && <Alert color="red">{passErrorMessage(create.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {pl.passes.form.cancel}
          </Button>
          <Button onClick={submit} loading={create.isPending}>
            {pl.passes.form.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
