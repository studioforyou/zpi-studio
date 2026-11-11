import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_change_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

String _shortDay(DateTime date) => DateFormat('EEE d.MM', 'pl').format(date);

final _time = DateFormat('HH:mm');

String _range(DateTime start, DateTime end) =>
    '${_time.format(start)}–${_time.format(end)}';

bool _sameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;

/// What a notice says in one line, e.g. "Zmiana godziny i sali: Pole dance,
/// wt. 17.11" or "Przypisano Cię do zajęć: Stretching, sob. 21.11".
String noticeHeadline(AppLocalizations l10n, ScheduleChangeNotice notice) {
  final name = notice.eventName ?? l10n.classesTitle;
  final day = _shortDay(notice.startsAt);
  return switch (notice.kind) {
    NoticeKind.assigned => l10n.changeNoticeAssigned(name, day),
    NoticeKind.unassigned => l10n.changeNoticeUnassigned(name, day),
    NoticeKind.changed => l10n.changeNoticeChanged(
      noticeLabel(l10n, notice),
      name,
      day,
    ),
  };
}

/// The kind of a change, e.g. "Zmiana godziny i sali"; "Zmiana obsady" when
/// only the assistants changed.
String noticeLabel(AppLocalizations l10n, ScheduleChangeNotice notice) {
  final parts = [
    if (notice.timeChanged)
      _sameDay(notice.previousStartsAt, notice.startsAt)
          ? l10n.changePartTime
          : l10n.changePartDate,
    if (notice.roomsChanged) l10n.changePartRoom,
    if (notice.leadsChanged) l10n.changePartInstructor,
  ];
  if (parts.isEmpty) return l10n.changeNoticeStaff;
  final joined = parts.length == 1
      ? parts.single
      : '${parts.sublist(0, parts.length - 1).join(', ')} '
            '${l10n.changeAnd} ${parts.last}';
  return l10n.changeLabel(joined);
}

/// What changed, before and after, e.g. "18:00–19:00 → 19:00–20:00 · Sala A
/// → Sala B". A trainer assigned or taken off sees when and where it is.
String noticeDetails(AppLocalizations l10n, ScheduleChangeNotice notice) {
  String names(List<String> names) =>
      names.isEmpty ? l10n.changeNoticeNoLead : names.join(', ');
  final anotherDay = !_sameDay(notice.previousStartsAt, notice.startsAt);
  String when(DateTime start, DateTime end) => anotherDay
      ? '${_shortDay(start)}, ${_range(start, end)}'
      : _range(start, end);
  final parts = [
    if (notice.timeChanged)
      '${when(notice.previousStartsAt, notice.previousEndsAt)} → '
          '${when(notice.startsAt, notice.endsAt)}'
    else if (notice.kind != NoticeKind.changed)
      _range(notice.startsAt, notice.endsAt),
    if (notice.roomsChanged)
      '${names(notice.previousRooms)} → ${names(notice.rooms)}'
    else if (notice.kind != NoticeKind.changed && notice.rooms.isNotEmpty)
      notice.rooms.join(', '),
    if (notice.leadsChanged)
      l10n.changeNoticeLeads(names(notice.previousLeads), names(notice.leads)),
  ];
  return parts.join(' · ');
}

/// Tells the participant or the trainer about each change of their classes
/// they have not closed yet: every saved change is its own notice, so a
/// trainer assigned and taken off again hears about both. "Pokaż" lists them;
/// each one is closed on its own.
class ScheduleChangesBanner extends ConsumerWidget {
  const ScheduleChangesBanner({
    required this.owner,
    required this.onOpen,
    super.key,
  });

  final ChangesOwner owner;

  /// Opens the event of a notice.
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final notices =
        ref.watch(scheduleChangesProvider(owner)).value ??
        const <ScheduleChangeNotice>[];
    if (notices.isEmpty) return const SizedBox.shrink();
    const shown = 3;
    return ChangesBannerFrame(
      title: l10n.changeBannerTitle(notices.length),
      lines: [
        for (final notice in notices.take(shown)) noticeHeadline(l10n, notice),
        if (notices.length > shown)
          l10n.changeBannerMore(notices.length - shown),
      ],
      onShow: () => showModalBottomSheet<void>(
        context: context,
        showDragHandle: true,
        isScrollControlled: true,
        builder: (sheetContext) => ScheduleChangesSheet(
          owner: owner,
          onOpen: (eventId) {
            Navigator.of(sheetContext).pop();
            onOpen(eventId);
          },
        ),
      ),
    );
  }
}

/// The orange card above a schedule that points to its changes.
class ChangesBannerFrame extends StatelessWidget {
  const ChangesBannerFrame({
    required this.title,
    required this.lines,
    required this.onShow,
    super.key,
  });

  final String title;
  final List<String> lines;
  final VoidCallback onShow;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
      child: Material(
        color: ChangeColors.card,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
          side: const BorderSide(color: ChangeColors.border),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: onShow,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
            child: Row(
              spacing: 10,
              children: [
                const Icon(
                  Icons.edit_calendar_outlined,
                  color: ChangeColors.accent,
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    spacing: 2,
                    children: [
                      Text(
                        title,
                        style: theme.textTheme.titleSmall?.copyWith(
                          color: ChangeColors.onContainer,
                        ),
                      ),
                      for (final line in lines)
                        Text(
                          line,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall,
                        ),
                    ],
                  ),
                ),
                TextButton(
                  onPressed: onShow,
                  style: TextButton.styleFrom(
                    foregroundColor: ChangeColors.accent,
                  ),
                  child: Text(l10n.changeBannerShow),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// The open notices, newest first, each with a button that closes it. A
/// trainer taken off a class cannot open it any more.
class ScheduleChangesSheet extends ConsumerWidget {
  const ScheduleChangesSheet({
    required this.owner,
    required this.onOpen,
    super.key,
  });

  final ChangesOwner owner;
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final notices =
        ref.watch(scheduleChangesProvider(owner)).value ??
        const <ScheduleChangeNotice>[];
    return SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.only(bottom: 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 12, 4),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      l10n.changeSheetTitle,
                      style: theme.textTheme.titleLarge,
                    ),
                  ),
                  if (notices.length > 1)
                    TextButton(
                      onPressed: () async {
                        await dismissAllScheduleChanges(ref, owner);
                        if (context.mounted) Navigator.of(context).pop();
                      },
                      style: TextButton.styleFrom(
                        foregroundColor: ChangeColors.accent,
                      ),
                      child: Text(l10n.changeSheetCloseAll),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 8),
              child: Text(
                l10n.changeSheetHint,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ),
            for (final notice in notices)
              ListTile(
                key: ValueKey(notice.id),
                contentPadding: const EdgeInsets.only(left: 24, right: 8),
                onTap: notice.kind == NoticeKind.unassigned
                    ? null
                    : () => onOpen(notice.eventId),
                leading: Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: ChangeColors.container,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    '${DateFormat('EEE', 'pl').format(notice.startsAt)}\n'
                    '${DateFormat('d.MM').format(notice.startsAt)}',
                    textAlign: TextAlign.center,
                    style: theme.textTheme.labelMedium?.copyWith(
                      color: ChangeColors.onContainer,
                    ),
                  ),
                ),
                title: Text(noticeHeadline(l10n, notice)),
                subtitle: Text(noticeDetails(l10n, notice)),
                trailing: IconButton(
                  tooltip: l10n.changeNoticeClose,
                  icon: const Icon(Icons.close),
                  onPressed: () => dismissScheduleChange(ref, owner, notice.id),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
