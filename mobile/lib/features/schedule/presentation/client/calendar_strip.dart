import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// What a day of the calendar shows under its number.
class DayMarks {
  const DayMarks({this.own = false, this.change = false});

  /// The person has a class or training that day.
  final bool own;

  /// One of their classes that day differs from its usual slot.
  final bool change;
}

DateTime _day(DateTime date) => DateTime(date.year, date.month, date.day);

/// Monday of the week of [date].
DateTime weekStart(DateTime date) =>
    _day(date).subtract(Duration(days: date.weekday - 1));

/// [date] moved by [days] calendar days, safe across daylight saving changes.
DateTime addDays(DateTime date, int days) =>
    DateTime(date.year, date.month, date.day + days);

/// The week strip that expands into the month grid. The strip keeps the
/// weekday when it moves a week; the month keeps the day of the month.
class CalendarStrip extends StatelessWidget {
  const CalendarStrip({
    super.key,
    required this.selected,
    required this.today,
    required this.expanded,
    required this.marks,
    required this.onSelect,
    required this.onExpandedChanged,
  });

  final DateTime selected;
  final DateTime today;
  final bool expanded;

  /// Marks by day (midnight, local time).
  final Map<DateTime, DayMarks> marks;
  final ValueChanged<DateTime> onSelect;
  final ValueChanged<bool> onExpandedChanged;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final l10n = AppLocalizations.of(context);
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onVerticalDragEnd: (details) {
        final velocity = details.primaryVelocity ?? 0;
        if (velocity > 200 && !expanded) onExpandedChanged(true);
        if (velocity < -200 && expanded) onExpandedChanged(false);
      },
      onHorizontalDragEnd: (details) {
        final velocity = details.primaryVelocity ?? 0;
        if (velocity.abs() < 200) return;
        _move(velocity < 0 ? 1 : -1);
      },
      child: AnimatedSize(
        duration: ct.motion.medium,
        curve: Curves.easeOutCubic,
        alignment: Alignment.topCenter,
        child: Column(
          children: [
            if (expanded) _month(context) else _week(context),
            Semantics(
              button: true,
              label: expanded ? l10n.calendarCollapse : l10n.calendarExpand,
              excludeSemantics: true,
              child: InkWell(
                onTap: () => onExpandedChanged(!expanded),
                child: SizedBox(
                  height: ct.sizes.tapTarget - ct.spacing.m,
                  width: double.infinity,
                  child: Icon(
                    expanded ? Icons.expand_less : Icons.expand_more,
                    color: ct.colors.muted,
                    size: ct.sizes.icon,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  void _move(int step) {
    if (expanded) {
      final month = DateTime(selected.year, selected.month + step);
      final last = DateUtils.getDaysInMonth(month.year, month.month);
      onSelect(
        DateTime(
          month.year,
          month.month,
          selected.day > last ? last : selected.day,
        ),
      );
    } else {
      onSelect(addDays(selected, 7 * step));
    }
  }

  List<String> _weekdays(AppLocalizations l10n) =>
      l10n.calendarWeekdaysShort.split(',');

  Widget _arrow(BuildContext context, IconData icon, String tooltip, int step) {
    final ct = context.ct;
    return SizedBox(
      width: ct.sizes.iconButton,
      child: IconButton(
        tooltip: tooltip,
        padding: EdgeInsets.zero,
        icon: Icon(icon, color: ct.colors.muted, size: ct.sizes.icon),
        onPressed: () => _move(step),
      ),
    );
  }

  Widget _week(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final l10n = AppLocalizations.of(context);
    final names = _weekdays(l10n);
    final start = weekStart(selected);
    final day = _day(selected);
    return Padding(
      padding: EdgeInsets.only(top: ct.spacing.xs),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          _arrow(context, Icons.chevron_left, l10n.calendarPreviousWeek, -1),
          for (var i = 0; i < 7; i++)
            Builder(
              builder: (context) {
                final date = addDays(start, i);
                final isSelected = date == day;
                final own = marks[date]?.own ?? false;
                final label = DateFormat('EEEE d MMMM', 'pl').format(date);
                return Expanded(
                  flex: isSelected ? 2 : 1,
                  child: Semantics(
                    button: true,
                    selected: isSelected,
                    label: date == _day(today)
                        ? '$label, ${l10n.scheduleToday}'
                        : label,
                    excludeSemantics: true,
                    child: InkWell(
                      onTap: () => onSelect(date),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            names[i],
                            style: ct.text.caption.copyWith(
                              color: isSelected ? c.ink : c.muted,
                            ),
                          ),
                          SizedBox(height: ct.spacing.xxs),
                          Text(
                            '${date.day}',
                            maxLines: 1,
                            style: isSelected
                                ? ct.text.weekDaySelected
                                : ct.text.stripDay.copyWith(
                                    color: own ? c.muted : c.faint,
                                  ),
                          ),
                          SizedBox(height: ct.spacing.s),
                          Container(
                            height: ct.shape.selectedDayUnderline,
                            margin: EdgeInsets.symmetric(
                              horizontal: ct.spacing.xs,
                            ),
                            color: isSelected ? c.orange : Colors.transparent,
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              },
            ),
          _arrow(context, Icons.chevron_right, l10n.calendarNextWeek, 1),
        ],
      ),
    );
  }

  Widget _month(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final l10n = AppLocalizations.of(context);
    final names = _weekdays(l10n);
    final first = DateTime(selected.year, selected.month);
    final gridStart = weekStart(first);
    final last = DateTime(
      selected.year,
      selected.month,
      DateUtils.getDaysInMonth(selected.year, selected.month),
    );
    final weeks = (last.difference(gridStart).inDays ~/ 7) + 1;
    final day = _day(selected);
    final selectedWeek = weekStart(day);
    final title = DateFormat('LLLL yyyy', 'pl').format(first);
    return Padding(
      padding: EdgeInsets.symmetric(horizontal: sp.s),
      child: Column(
        children: [
          Row(
            children: [
              _arrow(
                context,
                Icons.chevron_left,
                l10n.calendarPreviousMonth,
                -1,
              ),
              Expanded(
                child: Text(
                  title.characters.first.toUpperCase() +
                      title.characters.skip(1).toString(),
                  textAlign: TextAlign.center,
                  style: ct.text.monthTitle,
                ),
              ),
              _arrow(context, Icons.chevron_right, l10n.calendarNextMonth, 1),
            ],
          ),
          SizedBox(height: sp.s),
          Row(
            children: [
              for (final name in names)
                Expanded(
                  child: Text(
                    name,
                    textAlign: TextAlign.center,
                    style: ct.text.caption,
                  ),
                ),
            ],
          ),
          SizedBox(height: sp.xs),
          for (var w = 0; w < weeks; w++)
            Container(
              height: ct.sizes.monthRowHeight,
              color: addDays(gridStart, 7 * w) == selectedWeek
                  ? c.surface
                  : null,
              child: Row(
                children: [
                  for (var i = 0; i < 7; i++)
                    Expanded(
                      child: _monthCell(context, addDays(gridStart, 7 * w + i)),
                    ),
                ],
              ),
            ),
          SizedBox(height: sp.s),
          _legend(context),
        ],
      ),
    );
  }

  Widget _monthCell(BuildContext context, DateTime date) {
    final ct = context.ct;
    final c = ct.colors;
    final s = ct.sizes;
    final inMonth = date.month == selected.month;
    final isSelected = date == _day(selected);
    final isToday = date == _day(today);
    final mark = marks[date];
    final l10n = AppLocalizations.of(context);
    final label = DateFormat('EEEE d MMMM', 'pl').format(date);
    return Semantics(
      button: true,
      selected: isSelected,
      label: isToday ? '$label, ${l10n.scheduleToday}' : label,
      excludeSemantics: true,
      child: InkWell(
        onTap: () => onSelect(date),
        child: Stack(
          alignment: Alignment.center,
          children: [
            if (isToday)
              Positioned.fill(
                child: Padding(
                  padding: EdgeInsets.symmetric(
                    vertical: ct.spacing.xxs + 1,
                    horizontal: ct.spacing.xs,
                  ),
                  child: Center(
                    child: SizedBox(
                      width: s.tapTarget,
                      height: double.infinity,
                      child: ColoredBox(color: c.todayFill),
                    ),
                  ),
                ),
              ),
            Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  padding: EdgeInsets.only(bottom: ct.spacing.xxs),
                  decoration: BoxDecoration(
                    border: Border(
                      bottom: BorderSide(
                        color: isSelected ? c.ink : Colors.transparent,
                        width: ct.shape.monthSelectedUnderline,
                      ),
                    ),
                  ),
                  child: Text(
                    '${date.day}',
                    style: ct.text.weekDay.copyWith(
                      color: inMonth ? c.ink : c.faint,
                      fontWeight: isSelected || isToday
                          ? ct.text.selectedWeight
                          : null,
                    ),
                  ),
                ),
                SizedBox(height: ct.spacing.xs),
                SizedBox(
                  height: s.ownMarkerHeight,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    spacing: ct.spacing.xxs + 1,
                    children: [
                      if (mark?.own ?? false)
                        Container(
                          width: s.ownMarkerWidth,
                          height: s.ownMarkerHeight,
                          color: inMonth ? c.ownMarker : c.faint,
                        ),
                      if (mark?.change ?? false)
                        Container(
                          width: s.changeDot,
                          height: s.changeDot,
                          color: c.orange,
                        ),
                    ],
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _legend(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final s = ct.sizes;
    final l10n = AppLocalizations.of(context);
    Widget item(Widget mark, String label) => Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        mark,
        SizedBox(width: ct.spacing.s - 2),
        Text(label, style: ct.text.lineLabel),
      ],
    );
    return ExcludeSemantics(
      child: Wrap(
        alignment: WrapAlignment.center,
        spacing: ct.spacing.l,
        children: [
          item(
            Container(
              width: s.ownMarkerWidth - 2,
              height: s.ownMarkerHeight,
              color: c.ownMarker,
            ),
            l10n.calendarLegendOwn,
          ),
          item(
            Container(width: s.changeDot, height: s.changeDot, color: c.orange),
            l10n.calendarLegendChange,
          ),
        ],
      ),
    );
  }
}
