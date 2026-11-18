import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/schedule/data/time_off_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class TimeOffSubstitutionSelection extends ConsumerStatefulWidget {
  const TimeOffSubstitutionSelection({
    super.key,
    required this.accountId,
    required this.from,
    required this.to,
    this.timeOffId,
    required this.enabled,
    required this.onChanged,
  });
  final String accountId;
  final DateTime from, to;
  final String? timeOffId;
  final bool enabled;
  final void Function(List<String>? ids, int? version) onChanged;
  @override
  ConsumerState<TimeOffSubstitutionSelection> createState() =>
      _SelectionState();
}

class _SelectionState extends ConsumerState<TimeOffSubstitutionSelection> {
  final _choices = <String, bool>{};
  TimeOffSubstitutionPlan? _lastPlan;
  TimeOffOptionsQuery get _query => (
    accountId: widget.accountId,
    from: widget.from,
    to: widget.to,
    timeOffId: widget.timeOffId,
  );

  @override
  void didUpdateWidget(covariant TimeOffSubstitutionSelection oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.accountId != widget.accountId ||
        oldWidget.timeOffId != widget.timeOffId) {
      _choices.clear();
    }
    if (oldWidget.from != widget.from ||
        oldWidget.to != widget.to ||
        oldWidget.accountId != widget.accountId) {
      _lastPlan = null;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onChanged(null, null);
      });
    }
  }

  List<String> _selected(TimeOffSubstitutionPlan plan) => [
    for (final item in plan.items)
      if (item.selectable && (_choices[item.eventId] ?? item.selected))
        item.eventId,
  ];

  void _change(TimeOffSubstitutionPlan plan, String? id, bool value) {
    setState(() {
      for (final item in plan.items) {
        if (item.selectable && (id == null || item.eventId == id)) {
          _choices[item.eventId] = value;
        }
      }
    });
    widget.onChanged(_selected(plan), plan.version);
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final data = ref.watch(timeOffSubstitutionOptionsProvider(_query));
    final plan = data.isLoading || data.hasError ? null : data.asData?.value;
    if (plan == null && _lastPlan != null) {
      _lastPlan = null;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) widget.onChanged(null, null);
      });
    }
    if (plan != null && !identical(plan, _lastPlan)) {
      _lastPlan = plan;
      final query = _query;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _query == query) {
          widget.onChanged(_selected(plan), plan.version);
        }
      });
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: Text(
                l.timeOffCoverTitle,
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
            if (plan != null)
              Text(
                '${_selected(plan).length}/${plan.items.where((i) => i.selectable).length}',
              ),
            IconButton(
              tooltip: l.timeOffCoverTitle,
              icon: const Icon(Icons.info_outline, size: 20),
              onPressed: () => showDialog<void>(
                context: context,
                builder: (context) => AlertDialog(
                  title: Text(l.timeOffCoverTitle),
                  content: Text(l.timeOffCoverInfo),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: Text(l.substitutionClose),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
        data.when(
          skipLoadingOnRefresh: false,
          loading: () => const LinearProgressIndicator(),
          error: (_, _) => Column(
            children: [
              Text(l.timeOffCoverError),
              TextButton.icon(
                onPressed: () =>
                    ref.invalidate(timeOffSubstitutionOptionsProvider(_query)),
                icon: const Icon(Icons.refresh),
                label: Text(l.scheduleRetryButton),
              ),
            ],
          ),
          data: (plan) {
            final items = plan.items
                .where(
                  (i) =>
                      i.selectable ||
                      [
                        'OPEN',
                        'CLAIMED',
                        'APPROVED',
                        'MANUAL',
                      ].contains(i.status),
                )
                .toList();
            if (items.isEmpty) {
              return Text(
                l.timeOffCoverEmpty,
                style: Theme.of(context).textTheme.bodySmall,
              );
            }
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (items.any(
                  (i) =>
                      i.status == 'MANUAL' ||
                      (i.selectable && !_selected(plan).contains(i.eventId)),
                ))
                  Text(
                    l.timeOffCoverManualWarning,
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: Colors.orange.shade900,
                    ),
                  ),
                Wrap(
                  spacing: 8,
                  children: [
                    TextButton(
                      onPressed: widget.enabled
                          ? () => _change(plan, null, true)
                          : null,
                      child: Text(l.timeOffCoverAll),
                    ),
                    TextButton(
                      onPressed: widget.enabled
                          ? () => _change(plan, null, false)
                          : null,
                      child: Text(l.timeOffCoverNone),
                    ),
                  ],
                ),
                for (final item in items)
                  CheckboxListTile(
                    contentPadding: EdgeInsets.zero,
                    controlAffinity: ListTileControlAffinity.leading,
                    dense: true,
                    value:
                        item.selectable &&
                        (_choices[item.eventId] ?? item.selected),
                    onChanged: widget.enabled && item.selectable
                        ? (v) => _change(plan, item.eventId, v!)
                        : null,
                    title: Text(
                      _when(item),
                      style: const TextStyle(fontWeight: FontWeight.w600),
                    ),
                    subtitle: Text(
                      '${item.title} · ${item.rooms}${item.selectable ? '' : '\n${item.status == 'MANUAL' ? l.timeOffCoverManual : l.timeOffCoverAlready}'}',
                    ),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }
}

String _when(TimeOffLesson item) =>
    '${DateFormat('EEE, d.MM', 'pl').format(item.startsAt)} · ${DateFormat('HH:mm').format(item.startsAt)}–${DateFormat('HH:mm').format(item.endsAt)}';

class TimeOffSubstitutionSummary extends ConsumerWidget {
  const TimeOffSubstitutionSummary({super.key, required this.timeOffId});
  final String timeOffId;
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final auth = ref.watch(authControllerProvider);
    if (auth is! Authenticated) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    final query = (accountId: auth.session.accountId, id: timeOffId);
    final data = ref.watch(timeOffSubstitutionPlanProvider(query));
    return data.when(
      loading: () => const LinearProgressIndicator(),
      error: (_, _) => TextButton.icon(
        onPressed: () => ref.invalidate(timeOffSubstitutionPlanProvider(query)),
        icon: const Icon(Icons.refresh),
        label: Text(l.timeOffCoverError),
      ),
      data: (plan) {
        final items = plan.items
            .where(
              (i) =>
                  i.selected ||
                  ['OPEN', 'CLAIMED', 'APPROVED', 'MANUAL'].contains(i.status),
            )
            .toList();
        if (items.isEmpty) return const SizedBox.shrink();
        return ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text('${l.timeOffCoverTitle} (${items.length})'),
          children: [
            for (final item in items)
              ListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                title: Text(_when(item)),
                subtitle: Text(item.title),
                trailing: SizedBox(
                  width: 110,
                  child: Text(switch (item.status) {
                    'OPEN' => l.timeOffCoverOpen,
                    'CLAIMED' => l.timeOffCoverClaimed,
                    'APPROVED' => l.timeOffCoverDone,
                    'CLOSED' => l.timeOffCoverClosed,
                    'MANUAL' => l.timeOffCoverManual,
                    _ => l.timeOffCoverWaiting,
                  }, style: Theme.of(context).textTheme.labelSmall),
                ),
              ),
          ],
        );
      },
    );
  }
}
