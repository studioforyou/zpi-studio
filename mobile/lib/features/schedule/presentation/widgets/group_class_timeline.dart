import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _pixelsPerMinute = 2.2;

class ClassTimePlacement {
  const ClassTimePlacement({
    required this.event,
    required this.startMinute,
    required this.endMinute,
    required this.lane,
    required this.laneCount,
  });
  final GroupClass event;
  final double startMinute;
  final double endMinute;
  final int lane;
  final int laneCount;
}

/// Real intervals are clipped to the selected day. Only concurrent events
/// share columns; short consecutive events never acquire a false overlap.
List<ClassTimePlacement> placeGroupClasses(
  List<GroupClass> events, {
  DateTime? day,
}) {
  if (events.isEmpty) return const [];
  final selectedDay = DateUtils.dateOnly(day ?? events.first.startsAt);
  final nextDay = DateTime(
    selectedDay.year,
    selectedDay.month,
    selectedDay.day + 1,
  );
  final sorted = [...events]
    ..sort((a, b) {
      final time = a.startsAt.compareTo(b.startsAt);
      return time != 0 ? time : b.endsAt.compareTo(a.endsAt);
    });
  final placements = <ClassTimePlacement>[];
  final cluster = <({GroupClass event, double start, double end, int lane})>[];
  final laneEnds = <double>[];
  double clusterEnd = -1;
  void flush() {
    for (final item in cluster) {
      placements.add(
        ClassTimePlacement(
          event: item.event,
          startMinute: item.start,
          endMinute: item.end,
          lane: item.lane,
          laneCount: laneEnds.length,
        ),
      );
    }
    cluster.clear();
    laneEnds.clear();
  }

  for (final event in sorted) {
    if (!event.startsAt.isBefore(nextDay) ||
        !event.endsAt.isAfter(selectedDay)) {
      continue;
    }
    final start = event.startsAt.isBefore(selectedDay)
        ? 0.0
        : (event.startsAt.hour * 60 + event.startsAt.minute).toDouble();
    final end = !event.endsAt.isBefore(nextDay)
        ? 1440.0
        : (event.endsAt.hour * 60 + event.endsAt.minute).toDouble();
    if (cluster.isNotEmpty && start >= clusterEnd) flush();
    var lane = laneEnds.indexWhere((end) => end <= start);
    if (lane == -1) {
      lane = laneEnds.length;
      laneEnds.add(end);
    } else {
      laneEnds[lane] = end;
    }
    cluster.add((event: event, start: start, end: end, lane: lane));
    clusterEnd = cluster.length == 1 ? end : math.max(clusterEnd, end);
  }
  flush();
  return placements;
}

class GroupClassTimeline extends StatelessWidget {
  const GroupClassTimeline({
    required this.events,
    required this.day,
    required this.onSelect,
    this.footer,
    super.key,
  });
  final List<GroupClass> events;
  final DateTime day;
  final ValueChanged<GroupClass> onSelect;
  final Widget? footer;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    final fontScale = math.max(
      1.0,
      MediaQuery.textScalerOf(context).scale(12) / 12,
    );
    final pixelsPerMinute = _pixelsPerMinute * fontScale;
    final placements = placeGroupClasses(events, day: day);
    if (placements.isEmpty) return const SizedBox.shrink();
    final firstMinute =
        (placements.map((p) => p.startMinute).reduce(math.min) / 60).floor() *
        60;
    final lastMinute =
        (placements.map((p) => p.endMinute).reduce(math.max) / 60).ceil() * 60;
    final height = math.max(
      240.0,
      (lastMinute - firstMinute) * pixelsPerMinute + 28,
    );
    final maxLanes = placements.map((p) => p.laneCount).reduce(math.max);
    final now = DateTime.now();
    final currentMinute = now.hour * 60 + now.minute;
    return SingleChildScrollView(
      key: ValueKey(('timeline', day)),
      physics: const AlwaysScrollableScrollPhysics(),
      padding: const EdgeInsets.fromLTRB(8, 12, 12, 24),
      child: LayoutBuilder(
        builder: (context, constraints) {
          const gutter = 46.0;
          final available = constraints.maxWidth - gutter;
          final canvasWidth = math.max(
            available,
            maxLanes * 140.0 * math.max(1.0, fontScale * 0.8),
          );
          final needsPan = canvasWidth > available + 1;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (needsPan)
                Padding(
                  padding: const EdgeInsets.only(left: gutter, bottom: 8),
                  child: Row(
                    children: [
                      const Icon(Icons.swipe_left_alt_outlined, size: 18),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          l10n.classesSwipeParallel,
                          style: theme.textTheme.labelSmall,
                        ),
                      ),
                    ],
                  ),
                ),
              SizedBox(
                height: height,
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    SizedBox(
                      width: gutter,
                      height: height,
                      child: Stack(
                        children: [
                          for (
                            var minute = firstMinute;
                            minute <= lastMinute;
                            minute += 60
                          )
                            Positioned(
                              left: 0,
                              right: 4,
                              top: (minute - firstMinute) * pixelsPerMinute,
                              child: Text(
                                '${'${(minute ~/ 60) % 24}'.padLeft(2, '0')}:00',
                                style: theme.textTheme.labelSmall?.copyWith(
                                  color: theme.colorScheme.onSurfaceVariant,
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                    Expanded(
                      child: SingleChildScrollView(
                        scrollDirection: Axis.horizontal,
                        child: SizedBox(
                          width: canvasWidth,
                          height: height,
                          child: Stack(
                            clipBehavior: Clip.none,
                            children: [
                              for (
                                var minute = firstMinute;
                                minute <= lastMinute;
                                minute += 60
                              )
                                Positioned(
                                  left: 0,
                                  right: 0,
                                  top:
                                      (minute - firstMinute) * pixelsPerMinute +
                                      8,
                                  child: Divider(
                                    height: 1,
                                    color: theme.colorScheme.outlineVariant
                                        .withValues(alpha: 0.5),
                                  ),
                                ),
                              for (final placement in placements)
                                Positioned(
                                  left:
                                      canvasWidth *
                                      placement.lane /
                                      placement.laneCount,
                                  width: canvasWidth / placement.laneCount - 5,
                                  top:
                                      (placement.startMinute - firstMinute) *
                                          pixelsPerMinute +
                                      8,
                                  height: math.max(
                                    1.0,
                                    (placement.endMinute -
                                                placement.startMinute) *
                                            pixelsPerMinute -
                                        3,
                                  ),
                                  child: _TimelineCard(
                                    event: placement.event,
                                    onTap: () => onSelect(placement.event),
                                  ),
                                ),
                              if (DateUtils.isSameDay(day, now) &&
                                  currentMinute >= firstMinute &&
                                  currentMinute <= lastMinute)
                                Positioned(
                                  left: 0,
                                  right: 0,
                                  top:
                                      (currentMinute - firstMinute) *
                                          pixelsPerMinute +
                                      8,
                                  child: IgnorePointer(
                                    child: Container(
                                      height: 1,
                                      color: theme.colorScheme.error,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              ?footer,
            ],
          );
        },
      ),
    );
  }
}

class _TimelineCard extends StatelessWidget {
  const _TimelineCard({required this.event, required this.onTap});
  final GroupClass event;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final serviceColor = classServiceColor(event, theme.colorScheme);
    final scale = math.max(
      1.0,
      MediaQuery.textScalerOf(context).scale(12) / 12,
    );
    final teacher = event.staff
        .where((p) => p.isLead)
        .map((p) => p.displayName)
        .nonNulls
        .join(', ');
    final count = event.capacity == null
        ? l10n.classesUnlimited
        : '${event.activeReservations}/${event.capacity}';
    final shortOccupancy = event.capacity == null
        ? l10n.classesUnlimited
        : event.isFull
        ? '$count · ${l10n.classesNoPlacesShort}'
        : l10n.classesOccupied(event.activeReservations, event.capacity!);
    final occupancyColor = event.isFull
        ? theme.colorScheme.error
        : Colors.green.shade700;
    return Semantics(
      button: true,
      label: '${event.name}, ${classTime(event)}, ${classPlaces(l10n, event)}',
      child: Tooltip(
        message:
            '${event.name}\n${classTime(event)}\n${classPlaces(l10n, event)}',
        child: Material(
          color: serviceColor.withValues(alpha: 0.11),
          borderRadius: BorderRadius.circular(10),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onTap,
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border(left: BorderSide(color: serviceColor, width: 4)),
              ),
              child: LayoutBuilder(
                builder: (context, constraints) {
                  final tiny = constraints.maxHeight / scale < 40;
                  final compact = constraints.maxHeight / scale < 85;
                  final large = constraints.maxHeight / scale > 110;
                  final title = Row(
                    children: [
                      Expanded(
                        child: Text(
                          event.name ?? l10n.classesTitle,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style:
                              (compact
                                      ? theme.textTheme.labelMedium
                                      : theme.textTheme.labelLarge)
                                  ?.copyWith(fontWeight: FontWeight.w700),
                        ),
                      ),
                      if (event.isReserved)
                        const Icon(Icons.check_circle, size: 14),
                    ],
                  );
                  if (tiny) {
                    return Padding(
                      padding: const EdgeInsets.fromLTRB(8, 2, 6, 2),
                      child: Row(
                        children: [
                          Expanded(child: title),
                          const SizedBox(width: 4),
                          Text(
                            event.isFull ? l10n.classesNoPlacesShort : count,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: occupancyColor,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ],
                      ),
                    );
                  }
                  return Padding(
                    padding: EdgeInsets.fromLTRB(
                      10,
                      compact ? 4 : 7,
                      7,
                      compact ? 4 : 7,
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        title,
                        Text(
                          '${DateFormat('HH:mm').format(event.startsAt)}–${DateFormat('HH:mm').format(event.endsAt)}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.labelSmall,
                        ),
                        if (large && teacher.isNotEmpty)
                          Padding(
                            padding: const EdgeInsets.only(top: 3),
                            child: Text(
                              teacher,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.bodySmall,
                            ),
                          ),
                        if (large && event.roomNames.isNotEmpty)
                          Text(
                            event.roomNames.join(', '),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.bodySmall,
                          ),
                        const Spacer(),
                        if (event.status == 'CANCELLED')
                          Text(
                            l10n.eventStatusCancelled,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              color: theme.colorScheme.error,
                            ),
                          )
                        else if (compact)
                          Text(
                            shortOccupancy,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.labelSmall?.copyWith(
                              fontSize: 10,
                              height: 1.1,
                              color: occupancyColor,
                            ),
                          )
                        else
                          ClassOccupancy(event, compact: true),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}
