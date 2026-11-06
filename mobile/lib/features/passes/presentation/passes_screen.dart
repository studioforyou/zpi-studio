import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/makeup_credits_tab.dart';
import 'package:mobile/features/passes/presentation/pass_checkout_screen.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The client's passes: those of the active customer profile and the studio's
/// offer.
class PassesScreen extends StatelessWidget {
  const PassesScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return DefaultTabController(
      length: 3,
      child: Scaffold(
        appBar: AppBar(
          title: Text(l10n.navPasses),
          bottom: TabBar(
            tabs: [
              Tab(text: l10n.passesTabMine),
              Tab(text: l10n.passesTabMakeup),
              Tab(text: l10n.passesTabOffer),
            ],
          ),
        ),
        body: const TabBarView(
          children: [_OwnPassesTab(), MakeupCreditsTab(), _OfferTab()],
        ),
      ),
    );
  }
}

class _OwnPassesTab extends ConsumerWidget {
  const _OwnPassesTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;
    if (session == null || profileId == null) {
      return _Message(l10n.passesNoProfile);
    }
    final provider = ownPassesProvider(profileId);

    return ref
        .watch(provider)
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) => _LoadError(onRetry: () => ref.invalidate(provider)),
          data: (passes) {
            final current = [
              for (final pass in passes)
                if (pass.status == PassStatus.active ||
                    pass.status == PassStatus.suspended)
                  pass,
            ];
            final upcoming = [
              for (final pass in passes)
                if (pass.status == PassStatus.scheduled ||
                    pass.status == PassStatus.pendingPayment)
                  pass,
            ];
            final expired = [
              for (final pass in passes)
                if (pass.status == PassStatus.expired) pass,
            ];
            final profileName = _profileName(session, profileId);
            Widget card(OwnPass pass) => _OwnPassCard(
              pass,
              onBuyNext:
                  pass.variantId == null ||
                      pass.status == PassStatus.pendingPayment
                  ? null
                  : () => _buyNext(context, ref, pass, profileId, profileName),
            );

            return RefreshIndicator(
              onRefresh: () => ref.refresh(provider.future),
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  if (profileName != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Text(l10n.passesProfile(profileName)),
                    ),
                  if (passes.isEmpty) _Message(l10n.passesEmpty),
                  if (current.isNotEmpty) ...[
                    _SectionTitle(l10n.passesSectionCurrent),
                    for (final pass in current) card(pass),
                  ],
                  if (upcoming.isNotEmpty) ...[
                    _SectionTitle(l10n.passesSectionUpcoming),
                    for (final pass in upcoming) card(pass),
                  ],
                  if (expired.isNotEmpty)
                    ExpansionTile(
                      title: Text(l10n.passesSectionExpired(expired.length)),
                      tilePadding: EdgeInsets.zero,
                      children: [for (final pass in expired) card(pass)],
                    ),
                ],
              ),
            );
          },
        );
  }
}

/// The active profile's name, when the account acts for more than one.
String? _profileName(Session session, String profileId) {
  return session.profiles.length > 1
      ? session.profiles
            .where((p) => p.id == profileId)
            .firstOrNull
            ?.displayName
      : null;
}

/// Opens the purchase of the same variant as [pass], if it is still on sale.
Future<void> _buyNext(
  BuildContext context,
  WidgetRef ref,
  OwnPass pass,
  String profileId,
  String? profileName,
) async {
  final l10n = AppLocalizations.of(context);
  final messenger = ScaffoldMessenger.of(context);
  final navigator = Navigator.of(context);
  final PassOffer offer;
  try {
    offer = await ref.read(passesApiProvider).fetchOffer();
  } on Exception {
    messenger.showSnackBar(SnackBar(content: Text(l10n.passesLoadError)));
    return;
  }
  for (final product in offer.products) {
    for (final variant in product.variants) {
      if (variant.id == pass.variantId) {
        await navigator.push<PassOrder>(
          MaterialPageRoute(
            builder: (_) => PassCheckoutScreen(
              profileId: profileId,
              profileName: profileName,
              product: product,
              variant: variant,
              renewedPass: pass,
            ),
          ),
        );
        return;
      }
    }
  }
  messenger.showSnackBar(SnackBar(content: Text(l10n.passBuyNextUnavailable)));
}

class _OwnPassCard extends StatelessWidget {
  const _OwnPassCard(this.pass, {this.onBuyNext});

  final OwnPass pass;

  /// Buys the same variant again; `null` when the pass has no variant or
  /// still waits for its payment.
  final VoidCallback? onBuyNext;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    final remaining = pass.entriesRemaining;
    final total = pass.entriesTotal;
    final validFrom = pass.validFrom;
    final validUntil = pass.validUntil;
    final colors = Theme.of(context).colorScheme;
    final isRunning =
        pass.status == PassStatus.active || pass.status == PassStatus.suspended;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 4,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(pass.productName, style: textTheme.titleMedium),
                ),
                Chip(label: Text(_statusLabel(l10n, pass.status))),
              ],
            ),
            Text(
              remaining == null || total == null
                  ? l10n.passEntriesUnlimited
                  : remaining == 0
                  ? l10n.passEntriesUsedUp
                  : l10n.passEntriesLeft(remaining, total),
              style: textTheme.bodyLarge,
            ),
            if (validUntil != null)
              Text(
                validFrom != null && pass.status == PassStatus.scheduled
                    ? l10n.passValidFromUntil(
                        formatPassDate(validFrom),
                        formatPassDate(validUntil),
                      )
                    : l10n.passValidUntil(formatPassDate(validUntil)),
              ),
            if (validUntil != null && isRunning)
              Text(
                l10n.passDaysLeft(_daysLeft(validUntil)),
                style: textTheme.bodySmall,
              ),
            if (pass.expiringSoon)
              Row(
                spacing: 4,
                children: [
                  Icon(Icons.warning_amber, size: 18, color: colors.error),
                  Text(
                    l10n.passExpiringSoon,
                    style: textTheme.bodyMedium?.copyWith(color: colors.error),
                  ),
                ],
              ),
            if (onBuyNext != null)
              Align(
                alignment: Alignment.centerRight,
                child: pass.expiringSoon
                    ? FilledButton(
                        key: const Key('pass-buy-next-urgent'),
                        style: FilledButton.styleFrom(
                          backgroundColor: colors.error,
                          foregroundColor: colors.onError,
                        ),
                        onPressed: onBuyNext,
                        child: Text(l10n.passBuyNext),
                      )
                    : FilledButton.tonal(
                        onPressed: onBuyNext,
                        child: Text(l10n.passBuyNext),
                      ),
              ),
          ],
        ),
      ),
    );
  }

  /// Days the pass is still valid, counting today and its last day.
  static int _daysLeft(DateTime validUntil) {
    final now = DateTime.now();
    final today = DateTime.utc(now.year, now.month, now.day);
    final last = DateTime.utc(
      validUntil.year,
      validUntil.month,
      validUntil.day,
    );
    return last.difference(today).inDays + 1;
  }

  static String _statusLabel(AppLocalizations l10n, PassStatus status) {
    return switch (status) {
      PassStatus.pendingPayment => l10n.passStatusPendingPayment,
      PassStatus.scheduled => l10n.passStatusScheduled,
      PassStatus.active => l10n.passStatusActive,
      PassStatus.suspended => l10n.passStatusSuspended,
      PassStatus.expired => l10n.passStatusExpired,
      PassStatus.cancelled => l10n.passStatusCancelled,
    };
  }
}

class _OfferTab extends ConsumerWidget {
  const _OfferTab();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;
    final profile = session?.profiles
        .where((p) => p.id == profileId)
        .firstOrNull;

    /// Opens the purchase of [variant], or `null` when the active profile
    /// cannot buy [product].
    VoidCallback? Function(PassVariant) buyFor(PassProduct product) {
      final allowed =
          session != null &&
          profileId != null &&
          profile != null &&
          switch (product.audience) {
            PassAudience.any => true,
            PassAudience.adult => !profile.isChild,
            PassAudience.child => profile.isChild,
          };
      return (variant) => !allowed
          ? null
          : () => Navigator.of(context).push<PassOrder>(
              MaterialPageRoute(
                builder: (_) => PassCheckoutScreen(
                  profileId: profileId,
                  profileName: _profileName(session, profileId),
                  product: product,
                  variant: variant,
                ),
              ),
            );
    }

    return ref
        .watch(passOfferProvider)
        .when(
          loading: () => const Center(child: CircularProgressIndicator()),
          error: (_, _) =>
              _LoadError(onRetry: () => ref.invalidate(passOfferProvider)),
          data: (offer) => RefreshIndicator(
            onRefresh: () => ref.refresh(passOfferProvider.future),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                if (offer.products.isEmpty) _Message(l10n.passesOfferEmpty),
                for (final product in offer.products)
                  _PassProductCard(
                    product,
                    offer.serviceNames,
                    onBuy: buyFor(product),
                  ),
              ],
            ),
          ),
        );
  }
}

class _PassProductCard extends StatelessWidget {
  const _PassProductCard(
    this.product,
    this.serviceNames, {
    required this.onBuy,
  });

  final PassProduct product;
  final Map<String, String> serviceNames;

  /// Opens the purchase of a variant; returns `null` when it cannot be bought.
  final VoidCallback? Function(PassVariant) onBuy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    final description = product.description;
    final services = [
      for (final id in product.serviceTypeIds) ?serviceNames[id],
    ];
    final maxSuspensionDays = product.maxSuspensionDays;
    final audience = switch (product.audience) {
      PassAudience.any => null,
      PassAudience.adult => l10n.passAudienceAdult,
      PassAudience.child => l10n.passAudienceChild,
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 4,
          children: [
            Text(product.name, style: textTheme.titleMedium),
            Text(
              [_kindLabel(l10n, product.kind), ?audience].join(' · '),
              style: textTheme.bodySmall,
            ),
            if (description != null && description.isNotEmpty)
              Text(description),
            if (services.isNotEmpty)
              Text(l10n.passServices(services.join(', '))),
            Text(
              !product.suspensionAllowed
                  ? l10n.passSuspensionNotAllowed
                  : maxSuspensionDays == null
                  ? l10n.passSuspensionAllowed
                  : l10n.passSuspensionUpTo(maxSuspensionDays),
              style: textTheme.bodySmall,
            ),
            const Divider(),
            for (final variant in product.variants)
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '${switch (variant.entriesCount) {
                        null => l10n.passVariantUnlimited,
                        final count => l10n.passVariantEntries(count),
                      }}, ${l10n.passVariantValidity(variant.validityDays)}',
                    ),
                  ),
                  Text(formatMoney(variant.price), style: textTheme.titleSmall),
                  if (onBuy(variant) case final buy?)
                    Padding(
                      padding: const EdgeInsets.only(left: 8),
                      child: TextButton(
                        onPressed: buy,
                        child: Text(l10n.passOfferBuy),
                      ),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }

  static String _kindLabel(AppLocalizations l10n, PassProductKind kind) {
    return switch (kind) {
      PassProductKind.entryPass => l10n.passKindEntryPass,
      PassProductKind.singleEntry => l10n.passKindSingleEntry,
      PassProductKind.ptPackage => l10n.passKindPtPackage,
      PassProductKind.timePass => l10n.passKindTimePass,
      PassProductKind.voucher => l10n.passKindVoucher,
    };
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Text(text, textAlign: TextAlign.center),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        spacing: 16,
        children: [
          Text(l10n.passesLoadError),
          OutlinedButton(
            onPressed: onRetry,
            child: Text(l10n.passesRetryButton),
          ),
        ],
      ),
    );
  }
}
