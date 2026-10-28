import {
  Checkbox,
  Flex,
  Group,
  MultiSelect,
  NumberInput,
  Select,
  Stack,
  Switch,
  Textarea,
  TextInput,
} from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import {
  passProductAudiences,
  passProductKinds,
  type PassProductAudience,
  type PassProductKind,
  type ServiceType,
} from './passesApi';
import type { ProductFormValues, VariantFormValues } from './passFormValues';

type ProductFieldsProps = {
  values: ProductFormValues;
  onChange: (values: ProductFormValues) => void;
  serviceTypes: ServiceType[];
  /** The kind is chosen once, when the product is created. */
  kindLocked?: boolean;
  showErrors?: boolean;
};

export function ProductFields({
  values,
  onChange,
  serviceTypes,
  kindLocked = false,
  showErrors = false,
}: ProductFieldsProps) {
  const set = <K extends keyof ProductFormValues>(key: K, value: ProductFormValues[K]) =>
    onChange({ ...values, [key]: value });
  // Inactive services stay visible when they are already linked to the product.
  const serviceOptions = serviceTypes
    .filter((service) => service.isActive || values.serviceTypeIds.includes(service.id))
    .map((service) => ({ value: service.id, label: service.name }));

  return (
    <Stack gap="sm">
      <TextInput
        label={pl.passes.form.name}
        required
        maxLength={120}
        value={values.name}
        onChange={(event) => set('name', event.currentTarget.value)}
        error={showErrors && values.name.trim() === '' ? pl.passes.form.required : undefined}
      />
      <Textarea
        label={pl.passes.form.description}
        rows={3}
        value={values.description}
        onChange={(event) => set('description', event.currentTarget.value)}
      />
      <Group grow align="flex-start">
        <Select
          label={pl.passes.form.kind}
          data={passProductKinds.map((kind) => ({ value: kind, label: pl.passes.kinds[kind] }))}
          value={values.kind}
          onChange={(kind) => kind && set('kind', kind as PassProductKind)}
          allowDeselect={false}
          disabled={kindLocked}
          description={kindLocked ? pl.passes.form.kindLocked : undefined}
        />
        <Select
          label={pl.passes.form.audience}
          data={passProductAudiences.map((audience) => ({
            value: audience,
            label: pl.passes.audiences[audience],
          }))}
          value={values.audience}
          onChange={(audience) => audience && set('audience', audience as PassProductAudience)}
          allowDeselect={false}
        />
      </Group>
      <MultiSelect
        label={pl.passes.form.services}
        data={serviceOptions}
        value={values.serviceTypeIds}
        onChange={(ids) => set('serviceTypeIds', ids)}
        searchable
        clearable
      />
      <Group align="flex-end">
        {/* Same height as the input, so the switch sits level with its box, not its label. */}
        <Flex h="var(--input-height-sm)" align="center">
          <Switch
            label={pl.passes.form.suspensionAllowed}
            checked={values.suspensionAllowed}
            onChange={(event) => set('suspensionAllowed', event.currentTarget.checked)}
          />
        </Flex>
        {values.suspensionAllowed && (
          <NumberInput
            label={pl.passes.form.maxSuspensionDays}
            min={1}
            allowDecimal={false}
            value={values.maxSuspensionDays ?? ''}
            onChange={(days) => set('maxSuspensionDays', typeof days === 'number' ? days : null)}
          />
        )}
      </Group>
    </Stack>
  );
}

type VariantFieldsProps = {
  values: VariantFormValues;
  onChange: (values: VariantFormValues) => void;
  showErrors?: boolean;
};

export function VariantFields({ values, onChange, showErrors = false }: VariantFieldsProps) {
  const set = <K extends keyof VariantFormValues>(key: K, value: VariantFormValues[K]) =>
    onChange({ ...values, [key]: value });
  const toNumber = (value: string | number) => (typeof value === 'number' ? value : null);
  const required = (missing: boolean) =>
    showErrors && missing ? pl.passes.form.required : undefined;

  return (
    <Stack gap="xs">
      {/* Top alignment keeps the three boxes level, also when one of them shows an error. */}
      <Group align="flex-start" grow>
        <NumberInput
          label={pl.passes.variants.entries}
          min={1}
          allowDecimal={false}
          disabled={values.unlimitedEntries}
          value={values.unlimitedEntries ? '' : (values.entriesCount ?? '')}
          onChange={(count) => set('entriesCount', toNumber(count))}
          error={required(!values.unlimitedEntries && (values.entriesCount ?? 0) <= 0)}
        />
        <NumberInput
          label={pl.passes.variants.validityDays}
          min={1}
          allowDecimal={false}
          value={values.validityDays ?? ''}
          onChange={(days) => set('validityDays', toNumber(days))}
          error={required((values.validityDays ?? 0) <= 0)}
        />
        <NumberInput
          label={pl.passes.variants.price}
          min={0}
          decimalScale={2}
          decimalSeparator=","
          value={values.price ?? ''}
          onChange={(price) => set('price', toNumber(price))}
          error={required(values.price === null || values.price < 0)}
        />
      </Group>
      <Checkbox
        label={pl.passes.variants.unlimitedEntries}
        checked={values.unlimitedEntries}
        onChange={(event) => set('unlimitedEntries', event.currentTarget.checked)}
      />
    </Stack>
  );
}
