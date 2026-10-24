import { useState } from 'react';
import {
  ActionIcon,
  Alert,
  Button,
  Group,
  Loader,
  SegmentedControl,
  Select,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { AgendaView } from './AgendaView';
import { AssignmentsModal } from './AssignmentsModal';
import { EventDetailsDrawer } from './EventDetailsDrawer';
import { EventFormModal } from './EventFormModal';
import { MonthView } from './MonthView';
import { listScheduleEvents, scheduleKeys, type ScheduleEvent } from './scheduleApi';
import { emptyFilters, filterEvents, type ScheduleFilters } from './scheduleFilters';
import { ScheduleFiltersPanel } from './ScheduleFiltersPanel';
import type { ColorBy } from './scheduleFormat';
import { scheduleViews, shiftAnchor, viewRange, type ScheduleView } from './scheduleView';
import { dateRange, DEFAULT_TIME_ZONE, toInstant, todayIn } from './studioTime';
import { TimeGridView } from './TimeGridView';
import { useScheduleLookups } from './useScheduleLookups';

type SchedulePageProps = {
  /** The day the schedule opens on; today in the studio when missing. */
  initialDate?: string;
};

type NewEvent = { date: string; time?: string };

/**
 * The whole studio schedule for administrators: every kind of event in a day, week, month or
 * list view, narrowed by filters. Here administrators plan one-off and recurring events and
 * assign staff and rooms to them.
 */
export function SchedulePage({ initialDate }: SchedulePageProps) {
  const texts = pl.schedule;
  const lookups = useScheduleLookups();
  const timeZone = lookups.timeZone;
  const [view, setView] = useState<ScheduleView>('week');
  const [anchor, setAnchor] = useState(() => initialDate ?? todayIn(DEFAULT_TIME_ZONE));
  const [filters, setFilters] = useState<ScheduleFilters>(emptyFilters);
  const [colorBy, setColorBy] = useState<ColorBy>('service');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [editing, setEditing] = useState<ScheduleEvent | null>(null);
  const [newEvent, setNewEvent] = useState<NewEvent | null>(null);

  const range = viewRange(view, anchor);
  const from = toInstant(range.start, '00:00', timeZone).toISOString();
  const to = toInstant(range.end, '00:00', timeZone).toISOString();
  const events = useQuery({
    queryKey: scheduleKeys.events(from, to),
    queryFn: () => listScheduleEvents(from, to),
    placeholderData: keepPreviousData,
  });

  const allEvents = events.data ?? [];
  const shown = filterEvents(allEvents, filters, {
    timeZone,
    serviceTypes: lookups.serviceTypeById,
  }).sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.endsAt.localeCompare(b.endsAt));
  const selected = allEvents.find((event) => event.id === selectedId) ?? null;
  const today = todayIn(timeZone);

  function showDay(day: string) {
    setAnchor(day);
    setView('day');
  }

  function select(event: ScheduleEvent) {
    setSelectedId(event.id);
  }

  let content;
  if (events.isPending) {
    content = (
      <Group>
        <Loader size="sm" />
        <Text>{texts.loading}</Text>
      </Group>
    );
  } else if (events.isError) {
    content = <Alert color="red">{texts.loadError}</Alert>;
  } else if (view === 'month') {
    content = (
      <MonthView
        start={range.start}
        end={range.end}
        month={anchor}
        events={shown}
        lookups={lookups}
        colorBy={colorBy}
        today={today}
        onSelect={select}
        onShowDay={showDay}
      />
    );
  } else if (view === 'list') {
    content =
      shown.length === 0 ? (
        <Text c="dimmed">{allEvents.length === 0 ? texts.empty : texts.emptyFiltered}</Text>
      ) : (
        <AgendaView events={shown} lookups={lookups} colorBy={colorBy} onSelect={select} />
      );
  } else {
    content = (
      <TimeGridView
        days={dateRange(range.start, range.end)}
        events={shown}
        lookups={lookups}
        colorBy={colorBy}
        today={today}
        onSelect={select}
        onShowDay={showDay}
        onCreateAt={(date, time) => setNewEvent({ date, time })}
      />
    );
  }

  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Title order={2}>{texts.title}</Title>
        <Button onClick={() => setNewEvent({ date: anchor < today ? today : anchor })}>
          {texts.add}
        </Button>
      </Group>
      <Group justify="space-between" wrap="wrap">
        <Group gap="xs">
          <ActionIcon
            variant="default"
            size="lg"
            aria-label={texts.previous}
            onClick={() => setAnchor(shiftAnchor(view, anchor, -1))}
          >
            ‹
          </ActionIcon>
          <Button variant="default" onClick={() => setAnchor(today)}>
            {texts.today}
          </Button>
          <ActionIcon
            variant="default"
            size="lg"
            aria-label={texts.next}
            onClick={() => setAnchor(shiftAnchor(view, anchor, 1))}
          >
            ›
          </ActionIcon>
          <Text fw={600} ml="xs">
            {range.label}
          </Text>
          {events.isFetching && !events.isPending && <Loader size="xs" />}
        </Group>
        <Group gap="sm">
          <Select
            aria-label={texts.colorBy}
            allowDeselect={false}
            data={(['service', 'staff'] as const).map((value) => ({
              value,
              label: `${texts.colorBy} ${texts.colorByOptions[value]}`,
            }))}
            value={colorBy}
            onChange={(value) => value && setColorBy(value as ColorBy)}
            w={230}
          />
          <SegmentedControl
            value={view}
            onChange={(value) => setView(value as ScheduleView)}
            data={scheduleViews.map((value) => ({ value, label: texts.views[value] }))}
          />
        </Group>
      </Group>
      <ScheduleFiltersPanel filters={filters} onChange={setFilters} lookups={lookups} />
      {lookups.isError && <Alert color="yellow">{texts.lookupsError}</Alert>}
      {events.isSuccess && (
        <Text size="sm" c="dimmed">
          {texts.shown(shown.length, allEvents.length)}
        </Text>
      )}
      {content}
      <EventDetailsDrawer
        event={editing ? null : selected}
        lookups={lookups}
        onClose={() => setSelectedId(null)}
        onEditAssignments={setEditing}
      />
      {editing && (
        <AssignmentsModal
          key={editing.id}
          event={editing}
          lookups={lookups}
          onClose={() => setEditing(null)}
        />
      )}
      {newEvent && (
        <EventFormModal
          key={`${newEvent.date}-${newEvent.time ?? ''}`}
          lookups={lookups}
          initialDate={newEvent.date}
          initialTime={newEvent.time}
          onClose={() => setNewEvent(null)}
          onSaved={(date) => {
            setNewEvent(null);
            setAnchor(date);
          }}
        />
      )}
    </Stack>
  );
}
