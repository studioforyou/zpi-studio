import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/time_off_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The red of time off in the availability calendar.
const timeOffColor = Color(0xFFDE4036);

/// The darker red of a request's dashed outline.
const timeOffOutlineColor = Color(0xFFB71C1C);

/// The schedule endpoint accepts windows of at most 93 days.
const _maxEventsWindow = Duration(days: 93);

/// A period can last at most 366 days.
const _maxLength = Duration(days: 366);

String timeOffKindLabel(
  AppLocalizations l10n,
  TimeOffKind kind, {
  bool short = false,
}) => switch (kind) {
  TimeOffKind.vacation => l10n.timeOffKindVacation,
  TimeOffKind.sickLeave => l10n.timeOffKindSickLeave,
  TimeOffKind.unavailable =>
    short ? l10n.timeOffKindUnavailableShort : l10n.timeOffKindUnavailable,
};

String timeOffStatusLabel(AppLocalizations l10n, TimeOffStatus status) =>
    switch (status) {
      TimeOffStatus.requested => l10n.timeOffStatusRequested,
      TimeOffStatus.approved => l10n.timeOffStatusApproved,
      TimeOffStatus.rejected => l10n.timeOffStatusRejected,
      TimeOffStatus.cancelled => l10n.timeOffStatusCancelled,
    };

final _weekdayDate = DateFormat('EEE d.MM', 'pl');
final _time = DateFormat('H:mm');
final _dateTime = DateFormat('d.MM, H:mm', 'pl');
final _pickerDate = DateFormat('EEE d.MM', 'pl');

/// The period as the trainer knows it: „cz 19.11 – pt 20.11 (2 dni)”,
/// „wt 17.11, 17:00–19:00” or „pt 3.10”.
String formatTimeOffPeriod(
  AppLocalizations l10n,
  DateTime startsAt,
  DateTime endsAt,
) {
  final wholeDays =
      startsAt.hour == 0 &&
      startsAt.minute == 0 &&
      endsAt.hour == 0 &&
      endsAt.minute == 0;
  if (wholeDays) {
    final first = DateUtils.dateOnly(startsAt);
    final last = _addDays(DateUtils.dateOnly(endsAt), -1);
    final count = _daysBetween(first, last) + 1;
    return count <= 1
        ? l10n.timeOffWholeDay(_weekdayDate.format(first))
        : l10n.timeOffDays(
            _weekdayDate.format(first),
            _weekdayDate.format(last),
            count,
          );
  }
  if (DateUtils.isSameDay(startsAt, endsAt)) {
    return l10n.timeOffSameDay(
      _weekdayDate.format(startsAt),
      _time.format(startsAt),
      _time.format(endsAt),
    );
  }
  return l10n.timeOffRange(
    '${_weekdayDate.format(startsAt)} ${_time.format(startsAt)}',
    '${_weekdayDate.format(endsAt)} ${_time.format(endsAt)}',
  );
}

String timeOffErrorMessage(Object error, AppLocalizations l10n) {
  if (error is TimeOffApiException) {
    switch (error.code) {
      case 'TIME_OFF_OVERLAP':
        return l10n.timeOffErrorOverlap;
      case 'TIME_OFF_ALREADY_DECIDED' || 'TIME_OFF_NOT_CANCELLABLE':
        return l10n.timeOffErrorDecided;
    }
  }
  if (error is ApiException) {
    return switch (error.statusCode) {
      null => l10n.timeOffErrorNetwork,
      400 => l10n.timeOffErrorInvalid,
      409 => l10n.timeOffErrorOverlap,
      _ => l10n.timeOffErrorGeneric,
    };
  }
  return l10n.timeOffErrorGeneric;
}

/// A small colored label with the status of a request.
class TimeOffStatusChip extends StatelessWidget {
  const TimeOffStatusChip({
    super.key,
    required this.status,
    this.short = false,
  });

  final TimeOffStatus status;
  final bool short;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (background, foreground) = switch (status) {
      TimeOffStatus.requested => (
        const Color(0xFFFFF1DD),
        const Color(0xFF7A4300),
      ),
      TimeOffStatus.approved => (
        const Color(0xFFDFF0DC),
        const Color(0xFF1F5B1C),
      ),
      TimeOffStatus.rejected => (
        const Color(0xFFFFDAD6),
        const Color(0xFF93000A),
      ),
      TimeOffStatus.cancelled => (
        const Color(0xFFE4E1EC),
        const Color(0xFF45464F),
      ),
    };
    return DecoratedBox(
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 3),
        child: Text(
          short && status == TimeOffStatus.requested
              ? l10n.timeOffStatusRequestedShort
              : timeOffStatusLabel(l10n, status),
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: foreground,
            fontWeight: FontWeight.w500,
          ),
        ),
      ),
    );
  }
}

/// Fills a time-off block: red for approved time off, red stripes with a
/// dashed outline for a request that waits for a decision.
class TimeOffPainter extends CustomPainter {
  const TimeOffPainter({required this.requested, this.selected = false});

  final bool requested;
  final bool selected;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = RRect.fromRectAndRadius(
      Offset.zero & size,
      const Radius.circular(10),
    );
    canvas.save();
    canvas.clipRRect(rect);
    if (requested) {
      canvas.drawRect(
        Offset.zero & size,
        Paint()..color = timeOffColor.withValues(alpha: 0.16),
      );
      final stripe = Paint()
        ..color = timeOffColor.withValues(alpha: 0.18)
        ..strokeWidth = 6;
      for (var y = -size.width; y < size.height + size.width; y += 17) {
        canvas.drawLine(
          Offset(0, y + size.width),
          Offset(size.width, y),
          stripe,
        );
      }
    } else {
      canvas.drawRect(
        Offset.zero & size,
        Paint()..color = timeOffColor.withValues(alpha: 0.42),
      );
    }
    canvas.restore();
    if (requested) {
      _dashed(canvas, rect.deflate(0.75));
    }
    if (selected) {
      canvas.drawRRect(
        rect.deflate(1),
        Paint()
          ..color = timeOffOutlineColor
          ..style = PaintingStyle.stroke
          ..strokeWidth = 2,
      );
    }
  }

  void _dashed(Canvas canvas, RRect rect) {
    final paint = Paint()
      ..color = timeOffOutlineColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    for (final metric in (Path()..addRRect(rect)).computeMetrics()) {
      for (var d = 0.0; d < metric.length; d += 7) {
        canvas.drawPath(
          metric.extractPath(d, math.min(d + 4, metric.length)),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(TimeOffPainter oldDelegate) =>
      oldDelegate.requested != requested || oldDelegate.selected != selected;
}

/// The legend swatch of approved time off or a request.
class TimeOffSwatch extends StatelessWidget {
  const TimeOffSwatch({super.key, required this.requested});

  final bool requested;

  @override
  Widget build(BuildContext context) => CustomPaint(
    painter: TimeOffPainter(requested: requested),
    child: const SizedBox(width: 14, height: 14),
  );
}

/// What the time-off sheet works on: a new request (starting on [day]) or a
/// change of a waiting one.
sealed class TimeOffEditorTarget {
  const TimeOffEditorTarget();
}

class NewTimeOff extends TimeOffEditorTarget {
  const NewTimeOff({required this.day});

  final DateTime day;
}

class EditTimeOff extends TimeOffEditorTarget {
  const EditTimeOff(this.timeOff);

  final TimeOff timeOff;
}

/// Requests time off or changes a waiting request. Shows the events the
/// trainer runs in that period. Pops with the confirmation to show.
class TimeOffEditorSheet extends ConsumerStatefulWidget {
  const TimeOffEditorSheet({
    super.key,
    required this.accountId,
    required this.target,
    required this.now,
  });

  final String accountId;
  final TimeOffEditorTarget target;

  /// The current moment; requests that already ended are refused.
  final DateTime now;

  @override
  ConsumerState<TimeOffEditorSheet> createState() => _TimeOffEditorSheetState();
}

class _TimeOffEditorSheetState extends ConsumerState<TimeOffEditorSheet> {
  late TimeOffKind _kind;
  late bool _wholeDays;
  late DateTime _from;
  late DateTime _to;
  late TimeOfDay _start;
  late TimeOfDay _end;
  late final TextEditingController _reason;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    switch (widget.target) {
      case EditTimeOff(:final timeOff):
        _kind = timeOff.kind;
        _wholeDays = timeOff.isWholeDays;
        _from = DateUtils.dateOnly(timeOff.startsAt);
        _to = timeOff.isWholeDays
            ? _addDays(DateUtils.dateOnly(timeOff.endsAt), -1)
            : DateUtils.dateOnly(timeOff.startsAt);
        _start = TimeOfDay.fromDateTime(timeOff.startsAt);
        _end = TimeOfDay.fromDateTime(timeOff.endsAt);
        _reason = TextEditingController(text: timeOff.reason ?? '');
      case NewTimeOff(:final day):
        _kind = TimeOffKind.vacation;
        _wholeDays = true;
        _from = day;
        _to = day;
        _start = const TimeOfDay(hour: 8, minute: 0);
        _end = const TimeOfDay(hour: 12, minute: 0);
        _reason = TextEditingController();
    }
  }

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  bool get _editing => widget.target is EditTimeOff;

  ({DateTime startsAt, DateTime endsAt}) get _period => _wholeDays
      ? (startsAt: _from, endsAt: _addDays(_to, 1))
      : (
          startsAt: DateTime(
            _from.year,
            _from.month,
            _from.day,
            _start.hour,
            _start.minute,
          ),
          endsAt: DateTime(
            _from.year,
            _from.month,
            _from.day,
            _end.hour,
            _end.minute,
          ),
        );

  String? _validate(AppLocalizations l10n) {
    final period = _period;
    if (!period.endsAt.isAfter(period.startsAt)) {
      return l10n.timeOffFormEndBeforeStart;
    }
    if (!period.endsAt.isAfter(widget.now)) {
      return l10n.timeOffFormInPast;
    }
    if (period.endsAt.difference(period.startsAt) > _maxLength) {
      return l10n.timeOffFormTooLong;
    }
    return null;
  }

  Future<void> _save(AppLocalizations l10n) async {
    final invalid = _validate(l10n);
    if (invalid != null) {
      setState(() => _error = invalid);
      return;
    }
    final period = _period;
    setState(() {
      _busy = true;
      _error = null;
    });
    final api = ref.read(timeOffApiProvider);
    try {
      switch (widget.target) {
        case EditTimeOff(:final timeOff):
          await api.update(
            timeOff.id,
            kind: _kind,
            startsAt: period.startsAt,
            endsAt: period.endsAt,
            reason: _reason.text,
          );
        case NewTimeOff():
          await api.request(
            kind: _kind,
            startsAt: period.startsAt,
            endsAt: period.endsAt,
            reason: _reason.text,
          );
      }
      ref.invalidate(ownTimeOffProvider(widget.accountId));
      if (mounted) {
        Navigator.of(
          context,
        ).pop(_editing ? l10n.timeOffChanged : l10n.timeOffSent);
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = timeOffErrorMessage(e, l10n);
        });
      }
    }
  }

  Future<void> _pickDate({required bool from}) async {
    final today = DateUtils.dateOnly(widget.now);
    final current = from ? _from : _to;
    final first = from || !_wholeDays ? today : _from;
    final picked = await showDatePicker(
      context: context,
      firstDate: first,
      lastDate: _addDays(today, 730),
      initialDate: current.isBefore(first) ? first : current,
    );
    if (picked != null && mounted) {
      setState(() {
        if (from) {
          _from = picked;
          if (_to.isBefore(picked)) {
            _to = picked;
          }
        } else {
          _to = picked;
        }
        _error = null;
      });
    }
  }

  Future<void> _pickTime({required bool start}) async {
    final picked = await showTimePicker(
      context: context,
      initialTime: start ? _start : _end,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true),
        child: child!,
      ),
    );
    if (picked != null && mounted) {
      setState(() {
        if (start) {
          _start = picked;
        } else {
          _end = picked;
        }
        _error = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final period = _period;
    final valid = _validate(l10n) == null;
    final eventsEnd =
        period.endsAt.difference(period.startsAt) > _maxEventsWindow
        ? period.startsAt.add(_maxEventsWindow)
        : period.endsAt;
    final events = valid
        ? ref.watch(
            ownScheduleProvider((
              accountId: widget.accountId,
              window: (from: period.startsAt, to: eventsEnd),
            )),
          )
        : null;
    final ownEvents = [
      for (final event in events?.asData?.value ?? const <ScheduleEvent>[])
        if (event.status == EventStatus.scheduled &&
            event.kind != EventKind.block)
          event,
    ]..sort((a, b) => a.startsAt.compareTo(b.startsAt));

    return Padding(
      padding: EdgeInsets.fromLTRB(
        24,
        0,
        24,
        24 + MediaQuery.viewInsetsOf(context).bottom,
      ),
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 14,
          children: [
            Text(l10n.timeOffFormTitle, style: theme.textTheme.headlineSmall),
            SegmentedButton<TimeOffKind>(
              key: const Key('time-off-kind'),
              segments: [
                for (final kind in TimeOffKind.values)
                  ButtonSegment(
                    value: kind,
                    label: Text(
                      timeOffKindLabel(l10n, kind, short: true),
                      maxLines: 1,
                      softWrap: false,
                      overflow: TextOverflow.fade,
                    ),
                  ),
              ],
              selected: {_kind},
              onSelectionChanged: _busy
                  ? null
                  : (selection) => setState(() => _kind = selection.single),
            ),
            SegmentedButton<bool>(
              key: const Key('time-off-whole-days'),
              segments: [
                ButtonSegment(
                  value: true,
                  label: Text(l10n.timeOffFormWholeDays),
                ),
                ButtonSegment(
                  value: false,
                  label: Text(l10n.timeOffFormPartOfDay),
                ),
              ],
              selected: {_wholeDays},
              onSelectionChanged: _busy
                  ? null
                  : (selection) => setState(() {
                      _wholeDays = selection.single;
                      _error = null;
                    }),
            ),
            if (_wholeDays)
              Row(
                spacing: 12,
                children: [
                  Expanded(
                    child: TimeOffPickerField(
                      key: const Key('time-off-from'),
                      label: l10n.timeOffFormFrom,
                      value: _pickerDate.format(_from),
                      icon: Icons.event,
                      onTap: _busy ? null : () => _pickDate(from: true),
                    ),
                  ),
                  Expanded(
                    child: TimeOffPickerField(
                      key: const Key('time-off-to'),
                      label: l10n.timeOffFormTo,
                      value: _pickerDate.format(_to),
                      icon: Icons.event,
                      onTap: _busy ? null : () => _pickDate(from: false),
                    ),
                  ),
                ],
              )
            else ...[
              TimeOffPickerField(
                key: const Key('time-off-day'),
                label: l10n.timeOffFormDay,
                value: _pickerDate.format(_from),
                icon: Icons.event,
                onTap: _busy ? null : () => _pickDate(from: true),
              ),
              Row(
                spacing: 12,
                children: [
                  Expanded(
                    child: TimeOffPickerField(
                      key: const Key('time-off-start'),
                      label: l10n.timeOffFormFrom,
                      value: _time.format(period.startsAt),
                      icon: Icons.schedule,
                      onTap: _busy ? null : () => _pickTime(start: true),
                    ),
                  ),
                  Expanded(
                    child: TimeOffPickerField(
                      key: const Key('time-off-end'),
                      label: l10n.timeOffFormTo,
                      value: _time.format(period.endsAt),
                      icon: Icons.schedule,
                      onTap: _busy ? null : () => _pickTime(start: false),
                    ),
                  ),
                ],
              ),
            ],
            TextField(
              key: const Key('time-off-reason'),
              controller: _reason,
              enabled: !_busy,
              maxLength: 500,
              decoration: InputDecoration(
                labelText: l10n.timeOffFormReason,
                border: const OutlineInputBorder(),
                counterText: '',
              ),
            ),
            if (events != null && events.hasValue)
              ownEvents.isEmpty
                  ? _Notice(
                      key: const Key('time-off-no-events'),
                      icon: Icons.check_circle,
                      background: const Color(0xFFE8F2E6),
                      foreground: const Color(0xFF1F4B1D),
                      iconColor: const Color(0xFF2E6B2B),
                      title: l10n.timeOffFormNoEvents,
                    )
                  : _Notice(
                      key: const Key('time-off-events'),
                      icon: Icons.warning_amber,
                      background: const Color(0xFFFFF1DD),
                      foreground: const Color(0xFF4D2A00),
                      iconColor: const Color(0xFF9A5A00),
                      title: l10n.timeOffFormEvents(ownEvents.length),
                      lines: [
                        for (final event in ownEvents)
                          '${_capitalize(_weekdayDate.format(event.startsAt))}, '
                              '${_time.format(event.startsAt)} · '
                              '${event.name ?? ''}',
                      ],
                      footer: l10n.timeOffFormEventsHint,
                    ),
            if (_error case final error?)
              Text(error, style: TextStyle(color: theme.colorScheme.error)),
            FilledButton(
              onPressed: _busy ? null : () => _save(l10n),
              child: _busy
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: theme.colorScheme.onPrimary,
                      ),
                    )
                  : Text(
                      _editing
                          ? l10n.timeOffFormSaveChanges
                          : l10n.timeOffFormSubmit,
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

class _Notice extends StatelessWidget {
  const _Notice({
    super.key,
    required this.icon,
    required this.background,
    required this.foreground,
    required this.iconColor,
    required this.title,
    this.lines = const [],
    this.footer,
  });

  final IconData icon;
  final Color background;
  final Color foreground;
  final Color iconColor;
  final String title;
  final List<String> lines;
  final String? footer;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.textTheme.bodyMedium?.copyWith(color: foreground);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 4,
          children: [
            Row(
              spacing: 8,
              children: [
                Icon(icon, color: iconColor, size: 20),
                Expanded(
                  child: Text(
                    title,
                    style: style?.copyWith(fontWeight: FontWeight.w500),
                  ),
                ),
              ],
            ),
            for (final line in lines)
              Padding(
                padding: const EdgeInsets.only(left: 28),
                child: Text('• $line', style: style),
              ),
            if (footer case final footer?)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(footer, style: style),
              ),
          ],
        ),
      ),
    );
  }
}

/// A read-only field that opens a picker when tapped.
class TimeOffPickerField extends StatelessWidget {
  const TimeOffPickerField({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    required this.onTap,
  });

  final String label;
  final String value;
  final IconData icon;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(4),
      child: InputDecorator(
        decoration: InputDecoration(
          labelText: label,
          prefixIcon: Icon(icon),
          border: const OutlineInputBorder(),
          enabled: onTap != null,
        ),
        child: Text(value, maxLines: 1, overflow: TextOverflow.ellipsis),
      ),
    );
  }
}

/// What the trainer chose in the details sheet.
enum TimeOffDetailsAction { edit, cancel }

/// Details of a tapped time-off block with what can still be done with it:
/// a request can be changed or withdrawn, approved time off only cancelled.
class TimeOffDetailsSheet extends StatelessWidget {
  const TimeOffDetailsSheet({
    super.key,
    required this.timeOff,
    required this.now,
  });

  final TimeOff timeOff;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    Widget row(String label, String value) => Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        SizedBox(
          width: 120,
          child: Text(
            label,
            style: theme.textTheme.bodyLarge?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ),
        Expanded(child: Text(value, style: theme.textTheme.bodyLarge)),
      ],
    );
    final decidedBy = timeOff.decidedAt == null
        ? null
        : l10n.timeOffDetailsDecided(
            timeOff.decidedByName ?? l10n.timeOffDetailsStudio,
            _dateTime.format(timeOff.decidedAt!),
          );
    final requested = timeOff.status == TimeOffStatus.requested;
    final cancellable = timeOff.isCancellable(now);
    final redOutline = OutlinedButton.styleFrom(
      foregroundColor: scheme.error,
      side: BorderSide(color: scheme.error),
    );

    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        spacing: 10,
        children: [
          Wrap(
            spacing: 10,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                timeOffKindLabel(l10n, timeOff.kind),
                style: theme.textTheme.titleLarge,
              ),
              TimeOffStatusChip(status: timeOff.status),
            ],
          ),
          row(
            l10n.timeOffDetailsWhen,
            formatTimeOffPeriod(l10n, timeOff.startsAt, timeOff.endsAt),
          ),
          if (timeOff.reason case final reason?)
            row(l10n.timeOffDetailsReason, reason),
          if (timeOff.decisionNote case final note?)
            row(l10n.timeOffDetailsNote, note),
          if (decidedBy != null)
            row(
              timeOff.status == TimeOffStatus.rejected
                  ? l10n.timeOffDetailsRejectedBy
                  : l10n.timeOffDetailsApprovedBy,
              decidedBy,
            ),
          if (requested)
            row(
              l10n.timeOffDetailsRequested,
              _dateTime.format(timeOff.requestedAt),
            ),
          const SizedBox(height: 4),
          if (requested && cancellable)
            Row(
              spacing: 12,
              children: [
                Expanded(
                  child: OutlinedButton(
                    style: redOutline,
                    onPressed: () =>
                        Navigator.of(context).pop(TimeOffDetailsAction.cancel),
                    child: Text(l10n.timeOffWithdraw),
                  ),
                ),
                Expanded(
                  child: FilledButton(
                    onPressed: () =>
                        Navigator.of(context).pop(TimeOffDetailsAction.edit),
                    child: Text(l10n.timeOffEdit),
                  ),
                ),
              ],
            )
          else if (cancellable) ...[
            OutlinedButton(
              style: redOutline,
              onPressed: () =>
                  Navigator.of(context).pop(TimeOffDetailsAction.cancel),
              child: Text(l10n.timeOffCancel),
            ),
            Text(
              l10n.timeOffApprovedHint,
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// Asks before withdrawing a request or cancelling approved time off.
class TimeOffCancelDialog extends StatelessWidget {
  const TimeOffCancelDialog({super.key, required this.timeOff});

  final TimeOff timeOff;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final requested = timeOff.status == TimeOffStatus.requested;
    final period = formatTimeOffPeriod(l10n, timeOff.startsAt, timeOff.endsAt);
    return AlertDialog(
      title: Text(
        requested ? l10n.timeOffWithdrawTitle : l10n.timeOffCancelTitle,
      ),
      content: Text(
        requested
            ? l10n.timeOffWithdrawText(period)
            : l10n.timeOffCancelText(period),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.timeOffDialogBack),
        ),
        TextButton(
          style: TextButton.styleFrom(foregroundColor: scheme.error),
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(requested ? l10n.timeOffWithdraw : l10n.timeOffCancel),
        ),
      ],
    );
  }
}

DateTime _addDays(DateTime day, int days) =>
    DateTime(day.year, day.month, day.day + days);

int _daysBetween(DateTime from, DateTime to) => DateTime.utc(
  to.year,
  to.month,
  to.day,
).difference(DateTime.utc(from.year, from.month, from.day)).inDays;

String _capitalize(String text) =>
    text.isEmpty ? text : text[0].toUpperCase() + text.substring(1);
