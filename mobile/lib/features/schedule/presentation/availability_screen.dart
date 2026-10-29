import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/availability_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The trainer's working hours: a regular week and single extra days.
class AvailabilityScreen extends ConsumerWidget {
  const AvailabilityScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.navAvailability)),
      body: switch (ref.watch(authControllerProvider)) {
        AuthRestoring() || Unauthenticated() => const SizedBox(),
        Authenticated(:final session) =>
          ref
              .watch(availabilityProvider(session.accountId))
              .when(
                loading: () => const Center(child: CircularProgressIndicator()),
                error: (error, _) => _LoadError(
                  message: availabilityErrorMessage(error, l10n, loading: true),
                  onRetry: () =>
                      ref.invalidate(availabilityProvider(session.accountId)),
                ),
                data: (slots) => _AvailabilityList(
                  accountId: session.accountId,
                  slots: slots,
                ),
              ),
      },
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          spacing: 16,
          children: [
            const Icon(Icons.error_outline),
            Text(message, textAlign: TextAlign.center),
            OutlinedButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: Text(AppLocalizations.of(context).availabilityRetry),
            ),
          ],
        ),
      ),
    );
  }
}

class _AvailabilityList extends StatelessWidget {
  const _AvailabilityList({required this.accountId, required this.slots});

  final String accountId;
  final List<AvailabilitySlot> slots;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final weekly = slots.where((slot) => slot.isWeekly).toList();
    final days = slots.where((slot) => !slot.isWeekly).toList();

    return SafeArea(
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 32),
        children: [
          Text(
            l10n.availabilityIntro,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 16),
          _SectionHeader(title: l10n.availabilityWeeklySection),
          Card(
            margin: EdgeInsets.zero,
            child: Column(
              children: [
                for (var weekday = 1; weekday <= 7; weekday++) ...[
                  if (weekday > 1) const Divider(height: 1),
                  _WeekdayRow(
                    accountId: accountId,
                    weekday: weekday,
                    slots: [
                      for (final slot in weekly)
                        if (slot.weekday == weekday) slot,
                    ],
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 24),
          _SectionHeader(
            title: l10n.availabilityExtraSection,
            action: TextButton.icon(
              onPressed: () => openAvailabilityEditor(
                context,
                accountId: accountId,
                target: const NewDay(),
              ),
              icon: const Icon(Icons.add),
              label: Text(l10n.availabilityAddDay),
            ),
          ),
          Text(
            l10n.availabilityExtraHint,
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 8),
          if (days.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 12),
              child: Text(
                l10n.availabilityNoExtraDays,
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: theme.colorScheme.outline,
                ),
              ),
            )
          else
            Card(
              margin: EdgeInsets.zero,
              child: Column(
                children: [
                  for (final (index, slot) in days.indexed) ...[
                    if (index > 0) const Divider(height: 1),
                    ListTile(
                      leading: const Icon(Icons.event_available),
                      title: Text(_capitalize(_dayFormat.format(slot.date!))),
                      subtitle: Text(_timeRange(slot)),
                      trailing: const Icon(Icons.chevron_right),
                      onTap: () => openAvailabilityEditor(
                        context,
                        accountId: accountId,
                        target: EditSlot(slot),
                      ),
                    ),
                  ],
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title, this.action});

  final String title;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 48,
      child: Row(
        children: [
          Expanded(
            child: Text(title, style: Theme.of(context).textTheme.titleMedium),
          ),
          ?action,
        ],
      ),
    );
  }
}

/// One weekday of the regular week, with its hours and a button to add more.
class _WeekdayRow extends StatelessWidget {
  const _WeekdayRow({
    required this.accountId,
    required this.weekday,
    required this.slots,
  });

  final String accountId;
  final int weekday;
  final List<AvailabilitySlot> slots;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 4, 8),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              spacing: 4,
              children: [
                Text(weekdayName(weekday), style: theme.textTheme.titleSmall),
                if (slots.isEmpty)
                  Text(
                    l10n.availabilityDayOff,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: scheme.outline,
                    ),
                  )
                else
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: [
                      for (final slot in slots)
                        ActionChip(
                          avatar: Icon(Icons.schedule, color: scheme.primary),
                          label: Text(_weeklyLabel(slot, l10n)),
                          tooltip: l10n.availabilityEditHours,
                          onPressed: () => openAvailabilityEditor(
                            context,
                            accountId: accountId,
                            target: EditSlot(slot),
                          ),
                        ),
                    ],
                  ),
              ],
            ),
          ),
          IconButton(
            tooltip: l10n.availabilityAddHours,
            icon: const Icon(Icons.add_circle_outline),
            onPressed: () => openAvailabilityEditor(
              context,
              accountId: accountId,
              target: NewWeekly(weekday),
            ),
          ),
        ],
      ),
    );
  }

  /// The hours, with the period when it does not simply start today and
  /// run on.
  static String _weeklyLabel(AvailabilitySlot slot, AppLocalizations l10n) {
    final today = DateUtils.dateOnly(DateTime.now());
    return [
      _timeRange(slot),
      if (slot.validFrom.isAfter(today))
        l10n.availabilitySince(_shortDate.format(slot.validFrom)),
      if (slot.validTo case final validTo?)
        l10n.availabilityUntil(_shortDate.format(validTo)),
    ].join(' · ');
  }
}

/// What the editor sheet works on.
sealed class AvailabilityTarget {
  const AvailabilityTarget();
}

class NewWeekly extends AvailabilityTarget {
  const NewWeekly(this.weekday);

  final int weekday;
}

class NewDay extends AvailabilityTarget {
  const NewDay();
}

class EditSlot extends AvailabilityTarget {
  const EditSlot(this.slot);

  final AvailabilitySlot slot;
}

/// Opens the editor and shows a confirmation once it saved something.
Future<void> openAvailabilityEditor(
  BuildContext context, {
  required String accountId,
  required AvailabilityTarget target,
}) async {
  // The shell's snack bars would otherwise cover the sheet's lower buttons.
  final messenger = ScaffoldMessenger.of(context)..hideCurrentSnackBar();
  final message = await showModalBottomSheet<String>(
    context: context,
    useRootNavigator: true,
    isScrollControlled: true,
    showDragHandle: true,
    useSafeArea: true,
    builder: (_) =>
        AvailabilityEditorSheet(accountId: accountId, target: target),
  );
  if (message != null) {
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

/// Adds, changes or removes one slot. Pops with the confirmation to show.
class AvailabilityEditorSheet extends ConsumerStatefulWidget {
  const AvailabilityEditorSheet({
    super.key,
    required this.accountId,
    required this.target,
  });

  final String accountId;
  final AvailabilityTarget target;

  @override
  ConsumerState<AvailabilityEditorSheet> createState() =>
      _AvailabilityEditorSheetState();
}

class _AvailabilityEditorSheetState
    extends ConsumerState<AvailabilityEditorSheet> {
  late TimeOfDay _start;
  late TimeOfDay _end;
  late DateTime _date;
  DateTime? _validTo;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    final today = DateUtils.dateOnly(DateTime.now());
    switch (widget.target) {
      case EditSlot(:final slot):
        _start = slot.start;
        _end = slot.end;
        _date = slot.date ?? today;
        _validTo = slot.validTo;
      case NewWeekly() || NewDay():
        _start = const TimeOfDay(hour: 16, minute: 0);
        _end = const TimeOfDay(hour: 20, minute: 0);
        _date = today.add(const Duration(days: 1));
    }
  }

  bool get _isWeekly => switch (widget.target) {
    NewWeekly() => true,
    NewDay() => false,
    EditSlot(:final slot) => slot.isWeekly,
  };

  String _title(AppLocalizations l10n) => switch (widget.target) {
    NewWeekly(:final weekday) => l10n.availabilityNewWeeklyTitle(
      weekdayName(weekday),
    ),
    NewDay() => l10n.availabilityNewDayTitle,
    EditSlot(:final slot) =>
      slot.isWeekly
          ? weekdayName(slot.weekday)
          : _capitalize(_dayFormat.format(slot.date!)),
  };

  AvailabilityController get _controller =>
      ref.read(availabilityProvider(widget.accountId).notifier);

  Future<void> _save(AppLocalizations l10n) async {
    if (_minutes(_end) <= _minutes(_start)) {
      setState(() => _error = l10n.availabilityEndBeforeStart);
      return;
    }
    await _run(l10n, l10n.availabilitySaved, (api) {
      return switch (widget.target) {
        NewWeekly(:final weekday) => api.addWeekly(
          weekday: weekday,
          start: _start,
          end: _end,
          validTo: _validTo,
        ),
        NewDay() => api.addDay(date: _date, start: _start, end: _end),
        EditSlot(:final slot) => api.update(
          slot,
          start: _start,
          end: _end,
          validTo: _validTo,
        ),
      };
    });
  }

  Future<void> _remove(AppLocalizations l10n, AvailabilitySlot slot) {
    return _run(l10n, l10n.availabilityRemoved, (api) => api.remove(slot));
  }

  Future<void> _run(
    AppLocalizations l10n,
    String confirmation,
    Future<void> Function(AvailabilityApi api) action,
  ) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await _controller.change(action);
      if (mounted) {
        Navigator.of(context).pop(confirmation);
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = availabilityErrorMessage(e, l10n);
        });
      }
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

  Future<void> _pickDate({required bool validTo}) async {
    final today = DateUtils.dateOnly(DateTime.now());
    final first = validTo ? today.add(const Duration(days: 1)) : today;
    final current = validTo ? _validTo : _date;
    final picked = await showDatePicker(
      context: context,
      firstDate: first,
      lastDate: today.add(const Duration(days: 365)),
      initialDate: current == null || current.isBefore(first) ? first : current,
    );
    if (picked != null && mounted) {
      setState(() {
        if (validTo) {
          _validTo = picked;
        } else {
          _date = picked;
        }
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final target = widget.target;

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
          spacing: 16,
          children: [
            Text(_title(l10n), style: theme.textTheme.titleLarge),
            if (target is NewDay)
              _PickerField(
                key: const Key('availability-date'),
                label: l10n.availabilityDate,
                value: _capitalize(_dayFormat.format(_date)),
                icon: Icons.calendar_today,
                onTap: _busy ? null : () => _pickDate(validTo: false),
              ),
            Row(
              spacing: 12,
              children: [
                Expanded(
                  child: _PickerField(
                    key: const Key('availability-start'),
                    label: l10n.availabilityStart,
                    value: formatTime(_start),
                    icon: Icons.schedule,
                    onTap: _busy ? null : () => _pickTime(start: true),
                  ),
                ),
                Expanded(
                  child: _PickerField(
                    key: const Key('availability-end'),
                    label: l10n.availabilityEnd,
                    value: formatTime(_end),
                    icon: Icons.schedule,
                    onTap: _busy ? null : () => _pickTime(start: false),
                  ),
                ),
              ],
            ),
            if (_isWeekly)
              _PickerField(
                key: const Key('availability-valid-to'),
                label: l10n.availabilityValidTo,
                value: _validTo == null
                    ? l10n.availabilityNoEndDate
                    : _longDate.format(_validTo!),
                icon: Icons.event_busy,
                onTap: _busy ? null : () => _pickDate(validTo: true),
                onClear: _validTo == null || _busy
                    ? null
                    : () => setState(() => _validTo = null),
                clearTooltip: l10n.availabilityClearEndDate,
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
                  : Text(l10n.availabilitySave),
            ),
            if (target case EditSlot(:final slot))
              TextButton.icon(
                onPressed: _busy ? null : () => _remove(l10n, slot),
                style: TextButton.styleFrom(
                  foregroundColor: theme.colorScheme.error,
                ),
                icon: const Icon(Icons.delete_outline),
                label: Text(l10n.availabilityRemove),
              ),
          ],
        ),
      ),
    );
  }
}

/// A read-only field that opens a picker when tapped.
class _PickerField extends StatelessWidget {
  const _PickerField({
    super.key,
    required this.label,
    required this.value,
    required this.icon,
    required this.onTap,
    this.onClear,
    this.clearTooltip,
  });

  final String label;
  final String value;
  final IconData icon;
  final VoidCallback? onTap;
  final VoidCallback? onClear;
  final String? clearTooltip;

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
          suffixIcon: onClear == null
              ? null
              : IconButton(
                  tooltip: clearTooltip,
                  icon: const Icon(Icons.close),
                  onPressed: onClear,
                ),
        ),
        child: Text(value),
      ),
    );
  }
}

/// The Polish name of an ISO weekday, capitalized: 1 is „Poniedziałek”.
String weekdayName(int weekday) =>
    _capitalize(DateFormat('EEEE', 'pl').format(DateTime(2024, 1, weekday)));

String availabilityErrorMessage(
  Object error,
  AppLocalizations l10n, {
  bool loading = false,
}) {
  if (error is ApiException) {
    return switch (error.statusCode) {
      null => l10n.availabilityNetworkError,
      401 || 403 => l10n.availabilityAccessError,
      404 => l10n.availabilityNotFound,
      409 => l10n.availabilityOverlap,
      400 => l10n.availabilityValidationError,
      _ => loading ? l10n.availabilityLoadError : l10n.availabilitySaveError,
    };
  }
  return loading ? l10n.availabilityLoadError : l10n.availabilitySaveError;
}

final _dayFormat = DateFormat('EEEE, d MMMM', 'pl');
final _shortDate = DateFormat('d.MM', 'pl');
final _longDate = DateFormat('d MMMM y', 'pl');

String _timeRange(AvailabilitySlot slot) =>
    '${formatTime(slot.start)}–${formatTime(slot.end)}';

int _minutes(TimeOfDay time) => time.hour * 60 + time.minute;

String _capitalize(String text) =>
    text.isEmpty ? text : text[0].toUpperCase() + text.substring(1);
