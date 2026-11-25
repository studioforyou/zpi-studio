import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The status of a pass as a coloured dot and label.
class PassStatusDot extends StatelessWidget {
  const PassStatusDot(this.status, {super.key});

  final PassStatus status;

  @override
  Widget build(BuildContext context) {
    return StatusDot(
      label: passStatusLabel(AppLocalizations.of(context), status),
      tone: switch (status) {
        PassStatus.active => StatusTone.ok,
        PassStatus.scheduled => StatusTone.scheduled,
        PassStatus.pendingPayment || PassStatus.suspended => StatusTone.warning,
        PassStatus.expired || PassStatus.cancelled => StatusTone.off,
      },
    );
  }
}

/// What an entry history row did to the balance, drawn as the same tiles as
/// the pass entry bar.
enum EntryTileKind {
  /// An entry used for a class: filled faint.
  used,

  /// An entry taken for a booked class: framed in the free-entry colour.
  reserved,

  /// Entries added by a purchase or a correction: filled free-entry colour.
  added,

  /// An entry given back: framed faint and crossed.
  returned,
}

EntryTileKind entryTileKind(PassUsageEntry entry) => switch (entry.kind) {
  'RESERVED' => EntryTileKind.reserved,
  'RETURNED' => EntryTileKind.returned,
  'CORRECTION' when (entry.entriesChange ?? 0) > 0 => EntryTileKind.added,
  _ => EntryTileKind.used,
};

class EntryTile extends StatelessWidget {
  const EntryTile(this.kind, {super.key});

  final EntryTileKind kind;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    return CustomPaint(
      size: Size(ct.sizes.entryTileWidth, ct.sizes.entryTileHeight),
      painter: _TilePainter(
        kind: kind,
        fill: switch (kind) {
          EntryTileKind.used => c.faint,
          EntryTileKind.added => c.freeEntry,
          _ => null,
        },
        frame: switch (kind) {
          EntryTileKind.reserved => c.freeEntry,
          EntryTileKind.returned => c.faint,
          _ => null,
        },
        frameWidth: kind == EntryTileKind.reserved
            ? ct.shape.entryOutline
            : ct.shape.hairline,
      ),
    );
  }
}

class _TilePainter extends CustomPainter {
  const _TilePainter({
    required this.kind,
    required this.fill,
    required this.frame,
    required this.frameWidth,
  });

  final EntryTileKind kind;
  final Color? fill;
  final Color? frame;
  final double frameWidth;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    if (fill case final fill?) canvas.drawRect(rect, Paint()..color = fill);
    if (frame case final frame?) {
      final paint = Paint()
        ..color = frame
        ..style = PaintingStyle.stroke
        ..strokeWidth = frameWidth;
      canvas.drawRect(rect.deflate(frameWidth / 2), paint);
      if (kind == EntryTileKind.returned) {
        canvas.drawLine(rect.bottomLeft, rect.topRight, paint);
      }
    }
  }

  @override
  bool shouldRepaint(_TilePainter old) =>
      old.kind != kind ||
      old.fill != fill ||
      old.frame != frame ||
      old.frameWidth != frameWidth;
}

/// `−1`, `+8`, `±0`, or a dash for an unlimited pass.
String entryChangeLabel(int? change) => switch (change) {
  null => '—',
  0 => '±0',
  > 0 => '+$change',
  _ => '−${change.abs()}',
};

String _kindLabel(AppLocalizations l10n, PassUsageEntry entry) {
  final change = entry.entriesChange;
  return switch (entry.kind) {
    'RESERVED' => l10n.passUsageReserved,
    'CONSUMED' => l10n.passUsageConsumed,
    'RETURNED' => l10n.passUsageReturned,
    'CORRECTION' when change != null => l10n.passUsageEntryCount(change.abs()),
    _ => l10n.passUsageReview,
  };
}

/// One entry of the entry history: tile, date, class and the change of the
/// balance on the right.
class PassEntryRow extends StatelessWidget {
  const PassEntryRow(
    this.entry, {
    super.key,
    this.showPass = false,
    this.showPerson = false,
  });

  final PassUsageEntry entry;

  /// Names the pass under the class (lists of every pass); otherwise the row
  /// says what happened to the entry.
  final bool showPass;
  final bool showPerson;

  static final _day = DateFormat('EEE d.MM', 'pl');
  static final _time = DateFormat('HH:mm', 'pl');

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final start = entry.startsAt;
    final date = start == null
        ? _day.format(entry.occurredAt)
        : '${_day.format(start)}, ${_time.format(start)}';
    final title = entry.kind == 'CORRECTION'
        ? l10n.passUsageCorrection
        : entry.title ?? l10n.passUsageUnknownTraining;
    final details = showPass
        ? [entry.passName, if (showPerson) entry.profileName]
        : [
            _kindLabel(l10n, entry),
            if (entry.cancelled) l10n.passEntryCancelled,
          ];
    return Container(
      padding: EdgeInsets.symmetric(vertical: sp.m),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: EdgeInsets.only(top: sp.xs + sp.xxs),
            child: EntryTile(entryTileKind(entry)),
          ),
          SizedBox(width: sp.m),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(date, style: ct.text.lineLabel),
                SizedBox(height: sp.xxs),
                Text(title, style: ct.text.rowTitle),
                SizedBox(height: sp.xxs),
                Text(details.join(' · '), style: ct.text.meta),
              ],
            ),
          ),
          SizedBox(width: sp.m),
          Text(
            entryChangeLabel(entry.entriesChange),
            style: ct.text.entryChange,
          ),
        ],
      ),
    );
  }
}

/// The entry history for [queryFor], loaded a page at a time with "Pokaż
/// więcej" at the end.
class PassEntryList extends ConsumerStatefulWidget {
  const PassEntryList({
    super.key,
    required this.queryFor,
    required this.emptyText,
    this.showPass = false,
    this.showPerson = false,
  });

  final PassUsageQuery Function(int page) queryFor;
  final String emptyText;
  final bool showPass;
  final bool showPerson;

  @override
  ConsumerState<PassEntryList> createState() => _PassEntryListState();
}

class _PassEntryListState extends ConsumerState<PassEntryList> {
  int _pages = 1;

  @override
  void didUpdateWidget(covariant PassEntryList oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.queryFor(0) != widget.queryFor(0)) _pages = 1;
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final first = ref.watch(passUsageProvider(widget.queryFor(0)));
    return first.when(
      skipLoadingOnRefresh: false,
      loading: () => Padding(
        padding: EdgeInsets.all(ct.spacing.xl),
        child: const Center(child: CircularProgressIndicator()),
      ),
      error: (_, _) => _Retry(
        text: l10n.passUsageLoadError,
        onRetry: () => ref.invalidate(passUsageProvider(widget.queryFor(0))),
      ),
      data: (page) {
        if (page.items.isEmpty) {
          return Padding(
            padding: EdgeInsets.symmetric(vertical: ct.spacing.xl),
            child: Text(
              widget.emptyText,
              textAlign: TextAlign.center,
              style: ct.text.meta,
            ),
          );
        }
        final rows = <Widget>[];
        var more = page.totalPages > 1;
        Widget? tail;
        for (var index = 0; index < _pages; index++) {
          final result = index == 0
              ? first
              : ref.watch(passUsageProvider(widget.queryFor(index)));
          if (result.value case final loaded?) {
            rows.addAll([
              for (final entry in loaded.items)
                PassEntryRow(
                  entry,
                  showPass: widget.showPass,
                  showPerson: widget.showPerson,
                ),
            ]);
            more = loaded.page + 1 < loaded.totalPages;
          } else if (result.hasError) {
            tail = _Retry(
              text: l10n.passUsageLoadError,
              onRetry: () =>
                  ref.invalidate(passUsageProvider(widget.queryFor(index))),
            );
            more = false;
            break;
          } else {
            tail = Padding(
              padding: EdgeInsets.all(ct.spacing.l),
              child: const Center(child: CircularProgressIndicator()),
            );
            more = false;
            break;
          }
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            DecoratedBox(
              decoration: BoxDecoration(border: Border(top: ct.hairline)),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: rows,
              ),
            ),
            ?tail,
            if (more)
              Padding(
                padding: EdgeInsets.only(top: ct.spacing.s),
                child: Center(
                  child: AppButton.ghost(
                    label: l10n.listShowMore,
                    onPressed: () => setState(() => _pages++),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

class _Retry extends StatelessWidget {
  const _Retry({required this.text, required this.onRetry});

  final String text;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.symmetric(vertical: ct.spacing.xl),
      child: Column(
        children: [
          Text(text, textAlign: TextAlign.center, style: ct.text.meta),
          SizedBox(height: ct.spacing.m),
          AppButton(
            label: AppLocalizations.of(context).passesRetryButton,
            small: true,
            onPressed: onRetry,
          ),
        ],
      ),
    );
  }
}

/// The balance line under the title of a pass's entry history.
String passHistorySummary(AppLocalizations l10n, OwnPass pass) {
  final total = pass.entriesTotal;
  final remaining = pass.entriesRemaining;
  final validUntil = pass.validUntil;
  if (pass.status == PassStatus.expired) {
    return [
      if (validUntil != null) l10n.passExpiredOn(formatPassDay(validUntil)),
      if (total != null && remaining != null)
        l10n.passUsedOf(total - remaining, total),
    ].join(' · ');
  }
  return [
    if (total != null && remaining != null)
      '${l10n.passRemainingLead} $remaining ${l10n.passRemainingOf(total)}'
    else
      l10n.passEntriesUnlimited,
    if (validUntil != null) l10n.passValidUntilShort(formatPassDay(validUntil)),
  ].join(' · ');
}

/// Opens the entry history of one pass as a sheet, newest first, with
/// upcoming bookings.
Future<void> showPassEntriesSheet(
  BuildContext context, {
  required String accountId,
  required String profileId,
  required OwnPass pass,
}) {
  final l10n = AppLocalizations.of(context);
  return showAppSheet<void>(
    context,
    builder: (context) => Consumer(
      builder: (context, ref, _) {
        if (passUsageAccount(ref.watch(authControllerProvider)) != accountId) {
          return const SizedBox.shrink();
        }
        final ct = context.ct;
        return AppSheet(
          kicker:
              '${pass.productName} · ${passEntriesShort(l10n, pass.entriesTotal)}',
          title: l10n.passUsageTitle,
          subtitleWidget: Text(
            passHistorySummary(l10n, pass),
            style: ct.text.body.copyWith(color: ct.colors.muted),
          ),
          children: [
            SizedBox(height: ct.spacing.section),
            PassEntryList(
              queryFor: (page) => (
                accountId: accountId,
                profileId: profileId,
                productId: null,
                passId: pass.id,
                from: null,
                to: null,
                includeUpcoming: true,
                page: page,
              ),
              emptyText: l10n.passUsageEmpty,
            ),
          ],
        );
      },
    ),
  );
}
