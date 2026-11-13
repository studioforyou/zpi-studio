import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/availability_api.dart';
import 'package:mobile/features/schedule/data/time_off_api.dart';
import 'package:mobile/features/schedule/presentation/time_off_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Today's date without time; overridden in tests.
final availabilityTodayProvider = Provider<DateTime>(
  (ref) => DateUtils.dateOnly(DateTime.now()),
);

/// The current moment, for what time off can still be changed; overridden in
/// tests.
final availabilityNowProvider = Provider<DateTime>((ref) => DateTime.now());

/// A day the trainer picked in "Moje urlopy" for the calendar to show.
final availabilityFocusProvider =
    NotifierProvider<AvailabilityFocus, DateTime?>(AvailabilityFocus.new);

class AvailabilityFocus extends Notifier<DateTime?> {
  @override
  DateTime? build() => null;

  void show(DateTime day) => state = day;

  void clear() => state = null;
}

/// The trainer's working hours, one week at a time: a column per day with
/// a bar for each stretch of hours.
class AvailabilityScreen extends ConsumerWidget {
  const AvailabilityScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.navAvailability),
        actions: [
          IconButton(
            key: const Key('time-off-list'),
            tooltip: l10n.timeOffListTooltip,
            icon: const Icon(Icons.list_alt),
            onPressed: () async {
              final day = await context.push<DateTime>(
                '${GoRouterState.of(context).matchedLocation}/time-off',
              );
              if (day != null) {
                ref.read(availabilityFocusProvider.notifier).show(day);
              }
            },
          ),
        ],
      ),
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
                data: (slots) => AvailabilityWeek(
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

/// A bar the trainer tapped: the slot on one day of the shown week.
typedef _Selection = ({AvailabilitySlot slot, DateTime day});

/// One week of availability with its navigation, the add button and the
/// legend.
class AvailabilityWeek extends ConsumerStatefulWidget {
  const AvailabilityWeek({
    super.key,
    required this.accountId,
    required this.slots,
  });

  final String accountId;
  final List<AvailabilitySlot> slots;

  @override
  ConsumerState<AvailabilityWeek> createState() => _AvailabilityWeekState();
}

class _AvailabilityWeekState extends ConsumerState<AvailabilityWeek> {
  late DateTime _today;
  late DateTime _currentWeek;
  late DateTime _weekStart;
  _Selection? _selected;

  @override
  void initState() {
    super.initState();
    _today = ref.read(availabilityTodayProvider);
    _currentWeek = mondayOf(_today);
    _weekStart = _currentWeek;
  }

  @override
  void didUpdateWidget(AvailabilityWeek oldWidget) {
    super.didUpdateWidget(oldWidget);
    // A reload may have split or removed the selected slot.
    if (_selected case (
      :final slot,
      day: _,
    ) when !widget.slots.any((other) => other.id == slot.id)) {
      _selected = null;
    }
  }

  void _focus(DateTime day) {
    final week = mondayOf(day);
    setState(() {
      _weekStart = week.isBefore(_currentWeek) ? _currentWeek : week;
      _selected = null;
    });
  }

  void _showWeek(int offset) {
    setState(() {
      _weekStart = _addDays(_weekStart, 7 * offset);
      _selected = null;
    });
  }

  void _toggle(AvailabilitySlot slot, DateTime day) {
    setState(() {
      final selected = _selected;
      _selected = selected != null && selected.slot.id == slot.id
          ? null
          : (slot: slot, day: day);
    });
  }

  AvailabilityController get _controller =>
      ref.read(availabilityProvider(widget.accountId).notifier);

  Future<void> _openEditor(AvailabilityEditorTarget target) async {
    // The shell's snack bars would otherwise cover the sheet's lower buttons.
    final messenger = ScaffoldMessenger.of(context)..hideCurrentSnackBar();
    final message = await showModalBottomSheet<String>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (_) => AvailabilityEditorSheet(
        accountId: widget.accountId,
        target: target,
        today: _today,
      ),
    );
    if (message != null) {
      if (mounted) {
        setState(() => _selected = null);
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _openTimeOffEditor(TimeOffEditorTarget target) async {
    final messenger = ScaffoldMessenger.of(context)..hideCurrentSnackBar();
    final message = await showModalBottomSheet<String>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (_) => TimeOffEditorSheet(
        accountId: widget.accountId,
        target: target,
        now: ref.read(availabilityNowProvider),
      ),
    );
    if (message != null) {
      if (mounted) {
        setState(() => _selected = null);
      }
      messenger
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _openTimeOff(TimeOff timeOff) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context)..hideCurrentSnackBar();
    setState(() => _selected = null);
    final action = await showModalBottomSheet<TimeOffDetailsAction>(
      context: context,
      useRootNavigator: true,
      isScrollControlled: true,
      showDragHandle: true,
      useSafeArea: true,
      builder: (_) => TimeOffDetailsSheet(
        timeOff: timeOff,
        now: ref.read(availabilityNowProvider),
      ),
    );
    if (!mounted) {
      return;
    }
    switch (action) {
      case null:
        return;
      case TimeOffDetailsAction.edit:
        await _openTimeOffEditor(EditTimeOff(timeOff));
      case TimeOffDetailsAction.cancel:
        final confirmed = await showDialog<bool>(
          context: context,
          builder: (_) => TimeOffCancelDialog(timeOff: timeOff),
        );
        if (confirmed != true) {
          return;
        }
        String message;
        try {
          await ref.read(timeOffApiProvider).cancel(timeOff.id);
          message = timeOff.status == TimeOffStatus.requested
              ? l10n.timeOffWithdrawn
              : l10n.timeOffCancelled;
        } catch (e) {
          message = timeOffErrorMessage(e, l10n);
        }
        ref.invalidate(ownTimeOffProvider(widget.accountId));
        messenger
          ..hideCurrentSnackBar()
          ..showSnackBar(SnackBar(content: Text(message)));
    }
  }

  Future<void> _remove(_Selection selection) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context)..hideCurrentSnackBar();
    final scope = await showDialog<_RemoveScope>(
      context: context,
      builder: (_) => _RemoveDialog(slot: selection.slot),
    );
    if (scope == null) {
      return;
    }
    final slot = selection.slot;
    String message;
    try {
      await _controller.change(
        (api) => api.remove(
          slot,
          onDate: scope == _RemoveScope.day && slot.isWeekly
              ? selection.day
              : null,
        ),
      );
      message = l10n.availabilityRemoved;
      if (mounted) {
        setState(() => _selected = null);
      }
    } catch (e) {
      message = availabilityErrorMessage(e, l10n);
    }
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final weekEnd = _addDays(_weekStart, 6);
    ref.listen(availabilityFocusProvider, (_, day) {
      if (day != null) {
        _focus(day);
        ref.read(availabilityFocusProvider.notifier).clear();
      }
    });
    final nextWeek = _addDays(_weekStart, 7);
    final timeOff = [
      for (final entry
          in ref.watch(ownTimeOffProvider(widget.accountId)).value ??
              const <TimeOff>[])
        if (entry.isActive &&
            entry.startsAt.isBefore(nextWeek) &&
            entry.endsAt.isAfter(_weekStart))
          entry,
    ];

    return SafeArea(
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              spacing: 16,
              children: [
                IconButton(
                  tooltip: l10n.availabilityPreviousWeek,
                  onPressed: _weekStart.isAfter(_currentWeek)
                      ? () => _showWeek(-1)
                      : null,
                  icon: const Icon(Icons.chevron_left),
                ),
                Flexible(
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(minWidth: 130),
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        weekRange(_weekStart),
                        key: const Key('availability-week'),
                        textAlign: TextAlign.center,
                        style: theme.textTheme.titleMedium,
                      ),
                    ),
                  ),
                ),
                IconButton(
                  tooltip: l10n.availabilityNextWeek,
                  onPressed: () => _showWeek(1),
                  icon: const Icon(Icons.chevron_right),
                ),
              ],
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(4, 4, 12, 0),
              child: _WeekCalendar(
                weekStart: _weekStart,
                today: _today,
                slots: [
                  for (final slot in widget.slots)
                    if (_appliesInWeek(slot, _weekStart, weekEnd)) slot,
                ],
                timeOff: timeOff,
                onTapTimeOff: _openTimeOff,
                selected: _selected,
                onTapSlot: _toggle,
                onTapOutside: () => setState(() => _selected = null),
                onRemove: _remove,
                onEdit: (selection) =>
                    _openEditor(EditHours(selection.slot, day: selection.day)),
              ),
            ),
          ),
          const SizedBox(height: 20),
          MenuAnchor(
            alignmentOffset: const Offset(-60, 8),
            menuChildren: [
              MenuItemButton(
                key: const Key('availability-add-hours'),
                leadingIcon: const Icon(Icons.schedule),
                onPressed: () => _openEditor(NewHours(weekStart: _weekStart)),
                child: Text(l10n.availabilityAddHoursOption),
              ),
              MenuItemButton(
                key: const Key('availability-add-time-off'),
                leadingIcon: const Icon(
                  Icons.event_busy_outlined,
                  color: timeOffColor,
                ),
                onPressed: () => _openTimeOffEditor(
                  NewTimeOff(
                    day: _weekStart.isAfter(_today) ? _weekStart : _today,
                  ),
                ),
                child: Text(l10n.availabilityAddTimeOff),
              ),
            ],
            builder: (context, menu, _) => FloatingActionButton(
              heroTag: null,
              backgroundColor: theme.colorScheme.primary,
              foregroundColor: theme.colorScheme.onPrimary,
              tooltip: l10n.availabilityAddMenu,
              onPressed: () => menu.isOpen ? menu.close() : menu.open(),
              child: const Icon(Icons.add, size: 30),
            ),
          ),
          const SizedBox(height: 20),
          _Legend(
            approved: timeOff.any(
              (entry) => entry.status == TimeOffStatus.approved,
            ),
            requested: timeOff.any(
              (entry) => entry.status == TimeOffStatus.requested,
            ),
          ),
          const SizedBox(height: 12),
        ],
      ),
    );
  }

  static bool _appliesInWeek(
    AvailabilitySlot slot,
    DateTime weekStart,
    DateTime weekEnd,
  ) {
    for (var day = weekStart; !day.isAfter(weekEnd); day = _addDays(day, 1)) {
      if (slot.appliesOn(day)) {
        return true;
      }
    }
    return false;
  }
}

/// The hour grid of one week. Days before today stay empty: past hours are
/// not loaded.
class _WeekCalendar extends StatelessWidget {
  const _WeekCalendar({
    required this.weekStart,
    required this.today,
    required this.slots,
    required this.timeOff,
    required this.onTapTimeOff,
    required this.selected,
    required this.onTapSlot,
    required this.onTapOutside,
    required this.onRemove,
    required this.onEdit,
  });

  final DateTime weekStart;
  final DateTime today;
  final List<AvailabilitySlot> slots;
  final List<TimeOff> timeOff;
  final void Function(TimeOff timeOff) onTapTimeOff;
  final _Selection? selected;
  final void Function(AvailabilitySlot slot, DateTime day) onTapSlot;
  final VoidCallback onTapOutside;
  final void Function(_Selection selection) onRemove;
  final void Function(_Selection selection) onEdit;

  static const _axisWidth = 40.0;
  static const _barWidth = 14.0;
  static const _edgePadding = 8.0;
  static const _minRowHeight = 28.0;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final days = [for (var i = 0; i < 7; i++) _addDays(weekStart, i)];
    final selectedDay = selected?.day;

    // Every two hours from 6:00 to 22:00, widened to fit earlier or later hours.
    var firstHour = 6;
    var lastHour = 22;
    for (final slot in slots) {
      firstHour = math.min(firstHour, slot.start.hour ~/ 2 * 2);
      lastHour = math.max(lastHour, ((_minutes(slot.end) + 119) ~/ 120) * 2);
    }
    final rows = (lastHour - firstHour) ~/ 2;

    Widget dimmed(int index, Widget child) {
      final dim =
          selectedDay != null && !DateUtils.isSameDay(selectedDay, days[index]);
      return AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: dim ? 0.45 : 1,
        child: child,
      );
    }

    final header = Row(
      children: [
        const SizedBox(width: _axisWidth),
        for (final (index, day) in days.indexed)
          Expanded(
            child: dimmed(
              index,
              _DayHeader(
                day: day,
                past: day.isBefore(today),
                highlighted:
                    selectedDay != null &&
                    DateUtils.isSameDay(selectedDay, day),
              ),
            ),
          ),
      ],
    );

    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onTap: onTapOutside,
      child: Column(
        children: [
          header,
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                final rowHeight = math.max(
                  _minRowHeight,
                  (constraints.maxHeight - 2 * _edgePadding) / rows,
                );
                final gridHeight = rows * rowHeight;
                double y(TimeOfDay time) =>
                    _edgePadding +
                    (_minutes(time) - firstHour * 60) / 120 * rowHeight;
                final columnWidth = (constraints.maxWidth - _axisWidth) / 7;

                final children = <Widget>[
                  // Hour lines and labels.
                  Positioned.fill(
                    child: CustomPaint(
                      painter: _GridPainter(
                        left: _axisWidth,
                        top: _edgePadding,
                        rowHeight: rowHeight,
                        rows: rows,
                        color: scheme.outlineVariant,
                      ),
                    ),
                  ),
                  for (var row = 0; row <= rows; row++)
                    Positioned(
                      left: 0,
                      width: _axisWidth - 6,
                      top: _edgePadding + row * rowHeight - 8,
                      height: 16,
                      child: Text(
                        '${firstHour + row * 2}:00',
                        textAlign: TextAlign.right,
                        textScaler: TextScaler.noScaling,
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: scheme.outline,
                        ),
                      ),
                    ),
                ];

                for (final (index, day) in days.indexed) {
                  final left = _axisWidth + index * columnWidth;
                  final centre = left + columnWidth / 2;
                  if (selectedDay != null &&
                      DateUtils.isSameDay(selectedDay, day)) {
                    children.add(
                      Positioned(
                        left: left + 2,
                        width: columnWidth - 4,
                        top: 0,
                        height: gridHeight + 2 * _edgePadding,
                        child: DecoratedBox(
                          decoration: BoxDecoration(
                            color: scheme.primaryContainer.withValues(
                              alpha: 0.6,
                            ),
                            borderRadius: const BorderRadius.vertical(
                              bottom: Radius.circular(14),
                            ),
                          ),
                        ),
                      ),
                    );
                  }
                  if (day.isBefore(today)) {
                    continue;
                  }
                  for (final slot in slots) {
                    if (!slot.appliesOn(day)) {
                      continue;
                    }
                    final top = y(slot.start);
                    final height = math.max(8.0, y(slot.end) - top);
                    final isSelected =
                        selected != null &&
                        selected!.slot.id == slot.id &&
                        DateUtils.isSameDay(selectedDay, day);
                    children.add(
                      Positioned(
                        left: centre - 16,
                        width: 32,
                        top: top - 4,
                        height: height + 8,
                        child: dimmed(
                          index,
                          _Bar(
                            key: Key(
                              'availability-bar-${slot.id}-${formatDate(day)}',
                            ),
                            slot: slot,
                            day: day,
                            selected: isSelected,
                            onTap: () => onTapSlot(slot, day),
                          ),
                        ),
                      ),
                    );
                  }
                }

                // Time off covers the hours under it: those hours do not
                // apply while the trainer is away.
                final totalHeight = gridHeight + 2 * _edgePadding;
                double yAt(int minutes) =>
                    (_edgePadding +
                            (minutes - firstHour * 60) / 120 * rowHeight)
                        .clamp(0, totalHeight);
                for (final (index, day) in days.indexed) {
                  if (day.isBefore(today)) {
                    continue;
                  }
                  final dayEnd = _addDays(day, 1);
                  for (final entry in timeOff) {
                    final from = entry.startsAt.isAfter(day)
                        ? entry.startsAt
                        : day;
                    final to = entry.endsAt.isBefore(dayEnd)
                        ? entry.endsAt
                        : dayEnd;
                    if (!from.isBefore(to)) {
                      continue;
                    }
                    final wholeDay = from == day && to == dayEnd;
                    final top = wholeDay
                        ? 0.0
                        : yAt(from.difference(day).inMinutes);
                    final bottom = wholeDay
                        ? totalHeight
                        : yAt(to.difference(day).inMinutes);
                    children.add(
                      Positioned(
                        left: _axisWidth + index * columnWidth + 3,
                        width: columnWidth - 6,
                        top: top,
                        height: math.max(10.0, bottom - top),
                        child: dimmed(
                          index,
                          GestureDetector(
                            key: Key('time-off-${entry.id}-${formatDate(day)}'),
                            behavior: HitTestBehavior.opaque,
                            onTap: () => onTapTimeOff(entry),
                            child: Semantics(
                              button: true,
                              label: timeOffKindLabel(
                                AppLocalizations.of(context),
                                entry.kind,
                              ),
                              child: CustomPaint(
                                painter: TimeOffPainter(
                                  requested:
                                      entry.status == TimeOffStatus.requested,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    );
                  }
                }

                if (selected case final selection?) {
                  final index = selection.day.difference(weekStart).inDays;
                  final centre =
                      _axisWidth + index * columnWidth + columnWidth / 2;
                  final top = y(selection.slot.start);
                  final bottom = y(selection.slot.end);
                  const tipWidth = 110.0;
                  children.add(
                    Positioned(
                      left: (centre - tipWidth / 2).clamp(
                        0,
                        constraints.maxWidth - tipWidth,
                      ),
                      width: tipWidth,
                      top: math.max(0, top - 34),
                      child: Center(child: _TimeTip(slot: selection.slot)),
                    ),
                  );
                  final onLeft = index == 6;
                  const actionsHeight = 84.0;
                  children.add(
                    Positioned(
                      left: onLeft
                          ? centre - _barWidth / 2 - 10 - 40
                          : centre + _barWidth / 2 + 10,
                      top: ((top + bottom) / 2 - actionsHeight / 2).clamp(
                        0,
                        math.max(0, totalHeight - actionsHeight),
                      ),
                      child: _BarActions(
                        onRemove: () => onRemove(selection),
                        onEdit: () => onEdit(selection),
                      ),
                    ),
                  );
                }

                final grid = SizedBox(
                  height: gridHeight + 2 * _edgePadding,
                  child: Stack(clipBehavior: Clip.none, children: children),
                );
                return SingleChildScrollView(
                  physics: gridHeight + 2 * _edgePadding > constraints.maxHeight
                      ? null
                      : const NeverScrollableScrollPhysics(),
                  child: grid,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _DayHeader extends StatelessWidget {
  const _DayHeader({
    required this.day,
    required this.past,
    required this.highlighted,
  });

  final DateTime day;
  final bool past;
  final bool highlighted;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 2),
      padding: const EdgeInsets.only(top: 6, bottom: 4),
      decoration: BoxDecoration(
        color: highlighted
            ? scheme.primaryContainer.withValues(alpha: 0.6)
            : null,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(14)),
      ),
      child: Column(
        children: [
          Text(
            l10n.availabilityWeekdayShort('${day.weekday}'),
            maxLines: 1,
            style: theme.textTheme.labelLarge?.copyWith(
              color: past ? scheme.outline : null,
            ),
          ),
          Text(
            _shortDate.format(day),
            maxLines: 1,
            style: theme.textTheme.labelSmall?.copyWith(color: scheme.outline),
          ),
        ],
      ),
    );
  }
}

/// A bar for one stretch of hours; striped when it applies on a single day.
class _Bar extends StatelessWidget {
  const _Bar({
    super.key,
    required this.slot,
    required this.day,
    required this.selected,
    required this.onTap,
  });

  final AvailabilitySlot slot;
  final DateTime day;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final bar = ClipRRect(
      borderRadius: BorderRadius.circular(_WeekCalendar._barWidth / 2),
      child: CustomPaint(
        painter: slot.isWeekly ? null : _StripesPainter(color: scheme.primary),
        child: ColoredBox(
          color: slot.isWeekly ? scheme.primary : Colors.transparent,
          child: const SizedBox(
            width: _WeekCalendar._barWidth,
            height: double.infinity,
          ),
        ),
      ),
    );
    return Semantics(
      button: true,
      selected: selected,
      label: '${_capitalize(_dayFormat.format(day))}, ${_timeRange(slot)}',
      excludeSemantics: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Center(
          child: Container(
            height: double.infinity,
            padding: const EdgeInsets.all(2),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: selected ? scheme.primary : Colors.transparent,
                width: 2,
              ),
            ),
            child: bar,
          ),
        ),
      ),
    );
  }
}

/// Diagonal stripes in two shades of [color].
class _StripesPainter extends CustomPainter {
  const _StripesPainter({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(
      Offset.zero & size,
      Paint()..color = Color.lerp(color, Colors.white, 0.35)!,
    );
    final paint = Paint()
      ..color = color
      ..strokeWidth = 4;
    for (var y = -size.width; y < size.height + size.width; y += 8) {
      canvas.drawLine(Offset(0, y + size.width), Offset(size.width, y), paint);
    }
  }

  @override
  bool shouldRepaint(_StripesPainter oldDelegate) => oldDelegate.color != color;
}

class _GridPainter extends CustomPainter {
  const _GridPainter({
    required this.left,
    required this.top,
    required this.rowHeight,
    required this.rows,
    required this.color,
  });

  final double left;
  final double top;
  final double rowHeight;
  final int rows;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1;
    for (var row = 0; row <= rows; row++) {
      final y = top + row * rowHeight;
      for (var x = left; x < size.width; x += 7) {
        canvas.drawLine(
          Offset(x, y),
          Offset(math.min(x + 4, size.width), y),
          paint,
        );
      }
    }
  }

  @override
  bool shouldRepaint(_GridPainter oldDelegate) =>
      oldDelegate.rowHeight != rowHeight ||
      oldDelegate.rows != rows ||
      oldDelegate.color != color;
}

class _TimeTip extends StatelessWidget {
  const _TimeTip({required this.slot});

  final AvailabilitySlot slot;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(
        color: scheme.inverseSurface,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
        child: Text(
          _timeRange(slot),
          maxLines: 1,
          textScaler: TextScaler.noScaling,
          style: Theme.of(
            context,
          ).textTheme.labelSmall?.copyWith(color: scheme.onInverseSurface),
        ),
      ),
    );
  }
}

class _BarActions extends StatelessWidget {
  const _BarActions({required this.onRemove, required this.onEdit});

  final VoidCallback onRemove;
  final VoidCallback onEdit;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    Widget action(
      String tooltip,
      IconData icon,
      Color color,
      VoidCallback onPressed,
    ) {
      return Material(
        color: scheme.surfaceContainerLowest,
        shape: const CircleBorder(),
        elevation: 2,
        child: IconButton(
          tooltip: tooltip,
          iconSize: 20,
          constraints: const BoxConstraints.tightFor(width: 40, height: 40),
          padding: EdgeInsets.zero,
          color: color,
          icon: Icon(icon),
          onPressed: onPressed,
        ),
      );
    }

    return Column(
      mainAxisSize: MainAxisSize.min,
      spacing: 4,
      children: [
        action(l10n.availabilityRemove, Icons.delete, scheme.error, onRemove),
        action(
          l10n.availabilityEditHours,
          Icons.edit,
          scheme.onSurfaceVariant,
          onEdit,
        ),
      ],
    );
  }
}

/// Explains the bars; time off is explained only in weeks that show some.
class _Legend extends StatelessWidget {
  const _Legend({required this.approved, required this.requested});

  final bool approved;
  final bool requested;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final style = theme.textTheme.bodySmall?.copyWith(
      color: scheme.onSurfaceVariant,
    );
    Widget swatch({required bool striped}) => ClipRRect(
      borderRadius: BorderRadius.circular(5),
      child: CustomPaint(
        painter: striped ? _StripesPainter(color: scheme.primary) : null,
        child: ColoredBox(
          color: striped ? Colors.transparent : scheme.primary,
          child: const SizedBox(width: 10, height: 18),
        ),
      ),
    );
    return Wrap(
      alignment: WrapAlignment.center,
      spacing: 20,
      runSpacing: 4,
      children: [
        Row(
          mainAxisSize: MainAxisSize.min,
          spacing: 6,
          children: [
            swatch(striped: false),
            Flexible(child: Text(l10n.availabilityLegendWeekly, style: style)),
          ],
        ),
        Row(
          mainAxisSize: MainAxisSize.min,
          spacing: 6,
          children: [
            swatch(striped: true),
            Flexible(
              child: Text(l10n.availabilityLegendThisWeek, style: style),
            ),
          ],
        ),
        if (approved)
          Row(
            mainAxisSize: MainAxisSize.min,
            spacing: 6,
            children: [
              const TimeOffSwatch(requested: false),
              Flexible(
                child: Text(l10n.availabilityLegendTimeOff, style: style),
              ),
            ],
          ),
        if (requested)
          Row(
            mainAxisSize: MainAxisSize.min,
            spacing: 6,
            children: [
              const TimeOffSwatch(requested: true),
              Flexible(
                child: Text(
                  l10n.availabilityLegendTimeOffRequested,
                  style: style,
                ),
              ),
            ],
          ),
      ],
    );
  }
}

enum _RemoveScope { day, everyWeek }

/// Asks whether to remove the hours on the tapped day only or every week.
class _RemoveDialog extends StatelessWidget {
  const _RemoveDialog({required this.slot});

  final AvailabilitySlot slot;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    Widget option(_RemoveScope scope, IconData icon, String label) {
      return Expanded(
        child: OutlinedButton(
          onPressed: () => Navigator.of(context).pop(scope),
          style: OutlinedButton.styleFrom(
            foregroundColor: scheme.onSurface,
            padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 14),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            spacing: 8,
            children: [
              Icon(icon, color: scheme.error, size: 20),
              Flexible(child: Text(label)),
            ],
          ),
        ),
      );
    }

    return AlertDialog(
      title: Text(l10n.availabilityRemoveTitle),
      content: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 10,
          children: [
            option(
              _RemoveScope.day,
              Icons.today,
              l10n.availabilityRemoveThisDay,
            ),
            if (slot.isWeekly)
              option(
                _RemoveScope.everyWeek,
                Icons.event_repeat,
                l10n.availabilityRemoveEveryWeekday('${slot.weekday}'),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(l10n.availabilityCancel),
        ),
      ],
    );
  }
}

/// What the editor sheet works on.
sealed class AvailabilityEditorTarget {
  const AvailabilityEditorTarget();
}

/// New hours, added on the chosen days of [weekStart]'s week or every week.
class NewHours extends AvailabilityEditorTarget {
  const NewHours({required this.weekStart});

  final DateTime weekStart;
}

/// A tapped bar: the slot on [day].
class EditHours extends AvailabilityEditorTarget {
  const EditHours(this.slot, {required this.day});

  final AvailabilitySlot slot;
  final DateTime day;
}

/// Adds or changes hours. Pops with the confirmation to show.
class AvailabilityEditorSheet extends ConsumerStatefulWidget {
  const AvailabilityEditorSheet({
    super.key,
    required this.accountId,
    required this.target,
    required this.today,
  });

  final String accountId;
  final AvailabilityEditorTarget target;
  final DateTime today;

  @override
  ConsumerState<AvailabilityEditorSheet> createState() =>
      _AvailabilityEditorSheetState();
}

class _AvailabilityEditorSheetState
    extends ConsumerState<AvailabilityEditorSheet> {
  late bool _everyWeek;
  late Set<int> _weekdays;
  late TimeOfDay _start;
  late TimeOfDay _end;
  DateTime? _validTo;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    switch (widget.target) {
      case EditHours(:final slot, :final day):
        _everyWeek = slot.isWeekly;
        _weekdays = {day.weekday};
        _start = slot.start;
        _end = slot.end;
        _validTo = slot.isWeekly ? slot.validTo : null;
      case NewHours():
        _everyWeek = true;
        _weekdays = {};
        _start = const TimeOfDay(hour: 16, minute: 0);
        _end = const TimeOfDay(hour: 20, minute: 0);
    }
  }

  bool get _editing => widget.target is EditHours;

  /// Days of the shown week before today cannot get hours of their own.
  bool _canPick(int weekday) {
    if (_editing) {
      return false;
    }
    if (_everyWeek) {
      return true;
    }
    final weekStart = (widget.target as NewHours).weekStart;
    return !_addDays(weekStart, weekday - 1).isBefore(widget.today);
  }

  void _setEveryWeek(bool everyWeek) {
    setState(() {
      _everyWeek = everyWeek;
      if (!_editing) {
        _weekdays.removeWhere((weekday) => !_canPick(weekday));
      }
      _error = null;
    });
  }

  String _title(AppLocalizations l10n) => switch (widget.target) {
    EditHours() => l10n.availabilityEditHours,
    NewHours(:final weekStart) =>
      _everyWeek
          ? l10n.availabilityNewTitle
          : l10n.availabilityNewThisWeekTitle(weekRange(weekStart)),
  };

  Future<void> _save(AppLocalizations l10n) async {
    if (_weekdays.isEmpty) {
      setState(() => _error = l10n.availabilityPickDays);
      return;
    }
    if (_minutes(_end) <= _minutes(_start)) {
      setState(() => _error = l10n.availabilityEndBeforeStart);
      return;
    }
    final weekdays = _weekdays.toList()..sort();
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref
          .read(availabilityProvider(widget.accountId).notifier)
          .change(
            (api) => switch (widget.target) {
              NewHours() when _everyWeek => api.add(
                weekdays: weekdays,
                start: _start,
                end: _end,
                validTo: _validTo,
              ),
              NewHours(:final weekStart) => api.add(
                dates: [
                  for (final weekday in weekdays)
                    _addDays(weekStart, weekday - 1),
                ],
                start: _start,
                end: _end,
              ),
              // Every week on a single day makes it repeat; this week on
              // weekly hours changes only that day.
              EditHours(:final slot, :final day) => api.update(
                slot,
                start: _start,
                end: _end,
                onDate: slot.isWeekly && !_everyWeek ? day : null,
                changeValidTo: _everyWeek,
                validTo: _validTo,
              ),
            },
          );
      if (mounted) {
        Navigator.of(context).pop(l10n.availabilitySaved);
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

  Future<void> _pickValidTo() async {
    final today = widget.today;
    final picked = await showDatePicker(
      context: context,
      firstDate: today,
      lastDate: _addDays(today, 365),
      initialDate: _validTo == null || _validTo!.isBefore(today)
          ? today
          : _validTo!,
    );
    if (picked != null && mounted) {
      setState(() => _validTo = picked);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);

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
            SegmentedButton<bool>(
              segments: [
                ButtonSegment(
                  value: true,
                  label: Text(l10n.availabilityEveryWeek),
                ),
                ButtonSegment(
                  value: false,
                  label: Text(l10n.availabilityThisWeek),
                ),
              ],
              selected: {_everyWeek},
              onSelectionChanged: _busy
                  ? null
                  : (selection) => _setEveryWeek(selection.single),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                for (var weekday = 1; weekday <= 7; weekday++)
                  _DayChip(
                    label: l10n.availabilityWeekdayShort('$weekday'),
                    selected: _weekdays.contains(weekday),
                    onTap: _busy || !_canPick(weekday)
                        ? null
                        : () => setState(() {
                            _error = null;
                            if (!_weekdays.remove(weekday)) {
                              _weekdays.add(weekday);
                            }
                          }),
                  ),
              ],
            ),
            Text(_title(l10n), style: theme.textTheme.titleLarge),
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
            if (_everyWeek)
              _PickerField(
                key: const Key('availability-valid-to'),
                label: l10n.availabilityValidTo,
                value: _validTo == null
                    ? l10n.availabilityNoEndDate
                    : _longDate.format(_validTo!),
                icon: Icons.event_busy,
                onTap: _busy ? null : _pickValidTo,
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
          ],
        ),
      ),
    );
  }
}

/// A round day toggle; the chosen days are filled.
class _DayChip extends StatelessWidget {
  const _DayChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final enabled = onTap != null;
    final foreground = selected
        ? scheme.onPrimary
        : enabled
        ? scheme.onSurfaceVariant
        : scheme.onSurface.withValues(alpha: 0.38);
    return Semantics(
      button: true,
      selected: selected,
      enabled: enabled,
      child: Material(
        color: selected ? scheme.primary : Colors.transparent,
        shape: CircleBorder(
          side: BorderSide(
            color: selected
                ? scheme.primary
                : enabled
                ? scheme.outline
                : scheme.onSurface.withValues(alpha: 0.12),
          ),
        ),
        child: InkWell(
          customBorder: const CircleBorder(),
          onTap: onTap,
          child: SizedBox.square(
            dimension: 38,
            child: Center(
              child: FittedBox(
                child: Padding(
                  padding: const EdgeInsets.all(4),
                  child: Text(
                    label,
                    style: theme.textTheme.labelLarge?.copyWith(
                      color: foreground,
                    ),
                  ),
                ),
              ),
            ),
          ),
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

/// The Monday of [day]'s week.
DateTime mondayOf(DateTime day) => _addDays(day, 1 - day.weekday);

/// The week as „2.11 – 8.11”.
String weekRange(DateTime weekStart) =>
    '${_shortDate.format(weekStart)} – ${_shortDate.format(_addDays(weekStart, 6))}';

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

// Calendar arithmetic on dates, safe across daylight saving changes.
DateTime _addDays(DateTime day, int days) =>
    DateTime(day.year, day.month, day.day + days);

final _dayFormat = DateFormat('EEEE, d MMMM', 'pl');
final _shortDate = DateFormat('d.MM', 'pl');
final _longDate = DateFormat('d MMMM y', 'pl');

String _timeRange(AvailabilitySlot slot) =>
    '${formatTime(slot.start)}–${formatTime(slot.end)}';

int _minutes(TimeOfDay time) => time.hour * 60 + time.minute;

String _capitalize(String text) =>
    text.isEmpty ? text : text[0].toUpperCase() + text.substring(1);
