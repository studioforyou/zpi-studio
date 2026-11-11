import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Colours of a one-off change of a series occurrence. Orange, because red
/// already marks a cancelled event.
abstract final class ChangeColors {
  static const container = Color(0xFFFFDCBE);
  static const onContainer = Color(0xFF2C1600);
  static const accent = Color(0xFF8A5100);
  static const card = Color(0xFFFFF1E6);
  static const border = Color(0xFFFFB877);
}

final _time = DateFormat('HH:mm');
final _day = DateFormat('EEEE d.MM', 'pl');

/// `HH:mm–HH:mm`.
String changeTimeRange(DateTime start, DateTime end) =>
    '${_time.format(start)}–${_time.format(end)}';

bool _sameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;

/// Whether a moved occurrence takes place on another day than usual.
bool changeMovedToAnotherDay(SeriesChange change, DateTime startsAt) =>
    change.usualStartsAt != null && !_sameDay(change.usualStartsAt!, startsAt);

/// A short name of what changed, e.g. "Zmiana godziny i sali", or
/// "Zastępstwo" when only someone else leads the event.
String changeLabel(
  AppLocalizations l10n,
  SeriesChange change,
  DateTime startsAt,
) {
  final parts = [
    if (change.moved)
      changeMovedToAnotherDay(change, startsAt)
          ? l10n.changePartDate
          : l10n.changePartTime,
    if (change.usualRooms != null) l10n.changePartRoom,
    if (change.usualInstructors != null) l10n.changePartInstructor,
  ];
  if (parts.length == 1 && change.usualInstructors != null) {
    return l10n.changeSubstitution;
  }
  final joined = parts.length == 1
      ? parts.single
      : '${parts.sublist(0, parts.length - 1).join(', ')} '
            '${l10n.changeAnd} ${parts.last}';
  return l10n.changeLabel(joined);
}

/// The orange label on a changed event's tile.
class ChangeChip extends StatelessWidget {
  const ChangeChip({required this.change, required this.startsAt, super.key});

  final SeriesChange change;
  final DateTime startsAt;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
        decoration: BoxDecoration(
          color: ChangeColors.container,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          spacing: 4,
          children: [
            const Icon(
              Icons.edit_calendar_outlined,
              size: 16,
              color: ChangeColors.accent,
            ),
            Flexible(
              child: Text(
                changeLabel(l10n, change, startsAt),
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: ChangeColors.onContainer,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A value that differs this once: the new value stands out, the usual one
/// follows crossed out. Without [usual] it is the plain [value]. A [prefix],
/// such as "Prowadzący: ", stays plain.
class ChangedValue extends StatelessWidget {
  const ChangedValue(
    this.value, {
    this.usual,
    this.style,
    this.prefix = '',
    super.key,
  });

  final String value;
  final String? usual;
  final TextStyle? style;
  final String prefix;

  @override
  Widget build(BuildContext context) {
    final base = style ?? DefaultTextStyle.of(context).style;
    final usual = this.usual;
    if (usual == null) return Text('$prefix$value', style: base);
    return Text.rich(
      TextSpan(
        children: [
          if (prefix.isNotEmpty) TextSpan(text: prefix),
          TextSpan(
            text: value,
            style: base.copyWith(
              fontWeight: FontWeight.w700,
              color: ChangeColors.accent,
            ),
          ),
          const TextSpan(text: ' '),
          TextSpan(
            text: usual,
            style: base.copyWith(
              fontWeight: FontWeight.w400,
              color: Theme.of(context).colorScheme.outline,
              decoration: TextDecoration.lineThrough,
            ),
          ),
        ],
      ),
    );
  }
}

/// The time of an event, with its usual time when it moved.
String? usualTimeOf(SeriesChange? change, DateTime startsAt) {
  final usualStart = change?.usualStartsAt;
  final usualEnd = change?.usualEndsAt;
  if (usualStart == null || usualEnd == null) return null;
  final range = changeTimeRange(usualStart, usualEnd);
  return changeMovedToAnotherDay(change!, startsAt)
      ? '${_day.format(usualStart)}, $range'
      : range;
}

/// The "Jednorazowa zmiana" card at the top of an event's details: each part
/// that differs, with its new and its usual value.
class SeriesChangeCard extends StatelessWidget {
  const SeriesChangeCard({
    required this.change,
    required this.startsAt,
    required this.endsAt,
    required this.instructors,
    required this.rooms,
    super.key,
  });

  final SeriesChange change;
  final DateTime startsAt;
  final DateTime endsAt;
  final List<String> instructors;
  final List<String> rooms;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final anotherDay = changeMovedToAnotherDay(change, startsAt);
    final valueStyle = theme.textTheme.bodyLarge;
    Widget row(IconData icon, String label, String value, String usual) => Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 10,
      children: [
        Icon(icon, size: 20, color: ChangeColors.accent),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 1,
            children: [
              Text(
                label,
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
              ChangedValue(value, usual: usual, style: valueStyle),
            ],
          ),
        ),
      ],
    );
    final range = changeTimeRange(startsAt, endsAt);
    return Card(
      margin: EdgeInsets.zero,
      color: ChangeColors.card,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: const BorderSide(color: ChangeColors.border),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          spacing: 10,
          children: [
            Row(
              spacing: 8,
              children: [
                const Icon(
                  Icons.edit_calendar_outlined,
                  color: ChangeColors.accent,
                ),
                Text(
                  l10n.changeCardTitle,
                  style: theme.textTheme.titleSmall?.copyWith(
                    color: ChangeColors.onContainer,
                  ),
                ),
              ],
            ),
            if (usualTimeOf(change, startsAt) case final usual?)
              anotherDay
                  ? row(
                      Icons.event_outlined,
                      l10n.changeRowDate,
                      '${_day.format(startsAt)}, $range',
                      usual,
                    )
                  : row(
                      Icons.schedule_outlined,
                      l10n.changeRowTime,
                      range,
                      usual,
                    ),
            if (change.usualRooms case final usual?)
              row(
                Icons.meeting_room_outlined,
                l10n.changeRowRoom,
                rooms.join(', '),
                usual.join(', '),
              ),
            if (change.usualInstructors case final usual?)
              row(
                Icons.person_outline,
                l10n.changeRowInstructor,
                instructors.join(', '),
                usual.join(', '),
              ),
          ],
        ),
      ),
    );
  }
}

/// What a changed event's description says about it in a list, e.g.
/// "18:30–19:30 (zwykle 17:00–18:00) · Sala B (zwykle Sala A)".
String changeSummary(
  AppLocalizations l10n,
  SeriesChange change, {
  required DateTime startsAt,
  required DateTime endsAt,
  required List<String> instructors,
  required List<String> rooms,
}) {
  final anotherDay = changeMovedToAnotherDay(change, startsAt);
  final range = changeTimeRange(startsAt, endsAt);
  return [
    if (usualTimeOf(change, startsAt) case final usual?)
      l10n.changeUsually(
        anotherDay ? '${_day.format(startsAt)}, $range' : range,
        usual,
      ),
    if (change.usualRooms case final usual?)
      l10n.changeUsually(rooms.join(', '), usual.join(', ')),
    if (change.usualInstructors case final usual?)
      l10n.changeUsually(instructors.join(', '), usual.join(', ')),
  ].join(' · ');
}

/// A dashed placeholder in a list, where an event usually is.
class UsualSlotTile extends StatelessWidget {
  const UsualSlotTile({
    required this.title,
    required this.subtitle,
    this.onTap,
    this.icon = Icons.east,
    super.key,
  });

  /// Shown crossed out: what usually happens here.
  final String title;

  /// Shown in orange: where it went, or who runs it.
  final String subtitle;
  final VoidCallback? onTap;
  final IconData icon;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final muted = theme.colorScheme.outline;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(12),
          child: CustomPaint(
            painter: _DashedBorder(color: theme.colorScheme.outlineVariant),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: Row(
                spacing: 10,
                children: [
                  Icon(icon, size: 20, color: muted),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      spacing: 3,
                      children: [
                        Text(
                          title,
                          style: theme.textTheme.bodyMedium?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                            decoration: TextDecoration.lineThrough,
                          ),
                        ),
                        Text(
                          subtitle,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: ChangeColors.accent,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                  if (onTap != null)
                    Icon(Icons.chevron_right, size: 20, color: muted),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Where a moved event went, for its placeholder: "sob. 21.11, 10:00" on
/// another day, the time on the same day.
String movedToText(AppLocalizations l10n, DateTime startsAt, DateTime usual) {
  final when = _sameDay(startsAt, usual)
      ? _time.format(startsAt)
      : '${DateFormat('EEE d.MM', 'pl').format(startsAt)}, '
            '${_time.format(startsAt)}';
  return l10n.changeMovedTo(when);
}

/// The placeholder of an event moved away from its usual slot.
class MovedAwayTile extends StatelessWidget {
  const MovedAwayTile({
    required this.name,
    required this.startsAt,
    required this.usualStartsAt,
    this.onTap,
    super.key,
  });

  final String name;
  final DateTime startsAt;
  final DateTime usualStartsAt;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return UsualSlotTile(
      title: l10n.changeMovedUsual(name, _time.format(usualStartsAt)),
      subtitle: movedToText(l10n, startsAt, usualStartsAt),
      onTap: onTap,
    );
  }
}

class _DashedBorder extends CustomPainter {
  const _DashedBorder({required this.color});

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    final path = Path()
      ..addRRect(
        RRect.fromRectAndRadius(Offset.zero & size, const Radius.circular(12)),
      );
    for (final metric in path.computeMetrics()) {
      for (var distance = 0.0; distance < metric.length; distance += 9) {
        canvas.drawPath(metric.extractPath(distance, distance + 5), paint);
      }
    }
  }

  @override
  bool shouldRepaint(_DashedBorder oldDelegate) => oldDelegate.color != color;
}
