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
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { pl } from '@/shared/i18n/pl';
import { scheduleKeys } from '@/features/schedule/scheduleApi';
import { addDays, toInstant, todayIn } from '@/features/schedule/studioTime';
import {
  chosenTimeOffEvents,
  recordTimeOff,
  previewTimeOffPlan,
  timeOffCoverKeys,
  timeOffKeys,
  timeOffKinds,
  type TimeOff,
  type TimeOffKind,
} from './timeOffApi';
import { timeOffErrorMessage } from './timeOffFormat';
import { TimeOffSessionPicker } from './TimeOffCoverage';

type RecordTimeOffModalProps = {
  staffOptions: { value: string; label: string }[];
  timeZone: string;
  onClose: () => void;
  onRecorded: (entry: TimeOff) => void;
};

/** Records approved time off for a trainer, e.g. sick leave reported by phone. */
export function RecordTimeOffModal({
  staffOptions,
  timeZone,
  onClose,
  onRecorded,
}: RecordTimeOffModalProps) {
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
  const [choices, setChoices] = useState<Record<string, boolean>>({});

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
  const from = checkEvents ? period.startsAt.toISOString() : '';
  const to = checkEvents ? period.endsAt.toISOString() : '';
  const plan = useQuery({
    queryKey: timeOffCoverKeys.preview(staffId ?? '', from, to),
    queryFn: () => previewTimeOffPlan(staffId!, from, to),
    enabled: checkEvents,
  });

  const save = useMutation({
    mutationFn: () =>
      recordTimeOff({
        staffId: staffId!,
        kind,
        startsAt: period!.startsAt.toISOString(),
        endsAt: period!.endsAt.toISOString(),
        reason: reason.trim() === '' ? null : reason.trim(),
        substitutionEventIds: chosenTimeOffEvents(plan.data, choices),
      }),
    onSuccess: async (entry) => {
      await queryClient.invalidateQueries({ queryKey: timeOffKeys.all });
      await queryClient.invalidateQueries({ queryKey: scheduleKeys.all });
      onRecorded(entry);
    },
  });

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (!staffId || period === null || periodInvalid || !plan.isSuccess) {
      return;
    }
    save.mutate();
  }

  const required = (value: string | null) => (submitted && !value ? texts.required : undefined);

  return (
    <Modal
      opened
      onClose={onClose}
      title={texts.title}
      size="lg"
      closeOnEscape={!save.isPending}
      closeOnClickOutside={!save.isPending}
      withCloseButton={!save.isPending}
    >
      <form onSubmit={handleSubmit} noValidate>
        <Stack>
          <Select
            label={texts.staff}
            placeholder={texts.staffPlaceholder}
            required
            searchable
            data={staffOptions}
            value={staffId}
            onChange={(value) => {
              setStaffId(value);
              setChoices({});
            }}
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
          {checkEvents &&
            (plan.isPending ? (
              <Text size="sm">{pl.timeOff.decision.eventsLoading}</Text>
            ) : plan.isError ? (
              <Alert color="red">
                {pl.timeOffCover.error}
                <Button variant="subtle" onClick={() => void plan.refetch()}>
                  {pl.timeOffCover.retry}
                </Button>
              </Alert>
            ) : (
              <TimeOffSessionPicker
                plan={plan.data}
                choices={choices}
                onChange={setChoices}
                zone={timeZone}
                disabled={save.isPending}
              />
            ))}
          {save.error && <Alert color="red">{timeOffErrorMessage(save.error)}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={save.isPending}>
              {texts.cancel}
            </Button>
            <Button
              type="submit"
              loading={save.isPending}
              disabled={checkEvents && !plan.isSuccess}
            >
              {texts.save}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
