import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/presentation/makeup_booking_screen.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// The orange strip above the passes when the person has classes to make up
/// (#72). "Dowiedz się więcej" opens the list of make-ups. Nothing shows
/// without make-ups or while they load.
class MakeupStrip extends ConsumerStatefulWidget {
  const MakeupStrip({super.key, required this.scope});

  final MakeupScope scope;

  @override
  ConsumerState<MakeupStrip> createState() => _MakeupStripState();
}

class _MakeupStripState extends ConsumerState<MakeupStrip>
    with WidgetsBindingObserver {
  Timer? _expiry;

  MakeupQuery get _first => (scope: widget.scope, page: 0);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _expiry?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  void _refresh() {
    _expiry?.cancel();
    ref.invalidate(makeupCreditsProvider);
  }

  /// Refreshes when the first make-up expires while the screen stays open.
  void _refreshAtExpiry(MakeupCreditPage result) {
    _expiry?.cancel();
    if (result.items.isEmpty) return;
    final soonest = result.items
        .map((credit) => credit.expiresAt)
        .reduce((a, b) => a.isBefore(b) ? a : b);
    final delay = soonest.difference(DateTime.now());
    // Avoid a tight retry loop if the device clock is ahead of the server.
    _expiry = Timer(
      delay.isNegative
          ? const Duration(minutes: 1)
          : delay + const Duration(seconds: 1),
      _refresh,
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final result = ref.watch(makeupCreditsProvider(_first)).value;
    if (result == null) return const SizedBox.shrink();
    _refreshAtExpiry(result);
    if (result.totalItems == 0) return const SizedBox.shrink();
    return InfoStrip(
      text: l10n.makeupStrip(result.totalItems),
      actionLabel: l10n.makeupLearnMore,
      onAction: () => showMakeupSheet(context, scope: widget.scope),
    );
  }
}

/// The make-ups of one person ("Do odrobienia"), each with "Wybierz nowe
/// zajęcia" that opens the make-up mode.
Future<void> showMakeupSheet(
  BuildContext context, {
  required MakeupScope scope,
}) {
  final navigator = Navigator.of(context);
  return showAppSheet<void>(
    context,
    builder: (sheetContext) => _MakeupSheet(
      scope: scope,
      onChoose: (credit) {
        Navigator.of(sheetContext).pop();
        navigator.push<void>(
          MaterialPageRoute(
            builder: (_) =>
                MakeupBookingScreen(scope: scope, creditId: credit.id),
          ),
        );
      },
    ),
  );
}

class _MakeupSheet extends ConsumerStatefulWidget {
  const _MakeupSheet({required this.scope, required this.onChoose});

  final MakeupScope scope;
  final ValueChanged<MakeupCredit> onChoose;

  @override
  ConsumerState<_MakeupSheet> createState() => _MakeupSheetState();
}

class _MakeupSheetState extends ConsumerState<_MakeupSheet> {
  int _pages = 1;

  MakeupQuery _query(int page) => (scope: widget.scope, page: page);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final current = isMakeupScopeCurrent(
      ref.watch(authControllerProvider),
      widget.scope,
    );
    if (!current) {
      return AppSheet(
        title: l10n.classesProfileChanged,
        titleSmall: true,
        children: const [],
      );
    }
    final first = ref.watch(makeupCreditsProvider(_query(0)));
    final credits = <MakeupCredit>[];
    var more = false;
    var loading = first.isLoading && !first.hasValue;
    var failed = first.hasError && !first.hasValue;
    for (var page = 0; page < _pages && !loading && !failed; page++) {
      final result = page == 0
          ? first
          : ref.watch(makeupCreditsProvider(_query(page)));
      if (result.value case final loaded?) {
        credits.addAll(loaded.items);
        more = loaded.page + 1 < loaded.totalPages;
      } else {
        loading = !result.hasError;
        failed = result.hasError;
      }
    }
    return AppSheet(
      kicker: l10n.passesTabMine,
      title: l10n.passesTabMakeup,
      titleSmall: true,
      children: [
        SizedBox(height: sp.l),
        DecoratedBox(
          decoration: BoxDecoration(border: Border(top: ct.hairline)),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final credit in credits)
                _CreditItem(
                  credit: credit,
                  onChoose: () => widget.onChoose(credit),
                ),
            ],
          ),
        ),
        if (loading)
          Padding(
            padding: EdgeInsets.all(sp.l),
            child: const Center(child: CircularProgressIndicator()),
          ),
        if (failed) ...[
          SizedBox(height: sp.l),
          Text(l10n.makeupLoadError, style: ct.text.meta),
          SizedBox(height: sp.s),
          Align(
            alignment: Alignment.centerLeft,
            child: AppButton(
              label: l10n.passesRetryButton,
              small: true,
              onPressed: () => ref.invalidate(makeupCreditsProvider),
            ),
          ),
        ],
        if (more)
          Center(
            child: AppButton.ghost(
              label: l10n.listShowMore,
              onPressed: () => setState(() => _pages++),
            ),
          ),
        SizedBox(height: sp.l),
        SheetHint(l10n.makeupInfoBody),
      ],
    );
  }
}

class _CreditItem extends StatelessWidget {
  const _CreditItem({required this.credit, required this.onChoose});

  final MakeupCredit credit;
  final VoidCallback onChoose;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    return Container(
      padding: EdgeInsets.only(top: sp.l - sp.xxs, bottom: sp.l),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(credit.productName, style: ct.text.pickerTitle),
          SizedBox(height: sp.xxs + 1),
          Text(
            l10n.makeupUseUntil(formatPassDay(credit.expiresAt)),
            style: ct.text.meta,
          ),
          SizedBox(height: sp.m + sp.xxs),
          AppButton(
            label: l10n.makeupChooseNew,
            small: true,
            onPressed: onChoose,
          ),
        ],
      ),
    );
  }
}
