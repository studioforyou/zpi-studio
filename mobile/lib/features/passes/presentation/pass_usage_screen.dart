import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/pass_usage_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// One account history, also opened with a pass filter from a pass card.
class PassUsageScreen extends ConsumerStatefulWidget {
  const PassUsageScreen({
    super.key,
    required this.accountId,
    this.initialProfileId,
    this.initialPassId,
    this.embedded = false,
    this.onProfileChanged,
  });
  final String accountId;
  final String? initialProfileId;
  final String? initialPassId;
  final bool embedded;
  final ValueChanged<String?>? onProfileChanged;

  @override
  ConsumerState<PassUsageScreen> createState() => _PassUsageScreenState();
}

class _PassUsageScreenState extends ConsumerState<PassUsageScreen>
    with
        WidgetsBindingObserver,
        AutomaticKeepAliveClientMixin<PassUsageScreen> {
  int _page = 0;
  String? _profileId;
  String? _productId;
  String? _passId;
  DateTimeRange? _range;

  @override
  bool get wantKeepAlive => widget.embedded;

  @override
  void didUpdateWidget(covariant PassUsageScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.embedded &&
        widget.initialProfileId != oldWidget.initialProfileId) {
      _profileId = widget.initialProfileId;
      _passId = null;
      _page = 0;
    }
  }

  PassUsageQuery _queryFor(int page) => (
    accountId: widget.accountId,
    profileId: _profileId,
    productId: _productId,
    passId: _passId,
    from: _range?.start.toUtc().toIso8601String(),
    to: _range == null
        ? null
        : DateTime(
            _range!.end.year,
            _range!.end.month,
            _range!.end.day + 1,
          ).toUtc().toIso8601String(),
    page: page,
  );
  PassUsageQuery get _query => _queryFor(_page);
  bool get _sameAccount =>
      passUsageAccount(ref.read(authControllerProvider)) == widget.accountId;
  bool get _filtered =>
      _profileId != null ||
      _productId != null ||
      _passId != null ||
      _range != null;

  @override
  void initState() {
    super.initState();
    _profileId = widget.initialProfileId;
    _passId = widget.initialPassId;
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _sameAccount) _refresh();
  }

  Future<void> _refresh() async {
    if (!_sameAccount) return;
    final first = _queryFor(0);
    ref.invalidate(passUsageProvider(first));
    setState(() => _page = 0);
    await WidgetsBinding.instance.endOfFrame;
    if (!mounted || !_sameAccount) return;
    try {
      await ref.read(passUsageProvider(first).future);
    } catch (_) {
      // Retry and clearing filters are available on the screen.
    }
  }

  void _change(VoidCallback update) => setState(() {
    update();
    _page = 0;
  });

  void _reset() => _change(() {
    _profileId = null;
    _productId = null;
    _passId = null;
    _range = null;
    widget.onProfileChanged?.call(null);
  });

  Future<void> _choose({
    required String title,
    required String all,
    required List<PassUsageOption> options,
    required String? selected,
    required ValueChanged<String?> onChanged,
  }) async {
    final choice = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => Consumer(
        builder: (context, ref, _) =>
            passUsageAccount(ref.watch(authControllerProvider)) !=
                widget.accountId
            ? const SizedBox.shrink()
            : SafeArea(
                child: ConstrainedBox(
                  constraints: BoxConstraints(
                    maxHeight: MediaQuery.sizeOf(context).height * .7,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Padding(
                        padding: const EdgeInsets.all(16),
                        child: Text(
                          title,
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                      ),
                      Flexible(
                        child: ListView(
                          shrinkWrap: true,
                          children: [
                            for (final option in [
                              PassUsageOption(id: '', name: all),
                              ...options,
                            ])
                              ListTile(
                                title: Text(option.name),
                                trailing: option.id == (selected ?? '')
                                    ? const Icon(Icons.check)
                                    : null,
                                onTap: () =>
                                    Navigator.of(context).pop(option.id),
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
      ),
    );
    if (choice != null && mounted && _sameAccount) {
      onChanged(choice.isEmpty ? null : choice);
    }
  }

  Future<void> _choosePeriod() async {
    final l10n = AppLocalizations.of(context);
    final choice = await showModalBottomSheet<int>(
      context: context,
      showDragHandle: true,
      builder: (context) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final (index, label) in [
              (0, l10n.passUsageAllTime),
              (1, l10n.passUsageLast30),
              (2, l10n.passUsageThisMonth),
              (3, l10n.passUsageChooseDates),
            ])
              ListTile(
                title: Text(label),
                onTap: () => Navigator.of(context).pop(index),
              ),
          ],
        ),
      ),
    );
    if (!mounted || !_sameAccount || choice == null) return;
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final range = switch (choice) {
      0 => null,
      1 => DateTimeRange(
        start: DateTime(now.year, now.month, now.day - 29),
        end: today,
      ),
      2 => DateTimeRange(start: DateTime(now.year, now.month), end: today),
      _ => await showDateRangePicker(
        context: context,
        firstDate: DateTime(2000),
        lastDate: DateTime(now.year + 5, 12, 31),
        initialDateRange: _range,
      ),
    };
    if (!mounted || !_sameAccount || (choice == 3 && range == null)) return;
    _change(() => _range = range);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final l10n = AppLocalizations.of(context);
    final valid =
        passUsageAccount(ref.watch(authControllerProvider)) == widget.accountId;
    return Scaffold(
      appBar: widget.embedded
          ? null
          : AppBar(
              title: Text(l10n.passUsageTitle),
              actions: [
                IconButton(
                  tooltip: l10n.passUsageDateInfo,
                  icon: const Icon(Icons.info_outline),
                  onPressed: () => showModalBottomSheet<void>(
                    context: context,
                    showDragHandle: true,
                    builder: (context) => SafeArea(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                        child: Text(l10n.passUsageDateInfo),
                      ),
                    ),
                  ),
                ),
              ],
            ),
      body: !valid
          ? Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(l10n.passUsageAccountChanged),
              ),
            )
          : ref
                .watch(passUsageProvider(_query))
                .when(
                  skipLoadingOnRefresh: false,
                  skipLoadingOnReload: false,
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (_, _) => Center(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        spacing: 12,
                        children: [
                          Text(
                            l10n.passUsageLoadError,
                            textAlign: TextAlign.center,
                          ),
                          OutlinedButton(
                            onPressed: () =>
                                ref.invalidate(passUsageProvider(_query)),
                            child: Text(l10n.traineesRetry),
                          ),
                          if (_filtered)
                            TextButton(
                              onPressed: _reset,
                              child: Text(l10n.passUsageReset),
                            ),
                        ],
                      ),
                    ),
                  ),
                  data: (result) => LayoutBuilder(
                    builder: (context, constraints) {
                      final wide = constraints.maxWidth >= 760;
                      return RefreshIndicator(
                        onRefresh: _refresh,
                        child: ListView(
                          physics: const AlwaysScrollableScrollPhysics(),
                          padding: const EdgeInsets.all(16),
                          children: [
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: [
                                if (!widget.embedded)
                                  _filter(
                                    Icons.person_outline,
                                    result.participants
                                            .where((p) => p.id == _profileId)
                                            .firstOrNull
                                            ?.name ??
                                        l10n.passUsageAllPeople,
                                    _profileId != null,
                                    () => _choose(
                                      title: l10n.passUsagePerson,
                                      all: l10n.passUsageAllPeople,
                                      options: result.participants,
                                      selected: _profileId,
                                      onChanged: (value) => _change(() {
                                        _profileId = value;
                                        _passId = null;
                                      }),
                                    ),
                                  ),
                                _filter(
                                  Icons.confirmation_number_outlined,
                                  result.products
                                          .where((p) => p.id == _productId)
                                          .firstOrNull
                                          ?.name ??
                                      l10n.passUsageAllProducts,
                                  _productId != null,
                                  () => _choose(
                                    title: l10n.passUsageProduct,
                                    all: l10n.passUsageAllProducts,
                                    options: result.products,
                                    selected: _productId,
                                    onChanged: (value) => _change(() {
                                      _productId = value;
                                      _passId = null;
                                    }),
                                  ),
                                ),
                                _filter(
                                  Icons.date_range_outlined,
                                  _range == null
                                      ? l10n.passUsagePeriod
                                      : '${DateFormat('d MMM', 'pl').format(_range!.start)} – ${DateFormat('d MMM yyyy', 'pl').format(_range!.end)}',
                                  _range != null,
                                  _choosePeriod,
                                ),
                                if (result.selectedPassName != null)
                                  InputChip(
                                    label: Text(result.selectedPassName!),
                                    onDeleted: () =>
                                        _change(() => _passId = null),
                                  ),
                              ],
                            ),
                            Padding(
                              padding: const EdgeInsets.symmetric(vertical: 12),
                              child: Row(
                                children: [
                                  Expanded(
                                    child: Text(
                                      l10n.passUsageTotal(result.totalItems),
                                      style: Theme.of(
                                        context,
                                      ).textTheme.bodySmall,
                                    ),
                                  ),
                                  if (_filtered)
                                    TextButton(
                                      onPressed: _reset,
                                      child: Text(l10n.passUsageReset),
                                    ),
                                ],
                              ),
                            ),
                            if (result.items.isEmpty)
                              Padding(
                                padding: const EdgeInsets.symmetric(
                                  vertical: 24,
                                ),
                                child: Text(
                                  _filtered
                                      ? l10n.passUsageNoMatches
                                      : l10n.passUsageEmpty,
                                ),
                              ),
                            if (wide && result.items.isNotEmpty)
                              _HistoryTable(result.items)
                            else
                              for (
                                var index = 0;
                                index < result.items.length;
                                index++
                              ) ...[
                                if (index == 0 ||
                                    !_sameDay(
                                      result.items[index - 1].occurredAt,
                                      result.items[index].occurredAt,
                                    ))
                                  Padding(
                                    padding: const EdgeInsets.fromLTRB(
                                      0,
                                      12,
                                      0,
                                      6,
                                    ),
                                    child: Text(
                                      DateFormat(
                                        'd MMMM yyyy',
                                        'pl',
                                      ).format(result.items[index].occurredAt),
                                      style: Theme.of(
                                        context,
                                      ).textTheme.titleSmall,
                                    ),
                                  ),
                                _HistoryRow(result.items[index]),
                                const Divider(height: 1),
                              ],
                            if (result.totalPages > 1 || _page > 0)
                              Wrap(
                                alignment: WrapAlignment.spaceBetween,
                                crossAxisAlignment: WrapCrossAlignment.center,
                                spacing: 12,
                                children: [
                                  TextButton(
                                    onPressed: _page > 0
                                        ? () => setState(() => _page--)
                                        : null,
                                    child: Text(l10n.makeupPrevious),
                                  ),
                                  Text(
                                    l10n.makeupPage(
                                      result.page + 1,
                                      result.totalPages,
                                    ),
                                  ),
                                  TextButton(
                                    onPressed: _page + 1 < result.totalPages
                                        ? () => setState(() => _page++)
                                        : null,
                                    child: Text(l10n.makeupNext),
                                  ),
                                ],
                              ),
                          ],
                        ),
                      );
                    },
                  ),
                ),
    );
  }

  Widget _filter(
    IconData icon,
    String label,
    bool selected,
    VoidCallback onTap,
  ) => InputChip(
    avatar: Icon(icon, size: 18),
    label: Text(label),
    selected: selected,
    showCheckmark: false,
    onPressed: onTap,
  );
}

bool _sameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;

class _HistoryRow extends StatelessWidget {
  const _HistoryRow(this.entry);
  final PassUsageEntry entry;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 44,
          child: Text(
            DateFormat('HH:mm').format(entry.occurredAt),
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            spacing: 4,
            children: [
              Wrap(
                spacing: 12,
                runSpacing: 4,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    entry.profileName,
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                  _StatusBadge(entry),
                ],
              ),
              _EntryDescription(entry),
            ],
          ),
        ),
      ],
    ),
  );
}

class _EntryDescription extends StatelessWidget {
  const _EntryDescription(this.entry);
  final PassUsageEntry entry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final start = entry.startsAt;
    final end = entry.endsAt;
    final trainingDate = start == null
        ? null
        : '${DateFormat('d MMM yyyy · HH:mm', 'pl').format(start)}'
              '${end == null ? '' : '–${DateFormat(_sameDay(start, end) ? 'HH:mm' : 'd MMM · HH:mm', 'pl').format(end)}'}';
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 2,
      children: [
        Text(
          entry.kind == 'CORRECTION'
              ? l10n.passUsageCorrection
              : entry.title ?? l10n.passUsageUnknownTraining,
          style: Theme.of(
            context,
          ).textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w600),
        ),
        Text(entry.passName, style: Theme.of(context).textTheme.bodySmall),
        if (trainingDate != null)
          Text(
            l10n.passUsageTrainingOn(trainingDate),
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        if (entry.cancelled)
          Text(
            l10n.eventStatusCancelled,
            style: Theme.of(context).textTheme.labelSmall,
          ),
      ],
    );
  }
}

class _HistoryTable extends StatelessWidget {
  const _HistoryTable(this.entries);
  final List<PassUsageEntry> entries;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    Widget cell(Widget child) =>
        Padding(padding: const EdgeInsets.all(10), child: child);
    return Table(
      columnWidths: const {
        0: FixedColumnWidth(116),
        1: FlexColumnWidth(),
        2: FlexColumnWidth(2),
        3: FixedColumnWidth(148),
      },
      defaultVerticalAlignment: TableCellVerticalAlignment.top,
      border: TableBorder(
        horizontalInside: BorderSide(color: Theme.of(context).dividerColor),
      ),
      children: [
        TableRow(
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.surfaceContainer,
          ),
          children: [
            for (final label in [
              l10n.passUsageDateColumn,
              l10n.passUsagePerson,
              l10n.passUsageDetailsColumn,
              l10n.passUsageStatusColumn,
            ])
              cell(Text(label, style: Theme.of(context).textTheme.labelLarge)),
          ],
        ),
        for (final entry in entries)
          TableRow(
            children: [
              cell(
                Text(
                  DateFormat(
                    'd MMM yyyy\nHH:mm',
                    'pl',
                  ).format(entry.occurredAt),
                ),
              ),
              cell(Text(entry.profileName)),
              cell(_EntryDescription(entry)),
              cell(_StatusBadge(entry)),
            ],
          ),
      ],
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge(this.entry);
  final PassUsageEntry entry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final delta = entry.entriesChange;
    final (label, color, icon) = switch (entry.kind) {
      'RESERVED' => (
        l10n.passUsageReserved,
        Colors.indigo,
        Icons.event_outlined,
      ),
      'CONSUMED' => (
        l10n.passUsageConsumed,
        Colors.teal,
        Icons.check_circle_outline,
      ),
      'RETURNED' => (l10n.passUsageReturned, Colors.green, Icons.undo),
      'CORRECTION' when delta != null => (
        (delta > 0
                ? '+'
                : delta < 0
                ? '−'
                : '') +
            l10n.passUsageEntryCount(delta.abs()),
        delta >= 0 ? Colors.green : Colors.deepOrange,
        Icons.tune,
      ),
      _ => (l10n.passUsageReview, Colors.deepOrange, Icons.error_outline),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: .09),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 15, color: color),
          const SizedBox(width: 4),
          Flexible(
            child: Text(
              label,
              style: Theme.of(
                context,
              ).textTheme.labelSmall?.copyWith(color: color),
            ),
          ),
        ],
      ),
    );
  }
}
