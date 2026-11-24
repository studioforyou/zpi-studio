import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// What the right column of a [ClassEntry] says.
sealed class EntryAside {
  const EntryAside();
}

/// Plain muted text ("za 36 min", "lista · 3.").
class TextAside extends EntryAside {
  const TextAside(this.text);

  final String text;
}

/// Free places as a number with a word after it ("12 wolne").
class PlacesAside extends EntryAside {
  const PlacesAside(this.places);

  final int places;
}

/// One class or training in the day list.
class ClassEntry {
  const ClassEntry({
    required this.key,
    required this.startsAt,
    required this.endsAt,
    required this.title,
    required this.color,
    this.lines = const [],
    this.change,
    this.label,
    this.aside,
    this.participant,
    this.dimmed = false,
    this.cancelled = false,
    this.onTap,
  });

  /// Unique in the list (event and participant).
  final String key;
  final DateTime startsAt;
  final DateTime endsAt;
  final String title;

  /// The class type's colour, shown as the square on the axis.
  final Color color;

  /// Muted lines under the title (trainer, room).
  final List<String> lines;

  /// An orange line about a one-off change.
  final String? change;

  /// A short label in capitals ("Masz rezerwację").
  final String? label;
  final EntryAside? aside;

  /// Whose class it is, in the family view.
  final String? participant;

  /// A class the person has not booked, in the full offer.
  final bool dimmed;
  final bool cancelled;
  final VoidCallback? onTap;
}

/// The classes of one day.
class DaySection {
  const DaySection({required this.day, required this.entries, this.count});

  final DateTime day;
  final List<ClassEntry> entries;

  /// Shown on the right of the header ("7 zajęć") in the full offer.
  final int? count;
}

/// The day header: weekday and date, "dziś" for today, past days faint.
class DayHeader extends StatelessWidget {
  const DayHeader({
    super.key,
    required this.day,
    required this.today,
    this.count,
  });

  final DateTime day;
  final DateTime today;
  final int? count;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final l10n = AppLocalizations.of(context);
    final past = day.isBefore(today);
    final sameMonth = day.month == today.month && day.year == today.year;
    final text = DateFormat(
      sameMonth ? 'EEEE d' : 'EEEE d.MM',
      'pl',
    ).format(day);
    final name =
        text.characters.first.toUpperCase() +
        text.characters.skip(1).toString();
    return Semantics(
      header: true,
      child: Container(
        padding: EdgeInsets.fromLTRB(
          ct.spacing.page,
          ct.spacing.m + ct.spacing.xxs,
          ct.spacing.page,
          ct.spacing.s + ct.spacing.xxs,
        ),
        decoration: BoxDecoration(
          border: Border(top: ct.hairline, bottom: ct.hairline),
        ),
        child: Row(
          children: [
            Text(
              name,
              style: ct.text.button.copyWith(color: past ? c.faint : c.ink),
            ),
            if (day == today) ...[
              SizedBox(width: ct.spacing.s),
              Text(
                l10n.calendarTodayShort,
                style: ct.text.button.copyWith(color: c.orange),
              ),
            ],
            const Spacer(),
            if (count != null)
              Text(l10n.classesDayCount(count!), style: ct.text.caption),
          ],
        ),
      ),
    );
  }
}

/// One row of the day list: time, the axis with the class square, the
/// description and an aside on the right.
class ClassEntryRow extends StatelessWidget {
  const ClassEntryRow({super.key, required this.entry, this.showStart = true});

  final ClassEntry entry;

  /// Hides the start time when the row above starts at the same time.
  final bool showStart;

  static final _time = DateFormat('HH:mm');

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final l10n = AppLocalizations.of(context);
    final faded = entry.dimmed || entry.cancelled;
    final ink = entry.cancelled ? c.faint : (entry.dimmed ? c.muted : c.ink);
    final strike = entry.cancelled ? TextDecoration.lineThrough : null;
    final square = ct.sizes.classSquare;

    return Semantics(
      button: entry.onTap != null,
      child: InkWell(
        onTap: entry.onTap,
        child: IntrinsicHeight(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              SizedBox(
                width: ct.sizes.timeColumn,
                child: Padding(
                  padding: EdgeInsets.only(
                    right: sp.l,
                    top: sp.l,
                    bottom: sp.l,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      if (showStart)
                        Text(
                          _time.format(entry.startsAt),
                          style: ct.text.eventTime.copyWith(
                            color: entry.cancelled ? c.faint : c.ink,
                            decoration: strike,
                            decorationColor: c.faint,
                          ),
                        ),
                      Text(
                        l10n.calendarUntil(_time.format(entry.endsAt)),
                        style: ct.text.caption.copyWith(
                          color: faded ? c.faint : c.muted,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              Stack(
                clipBehavior: Clip.none,
                children: [
                  VerticalDivider(
                    width: ct.shape.hairline,
                    thickness: ct.shape.hairline,
                    color: c.line,
                  ),
                  Positioned(
                    top: sp.l + sp.s - 1,
                    left: -(square - ct.shape.hairline) / 2,
                    child: Container(
                      width: square,
                      height: square,
                      color: entry.cancelled
                          ? c.faint
                          : entry.dimmed
                          ? Color.lerp(entry.color, c.background, 0.45)
                          : entry.color,
                    ),
                  ),
                ],
              ),
              Expanded(
                child: Padding(
                  padding: EdgeInsets.fromLTRB(sp.l, sp.l, sp.page, sp.l),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              entry.title,
                              style: ct.text.eventTitle.copyWith(
                                color: ink,
                                decoration: strike,
                                decorationColor: c.faint,
                              ),
                            ),
                            SizedBox(height: sp.xs),
                            for (final line in entry.lines)
                              Text(
                                line,
                                style: ct.text.meta.copyWith(
                                  color: faded ? c.faint : c.muted,
                                ),
                              ),
                            if (entry.change != null) ...[
                              SizedBox(height: sp.xs),
                              Text(entry.change!, style: ct.text.changeLine),
                            ],
                            if (entry.label != null) ...[
                              SizedBox(height: sp.s - 2),
                              Text(
                                ct.text.upper(entry.label!),
                                style: ct.text.label.copyWith(color: c.ink),
                              ),
                            ],
                            if (entry.participant != null) ...[
                              SizedBox(height: sp.s - 2),
                              _Participant(name: entry.participant!),
                            ],
                          ],
                        ),
                      ),
                      if (entry.aside != null) ...[
                        SizedBox(width: sp.s),
                        _Aside(aside: entry.aside!, faded: faded),
                      ],
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

class _Aside extends StatelessWidget {
  const _Aside({required this.aside, required this.faded});

  final EntryAside aside;
  final bool faded;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final l10n = AppLocalizations.of(context);
    return switch (aside) {
      TextAside(:final text) => Padding(
        padding: EdgeInsets.only(top: ct.spacing.xxs),
        child: Text(text, style: ct.text.caption),
      ),
      PlacesAside(:final places) => Text.rich(
        TextSpan(
          children: [
            TextSpan(
              text: '$places',
              style: ct.text.eventTime.copyWith(color: faded ? c.muted : c.ink),
            ),
            TextSpan(text: ' ${l10n.classesFreeShort}', style: ct.text.caption),
          ],
        ),
      ),
    };
  }
}

class _Participant extends StatelessWidget {
  const _Participant({required this.name});

  final String name;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        SquareAvatar(
          initials: SquareAvatar.initialsOf(name).characters.first,
          background: c.highlight,
          foreground: c.background,
          size: ct.sizes.badge,
          style: ct.text.badge,
        ),
        SizedBox(width: ct.spacing.s - 2),
        Text(name, style: ct.text.lineLabel.copyWith(color: c.ink)),
      ],
    );
  }
}
