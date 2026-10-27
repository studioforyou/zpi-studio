import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/theme/brand_colors.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// How much of the schedule the calendar shows at once.
enum ScheduleView { day, week, month }

typedef ScheduleEventBuilder =
    Widget Function(BuildContext context, ScheduleEvent event);
typedef ScheduleDayBuilder =
    Widget Function(
      BuildContext context,
      List<ScheduleEvent> events,
      DateTime day,
    );
typedef ScheduleEventColor =
    Color Function(ScheduleEvent event, ColorScheme scheme);
typedef ScheduleLegendBuilder =
    Widget Function(BuildContext context, List<ScheduleEvent> events);

/// A schedule in a day, week or month view, with navigation between periods.
///
/// The events come from [eventsFor], so the same calendar can show a
/// trainer's own events or the studio's schedule. The screen that shows the
/// calendar decides how its events are filtered.
class ScheduleCalendar extends ConsumerStatefulWidget {
  const ScheduleCalendar({
    super.key,
    required this.eventsFor,
    this.where,
    this.filtersBuilder,
    this.today,
    this.eventBuilder,
    this.dayBuilder,
    this.eventColor,
    this.legendBuilder,
    this.showLegend = true,
    this.onRefresh,
  });

  /// Events overlapping a window, earliest first.
  final FutureProvider<List<ScheduleEvent>> Function(ScheduleWindow window)
  eventsFor;

  /// Which of the loaded events are shown; all of them when `null`.
  final bool Function(ScheduleEvent event)? where;

  /// Filter controls shown above the events. They get every loaded event of
  /// the period, e.g. to offer its rooms.
  final Widget Function(BuildContext context, List<ScheduleEvent> events)?
  filtersBuilder;

  /// The day the calendar opens on; the current day when `null`.
  final DateTime? today;

  /// Optional participant-specific content for event rows in each list view.
  /// Without it the trainer's standard [ScheduleEventTile] is used.
  final ScheduleEventBuilder? eventBuilder;

  /// Replaces the entire day body, including its legend. Receives only filtered
  /// events overlapping the selected day and must return a vertical scrollable so pull to
  /// refresh remains available (for example an hourly client timetable).
  final ScheduleDayBuilder? dayBuilder;

  /// Optional service colours for month markers. Cancelled events remain red.
  final ScheduleEventColor? eventColor;

  /// Optional explanation for custom event colours in the default list bodies.
  final ScheduleLegendBuilder? legendBuilder;
  final bool showLegend;

  /// Refreshes the data sources behind the visible window. Supply this when
  /// [eventsFor] adapts another cached provider that also needs invalidating.
  /// Used both for pull to refresh and for retrying a failed load.
  final Future<void> Function(ScheduleWindow window)? onRefresh;

  @override
  ConsumerState<ScheduleCalendar> createState() => _ScheduleCalendarState();
}

class _ScheduleCalendarState extends ConsumerState<ScheduleCalendar> {
  ScheduleView _view = ScheduleView.week;

  /// The selected day: the day of the day view and the highlighted day of
  /// the month view.
  late DateTime _day = _today;

  DateTime get _today => _dateOnly(widget.today ?? DateTime.now());

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final window = _window(_view, _day);
    final provider = widget.eventsFor(window);
    final eventsState = ref.watch(provider);
    final filtersBuilder = widget.filtersBuilder;
    final where = widget.where;

    // The header must remain scrollable when large text or the keyboard leaves
    // too little height, including while this calendar is retained offstage.
    return NestedScrollView(
      headerSliverBuilder: (context, innerBoxIsScrolled) => [
        SliverToBoxAdapter(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 0),
                child: SegmentedButton<ScheduleView>(
                  segments: [
                    ButtonSegment(
                      value: ScheduleView.day,
                      label: Text(l10n.scheduleViewDay),
                    ),
                    ButtonSegment(
                      value: ScheduleView.week,
                      label: Text(l10n.scheduleViewWeek),
                    ),
                    ButtonSegment(
                      value: ScheduleView.month,
                      label: Text(l10n.scheduleViewMonth),
                    ),
                  ],
                  selected: {_view},
                  showSelectedIcon: false,
                  onSelectionChanged: (selection) =>
                      setState(() => _view = selection.single),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Row(
                  children: [
                    IconButton(
                      icon: const Icon(Icons.chevron_left),
                      tooltip: l10n.schedulePrevious,
                      onPressed: () => setState(() => _day = _step(-1)),
                    ),
                    Expanded(
                      child: Text(
                        _periodLabel(_view, _day, window),
                        textAlign: TextAlign.center,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    IconButton(
                      icon: const Icon(Icons.chevron_right),
                      tooltip: l10n.scheduleNext,
                      onPressed: () => setState(() => _day = _step(1)),
                    ),
                    TextButton(
                      onPressed: () => setState(() => _day = _today),
                      child: Text(l10n.scheduleToday),
                    ),
                  ],
                ),
              ),
              if (filtersBuilder != null)
                filtersBuilder(context, eventsState.value ?? const []),
            ],
          ),
        ),
      ],
      body: eventsState
          .whenData(
            (events) => where == null ? events : events.where(where).toList(),
          )
          .when(
            skipLoadingOnReload: false,
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (_, _) => _LoadError(onRetry: () => _refresh(window)),
            data: (events) => RefreshIndicator(
              onRefresh: () => _refresh(window),
              child: switch (_view) {
                ScheduleView.day =>
                  widget.dayBuilder?.call(
                        context,
                        _eventsOn(events, _day),
                        _day,
                      ) ??
                      _DayList(
                        events: events,
                        day: _day,
                        eventBuilder: widget.eventBuilder,
                        legend: _legend(context, events),
                      ),
                ScheduleView.week => _WeekList(
                  events: events,
                  window: window,
                  today: _today,
                  onDaySelected: _openDay,
                  eventBuilder: widget.eventBuilder,
                  legend: _legend(context, events),
                ),
                ScheduleView.month => _MonthGrid(
                  events: events,
                  window: window,
                  month: _day,
                  selected: _day,
                  today: _today,
                  onDaySelected: (day) => setState(() => _day = day),
                  eventBuilder: widget.eventBuilder,
                  eventColor: widget.eventColor,
                  legend: _legend(context, events),
                ),
              },
            ),
          ),
    );
  }

  Widget? _legend(BuildContext context, List<ScheduleEvent> events) {
    if (!widget.showLegend) return null;
    return widget.legendBuilder?.call(context, events) ?? const _Legend();
  }

  Future<void> _refresh(ScheduleWindow window) async {
    try {
      final onRefresh = widget.onRefresh;
      if (onRefresh != null) {
        await onRefresh(window);
      } else {
        final request = ref.refresh(widget.eventsFor(window).future);
        await request;
      }
    } catch (_) {
      // The observed provider displays load failures and offers another retry.
    }
  }

  void _openDay(DateTime day) {
    setState(() {
      _view = ScheduleView.day;
      _day = day;
    });
  }

  /// The selected day moved by [direction] periods of the current view.
  DateTime _step(int direction) {
    return switch (_view) {
      ScheduleView.day => _addDays(_day, direction),
      ScheduleView.week => _addDays(_day, 7 * direction),
      ScheduleView.month => DateTime(_day.year, _day.month + direction),
    };
  }
}

/// The window the view shows around [day]. A month view shows six whole
/// weeks, starting on the Monday on or before the first of the month.
ScheduleWindow _window(ScheduleView view, DateTime day) {
  switch (view) {
    case ScheduleView.day:
      return (from: day, to: _addDays(day, 1));
    case ScheduleView.week:
      final monday = _mondayOf(day);
      return (from: monday, to: _addDays(monday, 7));
    case ScheduleView.month:
      final start = _mondayOf(DateTime(day.year, day.month));
      return (from: start, to: _addDays(start, 42));
  }
}

String _periodLabel(ScheduleView view, DateTime day, ScheduleWindow window) {
  switch (view) {
    case ScheduleView.day:
      return _capitalize(DateFormat('EEEE, d MMMM y', 'pl').format(day));
    case ScheduleView.week:
      final first = window.from;
      final last = _addDays(window.to, -1);
      final end = DateFormat('d MMMM y', 'pl').format(last);
      return first.month == last.month
          ? '${first.day}–$end'
          : '${DateFormat('d MMMM', 'pl').format(first)} – $end';
    case ScheduleView.month:
      return _capitalize(DateFormat('LLLL y', 'pl').format(day));
  }
}

/// Events that overlap the day.
List<ScheduleEvent> _eventsOn(List<ScheduleEvent> events, DateTime day) {
  final next = _addDays(day, 1);
  return [
    for (final event in events)
      if (event.startsAt.isBefore(next) && event.endsAt.isAfter(day)) event,
  ];
}

class _DayList extends StatelessWidget {
  const _DayList({
    required this.events,
    required this.day,
    required this.eventBuilder,
    required this.legend,
  });

  final List<ScheduleEvent> events;
  final DateTime day;
  final ScheduleEventBuilder? eventBuilder;
  final Widget? legend;

  @override
  Widget build(BuildContext context) {
    final dayEvents = _eventsOn(events, day);
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(16),
      children: [
        if (dayEvents.isEmpty)
          _Message(AppLocalizations.of(context).scheduleEmptyDay),
        for (final event in dayEvents)
          eventBuilder?.call(context, event) ?? ScheduleEventTile(event),
        ?legend,
      ],
    );
  }
}

class _WeekList extends StatelessWidget {
  const _WeekList({
    required this.events,
    required this.window,
    required this.today,
    required this.onDaySelected,
    required this.eventBuilder,
    required this.legend,
  });

  final List<ScheduleEvent> events;
  final ScheduleWindow window;
  final DateTime today;
  final ValueChanged<DateTime> onDaySelected;
  final ScheduleEventBuilder? eventBuilder;
  final Widget? legend;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(16),
      children: [
        for (var i = 0; i < 7; i++)
          ...() {
            final day = _addDays(window.from, i);
            final dayEvents = _eventsOn(events, day);
            return [
              InkWell(
                onTap: () => onDaySelected(day),
                child: Padding(
                  padding: const EdgeInsets.only(top: 12, bottom: 4),
                  child: Text(
                    _capitalize(DateFormat('EEEE, d MMMM', 'pl').format(day)),
                    style: day == today
                        ? textTheme.titleSmall?.copyWith(
                            color: Theme.of(context).colorScheme.primary,
                          )
                        : textTheme.titleSmall,
                  ),
                ),
              ),
              if (dayEvents.isEmpty)
                Text(l10n.scheduleEmptyDay, style: textTheme.bodySmall),
              for (final event in dayEvents)
                eventBuilder?.call(context, event) ?? ScheduleEventTile(event),
            ];
          }(),
        ?legend,
      ],
    );
  }
}

class _MonthGrid extends StatelessWidget {
  const _MonthGrid({
    required this.events,
    required this.window,
    required this.month,
    required this.selected,
    required this.today,
    required this.onDaySelected,
    required this.eventBuilder,
    required this.eventColor,
    required this.legend,
  });

  final List<ScheduleEvent> events;
  final ScheduleWindow window;
  final DateTime month;
  final DateTime selected;
  final DateTime today;
  final ValueChanged<DateTime> onDaySelected;
  final ScheduleEventBuilder? eventBuilder;
  final ScheduleEventColor? eventColor;
  final Widget? legend;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    final selectedEvents = _eventsOn(events, selected);
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            for (var i = 0; i < 7; i++)
              Expanded(
                child: Text(
                  DateFormat('EEE', 'pl').format(_addDays(window.from, i)),
                  textAlign: TextAlign.center,
                  style: textTheme.labelSmall,
                ),
              ),
          ],
        ),
        for (var week = 0; week < 6; week++)
          Row(
            children: [
              for (var i = 0; i < 7; i++)
                Expanded(
                  child: _MonthDay(
                    day: _addDays(window.from, week * 7 + i),
                    events: _eventsOn(
                      events,
                      _addDays(window.from, week * 7 + i),
                    ),
                    inMonth:
                        _addDays(window.from, week * 7 + i).month ==
                        month.month,
                    isSelected: _addDays(window.from, week * 7 + i) == selected,
                    isToday: _addDays(window.from, week * 7 + i) == today,
                    onTap: onDaySelected,
                    eventColor: eventColor,
                  ),
                ),
            ],
          ),
        const Divider(height: 24),
        Text(
          _capitalize(DateFormat('EEEE, d MMMM', 'pl').format(selected)),
          style: textTheme.titleSmall,
        ),
        const SizedBox(height: 4),
        if (selectedEvents.isEmpty)
          Text(l10n.scheduleEmptyDay, style: textTheme.bodySmall),
        for (final event in selectedEvents)
          eventBuilder?.call(context, event) ?? ScheduleEventTile(event),
        ?legend,
      ],
    );
  }
}

class _MonthDay extends StatelessWidget {
  const _MonthDay({
    required this.day,
    required this.events,
    required this.inMonth,
    required this.isSelected,
    required this.isToday,
    required this.onTap,
    required this.eventColor,
  });

  /// At most this many events are marked with a dot; the rest are counted.
  static const _maxDots = 3;

  final DateTime day;
  final List<ScheduleEvent> events;
  final bool inMonth;
  final bool isSelected;
  final bool isToday;
  final ValueChanged<DateTime> onTap;
  final ScheduleEventColor? eventColor;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final textScale = MediaQuery.textScalerOf(context).scale(14) / 14;
    return Tooltip(
      message: events.isEmpty
          ? ''
          : AppLocalizations.of(context).scheduleDayEventCount(events.length),
      child: InkWell(
        onTap: () => onTap(day),
        borderRadius: BorderRadius.circular(8),
        child: Container(
          height: 48 * (textScale < 1 ? 1 : textScale),
          decoration: BoxDecoration(
            color: isSelected ? scheme.primaryContainer : null,
            borderRadius: BorderRadius.circular(8),
            border: isToday ? Border.all(color: scheme.primary) : null,
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            spacing: 4,
            children: [
              Text(
                '${day.day}',
                style: textTheme.bodyMedium?.copyWith(
                  color: inMonth ? null : scheme.outline,
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 2),
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    spacing: 2,
                    children: [
                      for (final event in events.take(_maxDots))
                        _Dot(
                          event.status == EventStatus.cancelled
                              ? scheme.error
                              : eventColor?.call(event, scheme) ??
                                    scheduleEventColor(event, scheme),
                        ),
                      if (events.length > _maxDots)
                        Text(
                          '+${events.length - _maxDots}',
                          style: textTheme.labelSmall?.copyWith(
                            height: 1,
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// One event: its time, name, kind and rooms, marked with the colour of its
/// kind, or red when it is cancelled.
class ScheduleEventTile extends StatelessWidget {
  const ScheduleEventTile(this.event, {super.key});

  final ScheduleEvent event;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final color = scheduleEventColor(event, scheme);
    final cancelled = event.status == EventStatus.cancelled;
    final time = DateFormat('HH:mm');
    final details = [eventKindLabel(l10n, event.kind), ...event.roomNames];

    return Card(
      clipBehavior: Clip.antiAlias,
      child: IntrinsicHeight(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(width: 6, color: color),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  spacing: 2,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            '${time.format(event.startsAt)}–'
                            '${time.format(event.endsAt)}',
                            style: textTheme.labelLarge,
                          ),
                        ),
                        if (cancelled)
                          Text(
                            l10n.eventStatusCancelled,
                            style: textTheme.labelMedium?.copyWith(
                              color: scheme.error,
                            ),
                          )
                        else if (event.status == EventStatus.completed)
                          Text(
                            l10n.eventStatusCompleted,
                            style: textTheme.labelMedium?.copyWith(
                              color: scheme.outline,
                            ),
                          ),
                      ],
                    ),
                    Text(
                      event.name ?? eventKindLabel(l10n, event.kind),
                      style: textTheme.titleMedium?.copyWith(
                        decoration: cancelled
                            ? TextDecoration.lineThrough
                            : null,
                        color: cancelled ? scheme.error : null,
                      ),
                    ),
                    Text(details.join(' · '), style: textTheme.bodySmall),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The colour of an event's kind; red for a cancelled event.
Color scheduleEventColor(ScheduleEvent event, ColorScheme scheme) {
  if (event.status == EventStatus.cancelled) {
    return scheme.error;
  }
  return eventKindColor(event.kind);
}

/// The colour of an event kind in the calendar, its legend and filters.
Color eventKindColor(EventKind kind) => switch (kind) {
  EventKind.groupClass => BrandColors.teal500,
  EventKind.personalTraining => const Color(0xFF8E5BB5),
  EventKind.workshop => const Color(0xFFE08A2E),
  EventKind.consultation => const Color(0xFF3F7CC4),
  EventKind.block => const Color(0xFF9E9E9E),
};

String eventKindLabel(AppLocalizations l10n, EventKind kind) {
  return switch (kind) {
    EventKind.groupClass => l10n.eventKindClass,
    EventKind.personalTraining => l10n.eventKindPersonalTraining,
    EventKind.workshop => l10n.eventKindWorkshop,
    EventKind.consultation => l10n.eventKindConsultation,
    EventKind.block => l10n.eventKindBlock,
  };
}

/// What the colours mean, below the events.
class _Legend extends StatelessWidget {
  const _Legend();

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final entries = [
      for (final kind in [
        EventKind.groupClass,
        EventKind.personalTraining,
        EventKind.workshop,
        EventKind.consultation,
      ])
        (color: eventKindColor(kind), label: eventKindLabel(l10n, kind)),
      (color: scheme.error, label: l10n.eventStatusCancelled),
    ];
    return Padding(
      padding: const EdgeInsets.only(top: 16),
      child: Wrap(
        spacing: 12,
        runSpacing: 4,
        children: [
          for (final entry in entries)
            Row(
              mainAxisSize: MainAxisSize.min,
              spacing: 4,
              children: [
                _Dot(entry.color),
                Flexible(child: Text(entry.label, style: textTheme.labelSmall)),
              ],
            ),
        ],
      ),
    );
  }
}

class _Dot extends StatelessWidget {
  const _Dot(this.color);

  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 6,
      height: 6,
      decoration: BoxDecoration(color: color, shape: BoxShape.circle),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Text(text, textAlign: TextAlign.center),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        spacing: 16,
        children: [
          Text(l10n.scheduleLoadError),
          OutlinedButton(
            onPressed: onRetry,
            child: Text(l10n.scheduleRetryButton),
          ),
        ],
      ),
    );
  }
}

DateTime _dateOnly(DateTime time) => DateTime(time.year, time.month, time.day);

/// Calendar arithmetic in local time, so a DST change does not shift days.
DateTime _addDays(DateTime day, int days) =>
    DateTime(day.year, day.month, day.day + days);

DateTime _mondayOf(DateTime day) =>
    _addDays(day, -(day.weekday - DateTime.monday));

String _capitalize(String text) =>
    text.isEmpty ? text : text[0].toUpperCase() + text.substring(1);
