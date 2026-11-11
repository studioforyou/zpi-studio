import {
  Alert,
  Button,
  Chip,
  Group,
  MultiSelect,
  Paper,
  SimpleGrid,
  Stack,
  Text,
} from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { reportEventKinds, type ReportEventKind } from './attendanceReportsApi';
import { PeriodPicker } from './PeriodPicker';
import { useFilterOptions, type FilterOption, type FilterOptionGroup } from './useFilterOptions';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.filters;

/** The period and the events every report below covers, in one place above them. */
export function StatisticsFilterBar({ filters }: { filters: StatisticsFilters }) {
  const options = useFilterOptions();
  const { events, setEvents } = filters;

  return (
    <Paper withBorder radius="md" p="md" component="section" aria-label={texts.label}>
      <Stack gap="md">
        <Group align="flex-start" justify="space-between" gap="md" wrap="wrap">
          <PeriodPicker state={filters.period} />
          <Stack gap={4}>
            <Text size="sm" fw={500} id="statistics-kind-label">
              {texts.kind}
            </Text>
            <Chip.Group
              multiple
              value={events.kinds}
              // At least one kind stays chosen.
              onChange={(value) =>
                value.length > 0 && setEvents({ kinds: value as ReportEventKind[] })
              }
            >
              <Group gap={6} role="group" aria-labelledby="statistics-kind-label">
                {reportEventKinds.map((kind) => (
                  <Chip key={kind} value={kind} size="sm">
                    {texts.kinds[kind]}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
          </Stack>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }} spacing="md">
          <FilterSelect
            label={texts.services}
            data={options.services}
            value={events.serviceTypeIds}
            onChange={(serviceTypeIds) => setEvents({ serviceTypeIds })}
          />
          <FilterSelect
            label={texts.staff}
            data={options.staff}
            value={events.staffIds}
            onChange={(staffIds) => setEvents({ staffIds })}
          />
          <FilterSelect
            label={texts.groups}
            data={options.groups}
            value={events.trainingGroupIds}
            onChange={(trainingGroupIds) => setEvents({ trainingGroupIds })}
          />
          <FilterSelect
            label={texts.rooms}
            data={options.rooms}
            value={events.resourceIds}
            onChange={(resourceIds) => setEvents({ resourceIds })}
          />
        </SimpleGrid>
        {filters.eventsFiltered && (
          <Button
            variant="subtle"
            size="compact-sm"
            onClick={filters.clearEvents}
            style={{ alignSelf: 'flex-start' }}
          >
            {texts.clear}
          </Button>
        )}
        {options.isError && (
          <Alert color="orange" py="xs">
            {texts.lookupError}
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}

type FilterSelectProps = {
  label: string;
  data: FilterOption[] | FilterOptionGroup[];
  value: string[];
  onChange: (value: string[]) => void;
};

function FilterSelect({ label, data, value, onChange }: FilterSelectProps) {
  return (
    <MultiSelect
      label={label}
      placeholder={value.length === 0 ? texts.all : undefined}
      data={data}
      value={value}
      onChange={onChange}
      searchable
      clearable
      maxDropdownHeight={280}
      comboboxProps={{ withinPortal: true }}
    />
  );
}
