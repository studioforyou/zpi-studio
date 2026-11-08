import { useState } from 'react';
import { Alert, Button, Group, Modal, Radio, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  attendanceKeys,
  correctAttendance,
  type AttendanceCorrection,
  type AttendanceRecord,
  type RecordedAttendance,
} from './attendanceApi';

type Props = {
  eventId: string;
  /** The participant as currently loaded; it is replaced by fresh data after a conflict. */
  record: AttendanceRecord;
  onClose: () => void;
};

function correctionError(error: unknown): string {
  const messages = pl.schedule.attendance.correction.errors;
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'VERSION_CONFLICT':
        return messages.conflict;
      case 'ATTENDANCE_NOT_OPEN':
        return messages.notOpen;
      case 'EVENT_CANCELLED':
        return messages.cancelled;
      case 'RESERVATION_NOT_ACTIVE':
        return messages.notActive;
      case 'VALIDATION_FAILED':
        return messages.validation;
    }
  }
  return messages.unknown;
}

/** An administrator's correction of one participant's attendance, kept in its history. */
export function CorrectAttendanceModal({ eventId, record, onClose }: Props) {
  const queryClient = useQueryClient();
  const texts = pl.schedule.attendance;
  const [attendance, setAttendance] = useState<RecordedAttendance | null>(null);
  const [reason, setReason] = useState('');

  const correction = useMutation({
    mutationFn: (request: AttendanceCorrection) =>
      correctAttendance(eventId, record.reservationId, request),
    retry: false,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: attendanceKeys.event(eventId) });
      onClose();
    },
    // The loaded attendance may be out of date; a fresh one replaces `record`.
    onError: () => queryClient.invalidateQueries({ queryKey: attendanceKeys.event(eventId) }),
  });

  const unchanged = attendance !== null && attendance === record.attendance;
  const pending = correction.isPending;

  function submit() {
    if (pending || attendance === null || unchanged) return;
    correction.mutate({ attendance, reason: reason.trim() || null, version: record.version });
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={texts.correction.title}
      size="md"
      closeOnEscape={!pending}
      closeOnClickOutside={!pending}
      withCloseButton={!pending}
    >
      <Stack>
        <Stack gap={2}>
          <Text fw={600}>{record.displayName}</Text>
          <Text size="sm" c="dimmed">
            {texts.correction.current(texts.statuses[record.attendance])}
          </Text>
        </Stack>
        <Radio.Group
          label={texts.correction.attendance}
          value={attendance}
          onChange={(value) => setAttendance(value as RecordedAttendance)}
          error={unchanged ? texts.correction.unchanged : undefined}
        >
          <Group mt="xs">
            <Radio value="PRESENT" label={texts.statuses.PRESENT} disabled={pending} />
            <Radio value="ABSENT" label={texts.statuses.ABSENT} disabled={pending} />
          </Group>
        </Radio.Group>
        <Textarea
          label={texts.correction.reason}
          placeholder={texts.correction.reasonPlaceholder}
          maxLength={500}
          rows={2}
          value={reason}
          onChange={(event) => setReason(event.currentTarget.value)}
          disabled={pending}
        />
        <Text size="sm" c="dimmed">
          {texts.correction.passNote}
        </Text>
        <Text size="sm" c="dimmed">
          {texts.correction.historyNote}
        </Text>
        {correction.isError && <Alert color="red">{correctionError(correction.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={pending}>
            {texts.correction.cancel}
          </Button>
          <Button onClick={submit} loading={pending} disabled={attendance === null || unchanged}>
            {texts.correction.save}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
