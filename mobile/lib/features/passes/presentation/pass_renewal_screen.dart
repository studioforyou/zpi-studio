import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/pass_checkout_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Renewing one pass of the active customer profile, opened from a reminder
/// that it is about to run out: the purchase of the same variant, starting
/// after the pass ends, with the option to say it will not be renewed.
class PassRenewalScreen extends ConsumerWidget {
  const PassRenewalScreen({super.key, required this.passId});

  final String passId;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;
    if (session == null || profileId == null) {
      return _Unavailable(l10n.passesNoProfile);
    }
    final passes = ref.watch(ownPassesProvider(profileId));
    final offer = ref.watch(passOfferProvider);
    if (passes.hasError || offer.hasError) {
      return _Unavailable(
        l10n.passesLoadError,
        onRetry: () {
          ref.invalidate(ownPassesProvider(profileId));
          ref.invalidate(passOfferProvider);
        },
      );
    }
    final ownPasses = passes.value;
    final products = offer.value?.products;
    if (ownPasses == null || products == null) {
      return Scaffold(
        appBar: AppBar(title: Text(l10n.passRenewalTitle)),
        body: const Center(child: CircularProgressIndicator()),
      );
    }

    final pass = ownPasses.where((pass) => pass.id == passId).firstOrNull;
    final variantId = pass?.variantId;
    if (pass != null && variantId != null) {
      for (final product in products) {
        for (final variant in product.variants) {
          if (variant.id == variantId) {
            return PassCheckoutScreen(
              profileId: profileId,
              profileName: session.profiles.length > 1
                  ? session.profiles
                        .where((profile) => profile.id == profileId)
                        .firstOrNull
                        ?.displayName
                  : null,
              product: product,
              variant: variant,
              renewedPass: pass,
            );
          }
        }
      }
    }
    return _Unavailable(l10n.passBuyNextUnavailable);
  }
}

/// Why the pass cannot be renewed here, with a way to the client's passes.
class _Unavailable extends StatelessWidget {
  const _Unavailable(this.message, {this.onRetry});

  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.passRenewalTitle)),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            spacing: 16,
            children: [
              Text(message, textAlign: TextAlign.center),
              if (onRetry case final retry?)
                OutlinedButton(
                  onPressed: retry,
                  child: Text(l10n.passesRetryButton),
                ),
              FilledButton.tonal(
                onPressed: () => context.go(clientPassesPath),
                child: Text(l10n.passRenewalShowPasses),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
