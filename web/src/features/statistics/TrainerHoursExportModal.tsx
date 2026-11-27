import { useState } from 'react';
import { Alert, Button, Group, Modal, Select, Stack, Text, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { getStudio, scheduleKeys, type StaffMember } from '@/features/schedule/scheduleApi';
import { todayIn } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import { getTrainerHours } from './trainerHoursApi';
import { downloadHoursCsv, monthlyPeriod } from './trainerHoursExport';

const texts = pl.statistics.hours;
const exp = texts.export;

export function TrainerHoursExportModal({
  initialMonth,
  initialStaffId,
  staff,
  onClose,
}: {
  initialMonth: string;
  initialStaffId: string | null;
  staff: StaffMember[];
  onClose: () => void;
}) {
  const studio = useQuery({ queryKey: scheduleKeys.studio, queryFn: getStudio });
  const maxMonth = studio.data ? todayIn(studio.data.timeZone).slice(0, 7) : '';
  const [month, setMonth] = useState(initialMonth);
  const [staffId, setStaffId] = useState(initialStaffId);
  const [busy, setBusy] = useState<'pdf' | 'csv' | 'print' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const period = monthlyPeriod(month);
  const valid = !!period && !!maxMonth && month <= maxMonth;

  async function generate(action: 'pdf' | 'csv' | 'print') {
    if (!period || !valid || !studio.data || busy) return;
    // Capture the user gesture before fetching; opening a window after awaiting is blocked.
    const printWindow = action === 'print' ? window.open('', '_blank') : null;
    if (action === 'print' && !printWindow) {
      setError(exp.popupBlocked);
      return;
    }
    setBusy(action);
    setError(null);
    try {
      const report = await getTrainerHours({
        ...period,
        kind: null,
        staffId,
        confirmedOnly: false,
      });
      if (staffId && !report.instructors.some((row) => row.id === staffId))
        throw Error('Missing instructor');
      const data = {
        report,
        studioName: studio.data.name,
        generatedAt: new Date().toISOString(),
        staffId,
      };
      if (action === 'csv') downloadHoursCsv(data);
      else {
        const { exportHoursPdf } = await import('./trainerHoursPdf');
        await exportHoursPdf(data, printWindow ?? undefined);
      }
    } catch {
      printWindow?.close();
      setError(exp.error);
    } finally {
      setBusy(null);
    }
  }

  return (
    <Modal
      opened
      onClose={onClose}
      title={exp.title}
      centered
      closeOnClickOutside={!busy}
      closeOnEscape={!busy}
      withCloseButton={!busy}
    >
      <Stack gap="md">
        <TextInput
          type="month"
          label={exp.month}
          value={month}
          max={maxMonth}
          min="1000-01"
          onChange={(event) => setMonth(event.currentTarget.value)}
          error={maxMonth && !valid ? exp.invalidMonth : undefined}
          disabled={!!busy}
        />
        <Select
          label={texts.instructor}
          placeholder={texts.allInstructors}
          data={staff.map((row) => ({ value: row.id, label: row.displayName }))}
          value={staffId}
          onChange={setStaffId}
          clearable
          searchable
          disabled={!!busy}
          nothingFoundMessage={texts.noResults}
        />
        <Text size="sm" c="dimmed">
          {exp.scope}
        </Text>
        {(error || studio.isError) && (
          <Alert color="red" role="alert">
            {error ?? exp.error}
          </Alert>
        )}
        <Group gap="xs">
          <Button
            onClick={() => void generate('pdf')}
            loading={busy === 'pdf'}
            disabled={!valid || (!!busy && busy !== 'pdf')}
          >
            {exp.pdf}
          </Button>
          <Button
            variant="light"
            onClick={() => void generate('csv')}
            loading={busy === 'csv'}
            disabled={!valid || (!!busy && busy !== 'csv')}
          >
            {exp.csv}
          </Button>
          <Button
            variant="default"
            onClick={() => void generate('print')}
            loading={busy === 'print'}
            disabled={!valid || (!!busy && busy !== 'print')}
          >
            {exp.print}
          </Button>
        </Group>
        <Button variant="subtle" color="gray" onClick={onClose} disabled={!!busy}>
          {exp.cancel}
        </Button>
      </Stack>
    </Modal>
  );
}
