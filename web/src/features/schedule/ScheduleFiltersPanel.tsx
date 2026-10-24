import { useState } from 'react';
import {
  Badge,
  Button,
  Chip,
  Collapse,
  Group,
  MultiSelect,
  Paper,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { eventKinds, eventStatuses, eventVisibilities } from './scheduleApi';
import {
  activeFilterCount,
  ageGroups,
  emptyFilters,
  recurrenceFilters,
  staffingGaps,
  timesOfDay,
  type AgeGroup,
  type RecurrenceFilter,
  type ScheduleFilters,
  type StaffingGap,
  type TimeOfDay,
} from './scheduleFilters';
import type { ScheduleLookups } from './useScheduleLookups';

type ScheduleFiltersPanelProps = {
  filters: ScheduleFilters;
  onChange: (filters: ScheduleFilters) => void;
  lookups: ScheduleLookups;
};

function unique(values: (string | null)[]): string[] {
  return [...new Set(values.filter((value): value is string => value !== null))].sort();
}

/**
 * The schedule's filters: the most used ones in one row, the rest behind "More filters".
 * Values of one filter are alternatives; different filters must all match.
 */
export function ScheduleFiltersPanel({ filters, onChange, lookups }: ScheduleFiltersPanelProps) {
  const texts = pl.schedule.filters;
  const [expanded, setExpanded] = useState(false);
  const count = activeFilterCount(filters);
  const quickKeys: (keyof ScheduleFilters)[] = ['search', 'kinds', 'staffIds', 'roomIds'];
  const moreCount =
    count -
    quickKeys.filter((key) => activeFilterCount({ ...emptyFilters, [key]: filters[key] })).length;

  function set<K extends keyof ScheduleFilters>(key: K, value: ScheduleFilters[K]) {
    onChange({ ...filters, [key]: value });
  }

  const staffOptions = lookups.staff.map((member) => ({
    value: member.id,
    label: member.isActive
      ? member.displayName
      : `${member.displayName} (${pl.schedule.assignmentFields.inactive})`,
  }));
  const roomOptions = lookups.rooms.map((room) => ({ value: room.id, label: room.name }));
  const serviceOptions = lookups.serviceTypes.map((type) => ({ value: type.id, label: type.name }));
  const categoryOptions = unique(lookups.serviceTypes.map((type) => type.category)).map(
    (category) => ({ value: category, label: pl.schedule.categories[category] ?? category }),
  );
  const levelOptions = unique(lookups.serviceTypes.map((type) => type.level)).map((level) => ({
    value: level,
    label: pl.schedule.levels[level] ?? level,
  }));
  const groupOptions = lookups.groups.map((group) => ({ value: group.id, label: group.name }));

  return (
    <Paper withBorder p="sm" radius="md">
      <Stack gap="sm">
        <Group align="flex-end" gap="sm" wrap="wrap">
          <TextInput
            label={texts.search}
            placeholder={texts.searchPlaceholder}
            value={filters.search}
            onChange={(event) => set('search', event.currentTarget.value)}
            w={240}
          />
          <MultiSelect
            label={texts.kinds}
            placeholder={texts.all}
            data={eventKinds.map((kind) => ({ value: kind, label: pl.schedule.kinds[kind] }))}
            value={filters.kinds}
            onChange={(value) => set('kinds', value as ScheduleFilters['kinds'])}
            clearable
            w={220}
          />
          <MultiSelect
            label={texts.staff}
            placeholder={texts.all}
            data={staffOptions}
            value={filters.staffIds}
            onChange={(value) => set('staffIds', value)}
            searchable
            clearable
            w={220}
          />
          <MultiSelect
            label={texts.rooms}
            placeholder={texts.all}
            data={roomOptions}
            value={filters.roomIds}
            onChange={(value) => set('roomIds', value)}
            searchable
            clearable
            w={200}
          />
          <Button
            variant={expanded ? 'light' : 'default'}
            onClick={() => setExpanded((open) => !open)}
            rightSection={moreCount > 0 ? <Badge size="sm">{moreCount}</Badge> : undefined}
            aria-expanded={expanded}
          >
            {texts.more}
          </Button>
          {count > 0 && (
            <Button variant="subtle" color="gray" onClick={() => onChange(emptyFilters)}>
              {texts.clear}
            </Button>
          )}
        </Group>
        <Collapse expanded={expanded}>
          <Stack gap="sm" pt="xs">
            <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="sm">
              <MultiSelect
                label={texts.serviceTypes}
                placeholder={texts.all}
                data={serviceOptions}
                value={filters.serviceTypeIds}
                onChange={(value) => set('serviceTypeIds', value)}
                searchable
                clearable
              />
              <MultiSelect
                label={texts.categories}
                placeholder={texts.all}
                data={categoryOptions}
                value={filters.categories}
                onChange={(value) => set('categories', value)}
                clearable
              />
              <MultiSelect
                label={texts.levels}
                placeholder={texts.all}
                data={levelOptions}
                value={filters.levels}
                onChange={(value) => set('levels', value)}
                clearable
              />
              <MultiSelect
                label={texts.groups}
                placeholder={texts.all}
                data={groupOptions}
                value={filters.trainingGroupIds}
                onChange={(value) => set('trainingGroupIds', value)}
                searchable
                clearable
              />
              <MultiSelect
                label={texts.statuses}
                placeholder={texts.all}
                data={eventStatuses.map((status) => ({
                  value: status,
                  label: pl.schedule.statuses[status],
                }))}
                value={filters.statuses}
                onChange={(value) => set('statuses', value as ScheduleFilters['statuses'])}
                clearable
              />
              <MultiSelect
                label={texts.visibilities}
                placeholder={texts.all}
                data={eventVisibilities.map((visibility) => ({
                  value: visibility,
                  label: pl.schedule.visibilities[visibility],
                }))}
                value={filters.visibilities}
                onChange={(value) => set('visibilities', value as ScheduleFilters['visibilities'])}
                clearable
              />
            </SimpleGrid>
            <Stack gap={4}>
              <Text size="sm" fw={500}>
                {texts.recurrence}
              </Text>
              <SegmentedControl
                value={filters.recurrence}
                onChange={(value) => set('recurrence', value as RecurrenceFilter)}
                data={recurrenceFilters.map((value) => ({
                  value,
                  label: texts.recurrenceOptions[value],
                }))}
                w="fit-content"
              />
            </Stack>
            <ChipFilter
              label={texts.weekdays}
              options={pl.schedule.weekdaysShort.map((label, index) => ({
                value: String(index + 1),
                label,
              }))}
              value={filters.weekdays.map(String)}
              onChange={(value) => set('weekdays', value.map(Number))}
            />
            <ChipFilter
              label={texts.timesOfDay}
              options={timesOfDay.map((value) => ({
                value,
                label: texts.timesOfDayOptions[value],
              }))}
              value={filters.timesOfDay}
              onChange={(value) => set('timesOfDay', value as TimeOfDay[])}
            />
            <ChipFilter
              label={texts.ageGroups}
              options={ageGroups.map((value) => ({ value, label: texts.ageGroupOptions[value] }))}
              value={filters.ageGroups}
              onChange={(value) => set('ageGroups', value as AgeGroup[])}
            />
            <ChipFilter
              label={texts.gaps}
              options={staffingGaps.map((value) => ({ value, label: texts.gapOptions[value] }))}
              value={filters.gaps}
              onChange={(value) => set('gaps', value as StaffingGap[])}
            />
          </Stack>
        </Collapse>
      </Stack>
    </Paper>
  );
}

type ChipFilterProps = {
  label: string;
  options: { value: string; label: string }[];
  value: string[];
  onChange: (value: string[]) => void;
};

function ChipFilter({ label, options, value, onChange }: ChipFilterProps) {
  return (
    <Stack gap={4}>
      <Text size="sm" fw={500}>
        {label}
      </Text>
      <Chip.Group multiple value={value} onChange={onChange}>
        <Group gap="xs">
          {options.map((option) => (
            <Chip key={option.value} value={option.value} size="sm">
              {option.label}
            </Chip>
          ))}
        </Group>
      </Chip.Group>
    </Stack>
  );
}
