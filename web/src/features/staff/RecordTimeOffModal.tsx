import { useState, type FormEvent } from 'react';
import {
  Alert,
  Button,
  Group,
  Modal,
  SegmentedControl,
  Select,
  Stack,
  Text,
  Textarea,
  TextInput,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import { addDays, toInstant, todayIn } from '@/features/schedule/studioTime';
import { recordTimeOff, timeOffKeys, timeOffKinds, type TimeOffKind } from './timeOffApi';
import { timeOffErrorMessage } from './timeOffFormat';
import { formatDateTime, eventTitle } from '@/features/schedule/scheduleFormat';
import { useTrainerEventsDuring } from './trainerEvents';

type RecordTimeOffModalProps = {
  staffOptions: { value: string; label: string }[];
  timeZone: string;
  onClose: () => void;
};

/** Records approved time off for a trainer, e.g. sick leave reported by phone. */
export function RecordTimeOffModal({ staffOptions, timeZone, onClose }: RecordTimeOffModalProps) {
  const texts = pl.timeOff.form;
  const queryClient = useQueryClient();
  const today = todayIn(timeZone);
  const [staffId, setStaffId] = useState<string | null>(null);
  const [kind, setKind] = useState<TimeOffKind>('SICK_LEAVE');
  const [wholeDays, setWholeDays] = useState(true);
  const [dateFrom, setDateFrom] = useState(today);
  const [dateTo, setDateTo] = useState(today);
  const [timeFrom, setTimeFrom] = useState('08:00');
  const [timeTo, setTimeTo] = useState('16:00');
  const [reason, setReason] = useState('');
  const [submitted, setSubmitted] = useState(false);

  // Whole days run from the first day's midnight to the midnight after the last day.
  const period =
    wholeDays && dateFrom && dateTo
      ? {
          startsAt: toInstant(dateFrom, '00:00', timeZone),
          endsAt: toInstant(addDays(dateTo, 1), '00:00', timeZone),
        }
      : !wholeDays && dateFrom && timeFrom && timeTo
        ? {
            startsAt: toInstant(dateFrom, timeFrom, timeZone),
            endsAt: toInstant(dateFrom, timeTo, timeZone),
          }
        : null;
  const periodInvalid = period !== null && period.endsAt.getTime() <= period.startsAt.getTime();
  const checkEvents = staffId !== null && period !== null && !periodInvalid;
  const { events, own } = useTrainerEventsDuring(
    {
      staffId: staffId ?? '',
      startsAt: checkEvents ? period.startsAt.toISOString() : new Date(0).toISOString(),
      endsAt: checkEvents ? period.endsAt.toISOString() : new Date(1).toISOString(),
    },
    checkEvents,
  );

  const save = useMutation({
    mutationFn: () =>
      recordTimeOff({
        staffId: staffId!,
        kind,
        startsAt: period!.startsAt.toISOString(),
        endsAt: period!.endsAt.toISOString(),
        reason: reason.trim() === '' ? null : reason.trim(),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onClose();
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (!staffId || period === null || periodInvalid) {
      return;
    }
    save.mutate();
  }

  const required = (value: string | null) => (submitted && !value ? texts.required : undefined);

  return (
    <Modal opened onClose={onClose} title={texts.title} size="lg">
      <form onSubmit={handleSubmit} noValidate>
        <Stack>
          <Select
            label={texts.staff}
            placeholder={texts.staffPlaceholder}
            required
            searchable
            data={staffOptions}
            value={staffId}
            onChange={setStaffId}
            error={required(staffId)}
          />
          <Stack gap={4}>
            <Text size="sm" fw={500}>
              {texts.kind}
            </Text>
            <SegmentedControl
              fullWidth
              aria-label={texts.kind}
              data={timeOffKinds.map((value) => ({ value, label: pl.timeOff.kinds[value] }))}
              value={kind}
              onChange={(value) => setKind(value as TimeOffKind)}
            />
          </Stack>
          <SegmentedControl
            fullWidth
            aria-label={texts.periodType}
            data={[
              { value: 'days', label: texts.wholeDays },
              { value: 'hours', label: texts.partOfDay },
            ]}
            value={wholeDays ? 'days' : 'hours'}
            onChange={(value) => setWholeDays(value === 'days')}
          />
          {wholeDays ? (
            <Group grow align="flex-start">
              <TextInput
                type="date"
                label={texts.dateFrom}
                required
                value={dateFrom}
                onChange={(event) => setDateFrom(event.currentTarget.value)}
                error={required(dateFrom)}
              />
              <TextInput
                type="date"
                label={texts.dateTo}
                required
                value={dateTo}
                onChange={(event) => setDateTo(event.currentTarget.value)}
                error={required(dateTo) ?? (periodInvalid ? texts.endBeforeStart : undefined)}
              />
            </Group>
          ) : (
            <Group grow align="flex-start">
              <TextInput
                type="date"
                label={texts.date}
                required
                value={dateFrom}
                onChange={(event) => setDateFrom(event.currentTarget.value)}
                error={required(dateFrom)}
              />
              <TextInput
                type="time"
                label={texts.timeFrom}
                required
                value={timeFrom}
                onChange={(event) => setTimeFrom(event.currentTarget.value)}
                error={required(timeFrom)}
              />
              <TextInput
                type="time"
                label={texts.timeTo}
                required
                value={timeTo}
                onChange={(event) => setTimeTo(event.currentTarget.value)}
                error={required(timeTo) ?? (periodInvalid ? texts.endBeforeStart : undefined)}
              />
            </Group>
          )}
          <Textarea
            label={texts.reason}
            placeholder={texts.reasonPlaceholder}
            maxLength={500}
            rows={2}
            value={reason}
            onChange={(event) => setReason(event.currentTarget.value)}
          />
          {checkEvents && events.isSuccess && own.length > 0 && (
            <Alert color="yellow" variant="light" title={texts.eventsWarning(own.length)}>
              <Stack gap={2}>
                {own.map((event) => (
                  <Text size="sm" key={event.id}>
                    {formatDateTime(event.startsAt, timeZone)} · {eventTitle(event)}
                  </Text>
                ))}
              </Stack>
            </Alert>
          )}
          <Alert color="blue" variant="light">
            {texts.hint}
          </Alert>
          {save.error && <Alert color="red">{timeOffErrorMessage(save.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {texts.cancel}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {texts.save}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
