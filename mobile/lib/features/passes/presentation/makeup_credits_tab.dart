import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/data/makeup_booking_api.dart';
import 'package:mobile/features/passes/presentation/makeup_booking_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class MakeupCreditsTab extends ConsumerWidget {
  const MakeupCreditsTab({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;
    if (session == null || profileId == null) {
      return Center(child: Text(AppLocalizations.of(context).passesNoProfile));
    }
    final scope = (accountId: session.accountId, profileId: profileId);
    final name = session.profiles
        .where((profile) => profile.id == profileId)
        .firstOrNull
        ?.displayName;
    return _CreditsList(key: ValueKey(scope), scope: scope, profileName: name);
  }
}

class _CreditsList extends ConsumerStatefulWidget {
  const _CreditsList({super.key, required this.scope, this.profileName});

  final MakeupScope scope;
  final String? profileName;

  @override
  ConsumerState<_CreditsList> createState() => _CreditsListState();
}

class _CreditsListState extends ConsumerState<_CreditsList>
    with WidgetsBindingObserver {
  int _page = 0;
  Timer? _expiry;

  MakeupQuery get _query => (scope: widget.scope, page: _page);

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

  Future<void> _refresh() async {
    _expiry?.cancel();
    // Returning to page one also handles credits removed from later pages.
    final first = (scope: widget.scope, page: 0);
    ref.invalidate(makeupCreditsProvider(_query));
    if (_page != 0) ref.invalidate(makeupCreditsProvider(first));
    setState(() => _page = 0);
    try {
      await ref.read(makeupCreditsProvider(first).future);
    } catch (_) {
      // The provider renders the error and keeps retry available.
    }
  }

  void _refreshAtExpiry(MakeupCreditPage result) {
    _expiry?.cancel();
    if (result.items.isEmpty) return;
    final delay = result.items.first.expiresAt.difference(DateTime.now());
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
    return ref
        .watch(makeupCreditsProvider(_query))
        .when(
          skipLoadingOnRefresh: false,
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                spacing: 16,
                children: [
                  Text(l10n.makeupLoadError, textAlign: TextAlign.center),
                  OutlinedButton(
                    onPressed: _refresh,
                    child: Text(l10n.passesRetryButton),
                  ),
                ],
              ),
            ),
          ),
          data: (result) {
            _refreshAtExpiry(result);
            return RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                children: [
                  _CreditsSummary(
                    count: result.totalItems,
                    profileName: widget.profileName,
                  ),
                  const SizedBox(height: 12),
                  for (final credit in result.items)
                    _MakeupCreditCard(
                      credit: credit,
                      onBook: () {
                        if (makeupBookingScope(
                              ref.read(authControllerProvider),
                            ) !=
                            widget.scope) {
                          return;
                        }
                        Navigator.of(context).push<void>(
                          MaterialPageRoute(
                            builder: (_) => MakeupBookingScreen(
                              scope: widget.scope,
                              creditId: credit.id,
                            ),
                          ),
                        );
                      },
                    ),
                  if (result.totalPages > 1 || _page > 0)
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        TextButton(
                          onPressed: _page > 0
                              ? () => setState(() => _page--)
                              : null,
                          child: Text(l10n.makeupPrevious),
                        ),
                        Text(
                          l10n.makeupPage(result.page + 1, result.totalPages),
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
        );
  }
}

class _CreditsSummary extends StatelessWidget {
  const _CreditsSummary({required this.count, this.profileName});

  final int count;
  final String? profileName;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    return Card.filled(
      margin: EdgeInsets.zero,
      color: theme.colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 8, 16),
        child: Row(
          children: [
            Text(
              '$count',
              style: theme.textTheme.displayMedium?.copyWith(
                fontWeight: FontWeight.w600,
                color: theme.colorScheme.onPrimaryContainer,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 4,
                children: [
                  Text(
                    l10n.makeupAvailable(count),
                    style: theme.textTheme.titleSmall?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer,
                    ),
                  ),
                  if (profileName != null)
                    Text(
                      profileName!,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: theme.colorScheme.onPrimaryContainer,
                      ),
                    ),
                ],
              ),
            ),
            IconButton(
              tooltip: l10n.makeupInfoTitle,
              color: Colors.blue.shade700,
              icon: const Icon(Icons.info_outline_rounded),
              onPressed: () => _showMakeupInfo(context),
            ),
          ],
        ),
      ),
    );
  }
}

class _MakeupCreditCard extends StatelessWidget {
  const _MakeupCreditCard({required this.credit, required this.onBook});

  final MakeupCredit credit;
  final VoidCallback onBook;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final l10n = AppLocalizations.of(context);
    return Card.outlined(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(Icons.event_repeat_outlined, color: theme.colorScheme.primary),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 4,
                children: [
                  Text(
                    l10n.makeupExpiresAt(
                      DateFormat('dd.MM.yyyy, HH:mm').format(credit.expiresAt),
                    ),
                    style: theme.textTheme.titleSmall,
                  ),
                  Text(
                    credit.productName,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 4),
                  TextButton.icon(
                    onPressed: onBook,
                    icon: const Icon(Icons.event_repeat_outlined, size: 18),
                    label: Text(l10n.makeupChooseClass),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

Future<void> _showMakeupInfo(BuildContext context) {
  final l10n = AppLocalizations.of(context);
  return showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    isScrollControlled: true,
    builder: (context) => SafeArea(
      child: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(24, 0, 16, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 16,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    l10n.makeupInfoTitle,
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ),
                IconButton(
                  tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                  onPressed: () => Navigator.of(context).pop(),
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            Text(l10n.makeupInfoBody),
          ],
        ),
      ),
    ),
  );
}
