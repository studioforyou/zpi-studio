import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/app/shell/client_shell.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/passes/data/makeup_credits_api.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/makeup_credits.dart';
import 'package:mobile/features/passes/presentation/pass_checkout_screen.dart';
import 'package:mobile/features/passes/presentation/pass_entries.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Karnety (#281): the passes of one person, one at a time, and the studio's
/// offer. A family account picks whose passes it sees; buying follows that
/// person too.
class PassesScreen extends ConsumerStatefulWidget {
  const PassesScreen({super.key});

  @override
  ConsumerState<PassesScreen> createState() => _PassesScreenState();
}

class _PassesScreenState extends ConsumerState<PassesScreen> {
  int _tab = 0;

  /// The person whose passes are shown; the active profile until the
  /// person picker changes it.
  String? _profileId;

  @override
  Widget build(BuildContext context) {
    // A new active profile (a notification about a child, another login)
    // shows its own passes again.
    ref.listen(authControllerProvider, (previous, next) {
      String? active(AuthState? state) => switch (state) {
        Authenticated(:final session) =>
          '${session.accountId}/${session.activeProfileId}',
        _ => null,
      };
      if (active(previous) != active(next)) setState(() => _profileId = null);
    });
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profiles = session?.profiles ?? const <Profile>[];
    final profile =
        profiles.where((p) => p.id == _profileId).firstOrNull ??
        profiles.where((p) => p.id == session?.activeProfileId).firstOrNull;
    return Scaffold(
      appBar: ClientTabHeader(title: l10n.navPasses),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          AppTabs(
            labels: [l10n.passesTabMine, l10n.passesTabBuy],
            selected: _tab,
            onChanged: (tab) => setState(() => _tab = tab),
          ),
          Expanded(
            child: session == null || profile == null
                ? _Message(l10n.passesNoProfile)
                : _tab == 0
                ? _MyPasses(
                    key: ValueKey((session.accountId, profile.id)),
                    session: session,
                    profile: profile,
                    onPickPerson: () => _pickPerson(session, profile),
                    onBuy: () => setState(() => _tab = 1),
                  )
                : _Offer(session: session, profile: profile),
          ),
        ],
      ),
    );
  }

  Future<void> _pickPerson(Session session, Profile selected) async {
    final l10n = AppLocalizations.of(context);
    final picked = await showPickerSheet<String>(
      context,
      kicker: l10n.passesTabMine,
      title: l10n.passesPickPerson,
      selected: selected.id,
      options: [
        for (final profile in session.profiles)
          PickerOption(
            value: profile.id,
            title: profile.displayName,
            subtitle: profile.isChild
                ? l10n.passesPersonChild
                : l10n.passesPersonSelfLong,
            initials: SquareAvatar.initialsOf(profile.displayName),
          ),
      ],
    );
    if (picked != null && mounted) setState(() => _profileId = picked);
  }
}

/// The name of [profile] when the account acts for more than one person.
String? _familyName(Session session, Profile profile) =>
    session.profiles.length > 1 ? profile.displayName : null;

class _MyPasses extends ConsumerStatefulWidget {
  const _MyPasses({
    super.key,
    required this.session,
    required this.profile,
    required this.onPickPerson,
    required this.onBuy,
  });

  final Session session;
  final Profile profile;
  final VoidCallback onPickPerson;
  final VoidCallback onBuy;

  @override
  ConsumerState<_MyPasses> createState() => _MyPassesState();
}

class _MyPassesState extends ConsumerState<_MyPasses> {
  String? _passId;
  bool _expiredOpen = false;

  String get _profileId => widget.profile.id;

  MakeupScope get _makeupScope =>
      (accountId: widget.session.accountId, profileId: _profileId);

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final provider = ownPassesProvider(_profileId);
    final passes = ref.watch(provider);
    return RefreshIndicator(
      onRefresh: () {
        ref.invalidate(makeupCreditsProvider);
        return ref.refresh(provider.future);
      },
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: EdgeInsets.only(bottom: sp.xl),
        children: [
          MakeupStrip(scope: _makeupScope),
          if (widget.session.profiles.length > 1)
            Padding(
              padding: EdgeInsets.fromLTRB(sp.page, sp.section, sp.page, 0),
              child: PersonPickerField(
                name: widget.profile.displayName,
                note: widget.profile.isChild
                    ? l10n.passesPersonChild
                    : l10n.passesPersonSelf,
                onTap: widget.onPickPerson,
              ),
            ),
          ...passes.when(
            skipLoadingOnRefresh: false,
            loading: () => [
              Padding(
                padding: EdgeInsets.all(sp.xl),
                child: const Center(child: CircularProgressIndicator()),
              ),
            ],
            error: (_, _) => [
              _LoadError(onRetry: () => ref.invalidate(provider)),
            ],
            data: _content,
          ),
        ],
      ),
    );
  }

  List<Widget> _content(List<OwnPass> passes) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final current = _ordered([
      for (final pass in passes)
        if (passIsCurrent(pass)) pass,
    ]);
    final expired = [
      for (final pass in passes)
        if (pass.status == PassStatus.expired) pass,
    ];
    final selected =
        current.where((pass) => pass.id == _passId).firstOrNull ??
        current.firstOrNull;
    return [
      if (selected == null)
        _NoPass(
          text: passes.isEmpty ? l10n.passesEmpty : l10n.passesNoCurrent,
          onBuy: widget.onBuy,
        )
      else ...[
        Padding(
          padding: EdgeInsets.fromLTRB(
            sp.page,
            widget.session.profiles.length > 1 ? sp.s + sp.xxs : sp.section,
            sp.page,
            0,
          ),
          child: PickerField(
            title: selected.productName,
            subtitle: passSummary(l10n, selected),
            status: PassStatusDot(selected.status),
            onTap: current.length > 1
                ? () => _pickPass(current, selected)
                : null,
          ),
        ),
        _PassCard(
          pass: selected,
          onHistory: () => _openHistory(selected),
          onBuyNext:
              selected.expiringSoon &&
                  selected.variantId != null &&
                  selected.status != PassStatus.pendingPayment
              ? () => _buyNext(selected)
              : null,
        ),
      ],
      if (expired.isNotEmpty) ...[
        AppRow(
          title: l10n.passesSectionExpired(expired.length),
          dimmed: true,
          topLine: false,
          bottomLine: true,
          onTap: () => setState(() => _expiredOpen = !_expiredOpen),
          trailing: Icon(
            _expiredOpen ? Icons.expand_less : Icons.expand_more,
            color: ct.colors.muted,
            size: ct.sizes.icon,
          ),
        ),
        if (_expiredOpen)
          for (final pass in expired)
            ColoredBox(
              color: ct.colors.surface,
              child: AppRow(
                title:
                    '${pass.productName} · ${passEntriesShort(l10n, pass.entriesTotal)}',
                subtitle: _expiredSummary(l10n, pass),
                topLine: false,
                bottomLine: true,
                onTap: () => _openHistory(pass),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    PassStatusDot(pass.status),
                    SizedBox(width: sp.s),
                    Icon(
                      Icons.chevron_right,
                      color: ct.colors.faint,
                      size: ct.sizes.icon,
                    ),
                  ],
                ),
              ),
            ),
      ],
    ];
  }

  /// Running passes first, the one that ends first on top; then the ones
  /// that have not started, in the order they start.
  static List<OwnPass> _ordered(List<OwnPass> passes) {
    final far = DateTime(9999);
    bool running(OwnPass pass) =>
        pass.status == PassStatus.active || pass.status == PassStatus.suspended;
    return [...passes]..sort((a, b) {
      if (running(a) != running(b)) return running(a) ? -1 : 1;
      final byDate = running(a)
          ? (a.validUntil ?? far).compareTo(b.validUntil ?? far)
          : (a.validFrom ?? far).compareTo(b.validFrom ?? far);
      return byDate;
    });
  }

  static String _expiredSummary(AppLocalizations l10n, OwnPass pass) {
    final from = pass.validFrom;
    final until = pass.validUntil;
    final total = pass.entriesTotal;
    final remaining = pass.entriesRemaining;
    return [
      if (from != null && until != null)
        '${formatPassDay(from)}–${formatPassDay(until)}',
      if (total != null && remaining != null)
        l10n.passUsedOf(total - remaining, total),
    ].join(' · ');
  }

  Future<void> _pickPass(List<OwnPass> current, OwnPass selected) async {
    final l10n = AppLocalizations.of(context);
    final picked = await showPickerSheet<String>(
      context,
      kicker: l10n.passesTabMine,
      title: l10n.passesPickPass,
      selected: selected.id,
      options: [
        for (final pass in current)
          PickerOption(
            value: pass.id,
            title: pass.productName,
            subtitle: passSummary(l10n, pass),
            trailing: PassStatusDot(pass.status),
          ),
      ],
    );
    if (picked != null && mounted) setState(() => _passId = picked);
  }

  void _openHistory(OwnPass pass) {
    showPassEntriesSheet(
      context,
      accountId: widget.session.accountId,
      profileId: _profileId,
      pass: pass,
    );
  }

  /// Opens the purchase of the same variant as [pass], if it is still on
  /// sale.
  Future<void> _buyNext(OwnPass pass) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final navigator = Navigator.of(context);
    final PassOffer offer;
    try {
      offer = await ref.read(passOfferProvider.future);
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
                profileId: _profileId,
                profileName: _familyName(widget.session, widget.profile),
                product: product,
                variant: variant,
                renewedPass: pass,
                backLabel: l10n.navPasses,
              ),
            ),
          );
          return;
        }
      }
    }
    messenger.showSnackBar(
      SnackBar(content: Text(l10n.passBuyNextUnavailable)),
    );
  }
}

/// The selected pass: what it covers, the entries left with a bar of
/// entries, how long it is valid and its actions.
class _PassCard extends ConsumerWidget {
  const _PassCard({
    required this.pass,
    required this.onHistory,
    this.onBuyNext,
  });

  final OwnPass pass;
  final VoidCallback onHistory;

  /// Buys the same variant again; `null` hides the button.
  final VoidCallback? onBuyNext;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final covers = _covers(ref.watch(passOfferProvider).value);
    final total = pass.entriesTotal;
    final remaining = pass.entriesRemaining;
    final validFrom = pass.validFrom;
    final validUntil = pass.validUntil;
    final running =
        pass.status == PassStatus.active || pass.status == PassStatus.suspended;
    final validity = switch ((validFrom, validUntil)) {
      (final from?, final until?) when !running => l10n.passValidFromUntilShort(
        formatPassDay(from),
        formatPassDay(until),
      ),
      (_, final until?) => [
        l10n.passValidUntilShort(formatPassDay(until)),
        l10n.passDaysLeftShort(passDaysLeft(until)),
      ].join(' · '),
      _ => null,
    };
    return Container(
      padding: EdgeInsets.fromLTRB(sp.page, sp.section, sp.page, sp.xl - 2),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (covers != null) Text(covers, style: ct.text.meta),
          SizedBox(height: sp.l + sp.xs),
          if (total != null && remaining != null)
            Semantics(
              label: l10n.passEntriesLeft(remaining, total),
              excludeSemantics: true,
              child: FittedBox(
                fit: BoxFit.scaleDown,
                alignment: Alignment.centerLeft,
                child: Text.rich(
                  TextSpan(
                    style: ct.text.heroUnit,
                    children: [
                      TextSpan(text: '${l10n.passRemainingLead} '),
                      TextSpan(text: '$remaining', style: ct.text.hero),
                      TextSpan(text: ' ${l10n.passRemainingOf(total)}'),
                    ],
                  ),
                ),
              ),
            )
          else
            Text(l10n.passEntriesUnlimited, style: ct.text.bigNumber),
          if (total != null &&
              remaining != null &&
              total > 0 &&
              total <= ct.sizes.entryBarMaxSegments) ...[
            SizedBox(height: sp.l),
            _EntryBar(total: total, remaining: remaining),
          ],
          if (pass.expiringSoon) ...[
            SizedBox(height: sp.m),
            Container(
              padding: EdgeInsets.symmetric(
                horizontal: sp.s,
                vertical: sp.xxs + 1,
              ),
              decoration: BoxDecoration(
                border: Border.all(
                  color: c.orangeText,
                  width: ct.shape.hairline,
                ),
              ),
              child: Text(
                l10n.passExpiringSoon,
                style: ct.text.changeLine.copyWith(color: c.orangeText),
              ),
            ),
          ],
          if (validity != null) ...[
            SizedBox(height: pass.expiringSoon ? sp.s : sp.m),
            Text(validity, style: ct.text.meta),
          ],
          SizedBox(height: sp.l + sp.xxs),
          AppButtonPair(
            first: AppButton(label: l10n.passUsageTitle, onPressed: onHistory),
            second: onBuyNext == null
                ? null
                : AppButton(
                    key: const Key('pass-buy-next'),
                    label: l10n.passBuyNext,
                    onPressed: onBuyNext,
                  ),
          ),
        ],
      ),
    );
  }

  /// The classes the pass pays for, from the offer of its variant.
  String? _covers(PassOffer? offer) {
    if (offer == null || pass.variantId == null) return null;
    for (final product in offer.products) {
      if (product.variants.any((variant) => variant.id == pass.variantId)) {
        final names = [
          for (final id in product.serviceTypeIds) ?offer.serviceNames[id],
        ];
        return names.isEmpty ? null : names.join(', ');
      }
    }
    return null;
  }
}

/// One segment per entry: used ones faint, the ones left in the free-entry
/// colour.
class _EntryBar extends StatelessWidget {
  const _EntryBar({required this.total, required this.remaining});

  final int total;
  final int remaining;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final used = (total - remaining).clamp(0, total);
    return ExcludeSemantics(
      child: SizedBox(
        height: ct.sizes.entryBarHeight,
        child: Row(
          children: [
            for (var i = 0; i < total; i++) ...[
              if (i > 0) SizedBox(width: ct.sizes.entryBarGap),
              Expanded(
                child: ColoredBox(
                  color: i < used ? ct.colors.faint : ct.colors.freeEntry,
                  child: const SizedBox.expand(),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _NoPass extends StatelessWidget {
  const _NoPass({required this.text, required this.onBuy});

  final String text;
  final VoidCallback onBuy;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Container(
      padding: EdgeInsets.symmetric(
        horizontal: ct.spacing.page,
        vertical: ct.spacing.xl,
      ),
      decoration: BoxDecoration(border: Border(bottom: ct.hairline)),
      child: Column(
        children: [
          Text(text, textAlign: TextAlign.center, style: ct.text.body),
          SizedBox(height: ct.spacing.l),
          AppButton(
            label: AppLocalizations.of(context).passesTabBuy,
            onPressed: onBuy,
          ),
        ],
      ),
    );
  }
}

/// Kup karnet: one framed box per product with its rules and variants. A
/// variant can be bought when the product suits the selected person.
class _Offer extends ConsumerWidget {
  const _Offer({required this.session, required this.profile});

  final Session session;
  final Profile profile;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;

    VoidCallback? buyFor(PassProduct product, PassVariant variant) {
      final allowed = switch (product.audience) {
        PassAudience.any => true,
        PassAudience.adult => !profile.isChild,
        PassAudience.child => profile.isChild,
      };
      if (!allowed) return null;
      return () => Navigator.of(context).push<PassOrder>(
        MaterialPageRoute(
          builder: (_) => PassCheckoutScreen(
            profileId: profile.id,
            profileName: _familyName(session, profile),
            product: product,
            variant: variant,
            backLabel: l10n.passesTabBuy,
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
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(sp.l, sp.section, sp.l, sp.xl),
              children: [
                if (offer.products.isEmpty) _Message(l10n.passesOfferEmpty),
                for (final (index, product) in offer.products.indexed) ...[
                  if (index > 0) SizedBox(height: sp.m),
                  _ProductBox(
                    product: product,
                    serviceNames: offer.serviceNames,
                    buyFor: buyFor,
                  ),
                ],
              ],
            ),
          ),
        );
  }
}

class _ProductBox extends StatelessWidget {
  const _ProductBox({
    required this.product,
    required this.serviceNames,
    required this.buyFor,
  });

  final PassProduct product;
  final Map<String, String> serviceNames;
  final VoidCallback? Function(PassProduct, PassVariant) buyFor;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final c = ct.colors;
    final sp = ct.spacing;
    final description = product.description;
    final services = [
      for (final id in product.serviceTypeIds) ?serviceNames[id],
    ];
    final kind = _kindLabel(l10n, product.kind);
    final kindLine = [
      if (kind != product.name) kind,
      switch (product.audience) {
        PassAudience.any => null,
        PassAudience.adult => l10n.passAudienceAdult,
        PassAudience.child => l10n.passAudienceChild,
      },
    ].nonNulls.join(' · ');
    final maxSuspensionDays = product.maxSuspensionDays;
    return Container(
      padding: EdgeInsets.fromLTRB(sp.l, sp.l, sp.l, sp.xs + sp.xxs),
      decoration: BoxDecoration(
        color: c.surface,
        border: Border.fromBorderSide(ct.hairline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(product.name, style: ct.text.nextClass),
          if (kindLine.isNotEmpty) ...[
            SizedBox(height: sp.xxs + 1),
            Text(kindLine, style: ct.text.caption),
          ],
          if (description != null && description.isNotEmpty) ...[
            SizedBox(height: sp.s + sp.xxs),
            Text(description, style: ct.text.lineValue),
          ],
          if (services.isNotEmpty) ...[
            SizedBox(height: sp.xs),
            Text(l10n.passServices(services.join(', ')), style: ct.text.meta),
          ],
          SizedBox(height: sp.xs),
          Text(
            !product.suspensionAllowed
                ? l10n.passSuspensionNotAllowed
                : maxSuspensionDays == null
                ? l10n.passSuspensionAllowed
                : l10n.passSuspensionUpTo(maxSuspensionDays),
            style: ct.text.meta,
          ),
          SizedBox(height: sp.m),
          for (final variant in product.variants)
            _VariantRow(variant: variant, onBuy: buyFor(product, variant)),
        ],
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

class _VariantRow extends StatelessWidget {
  const _VariantRow({required this.variant, required this.onBuy});

  final PassVariant variant;

  /// `null` when the selected person cannot buy it.
  final VoidCallback? onBuy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final entries = switch (variant.entriesCount) {
      null => l10n.passVariantUnlimited,
      final count => l10n.passVariantEntries(count),
    };
    return Container(
      padding: EdgeInsets.symmetric(vertical: sp.s + sp.xxs),
      decoration: BoxDecoration(border: Border(top: ct.hairline)),
      child: Row(
        children: [
          Expanded(
            child: Text.rich(
              TextSpan(
                style: ct.text.body.copyWith(color: ct.colors.muted),
                children: [
                  TextSpan(
                    text: entries,
                    style: ct.text.body.copyWith(
                      fontWeight: ct.text.selectedWeight,
                    ),
                  ),
                  TextSpan(
                    text:
                        ' · ${l10n.passVariantValidity(variant.validityDays)}',
                  ),
                ],
              ),
            ),
          ),
          SizedBox(width: sp.m),
          Text(formatMoney(variant.price), style: ct.text.offerPrice),
          SizedBox(width: sp.m),
          SizedBox(
            width: ct.sizes.offerBuyWidth,
            child: onBuy == null
                ? null
                : AppButton(
                    label: l10n.passOfferBuy,
                    small: true,
                    onPressed: onBuy,
                  ),
          ),
        ],
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.all(ct.spacing.xl),
      child: Text(text, textAlign: TextAlign.center, style: ct.text.body),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    return Padding(
      padding: EdgeInsets.all(ct.spacing.xl),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(l10n.passesLoadError, style: ct.text.body),
          SizedBox(height: ct.spacing.l),
          AppButton(label: l10n.passesRetryButton, onPressed: onRetry),
        ],
      ),
    );
  }
}
