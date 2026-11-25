import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Buying one pass variant for a customer profile: the first day, the terms
/// and the payment. The payment is simulated until a payment provider is
/// connected, so confirming it pays the order at once.
class PassCheckoutScreen extends ConsumerStatefulWidget {
  const PassCheckoutScreen({
    super.key,
    required this.profileId,
    required this.profileName,
    required this.product,
    required this.variant,
    this.renewedPass,
    this.backLabel,
  });

  final String profileId;

  /// Shown when the account acts for more than one profile.
  final String? profileName;
  final PassProduct product;
  final PassVariant variant;

  /// The pass being renewed with the same variant, if any.
  final OwnPass? renewedPass;

  /// The screen it was opened from, for the back link; "Karnety" by default.
  final String? backLabel;

  @override
  ConsumerState<PassCheckoutScreen> createState() => _PassCheckoutScreenState();
}

class _PassCheckoutScreenState extends ConsumerState<PassCheckoutScreen> {
  late DateTime _startsOn = passToday();
  bool? _startAfterCurrent;
  bool _termsAccepted = false;
  bool _submitting = false;
  bool _failed = false;

  /// The order placed by an earlier attempt whose payment failed, so a retry
  /// only pays it.
  String? _placedOrderId;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final ct = context.ct;
    final sp = ct.spacing;
    final variant = widget.variant;
    final followed = widget.renewedPass ?? _latestRunningPass();
    final afterCurrent = followed != null && (_startAfterCurrent ?? true);
    final validFrom = afterCurrent ? _dayAfter(followed) : _startsOn;
    final validUntil = validFrom.add(Duration(days: variant.validityDays - 1));
    final locked = _submitting || _placedOrderId != null;
    final strong = ct.text.body.copyWith(fontWeight: ct.text.selectedWeight);

    return Scaffold(
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SubPageHeader(
            backLabel: widget.backLabel ?? l10n.navPasses,
            title: l10n.checkoutTitle,
          ),
          Expanded(
            child: ListView(
              children: [
                Container(
                  padding: EdgeInsets.fromLTRB(sp.page, sp.l, sp.page, sp.page),
                  decoration: BoxDecoration(
                    border: Border(bottom: ct.hairline),
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(widget.product.name, style: ct.text.nextClass),
                      SizedBox(height: sp.xs),
                      Text(
                        '${switch (variant.entriesCount) {
                          null => l10n.passVariantUnlimited,
                          final count => l10n.passVariantEntries(count),
                        }}, ${l10n.passVariantValidity(variant.validityDays)}',
                        style: ct.text.meta,
                      ),
                      if (widget.profileName case final name?) ...[
                        SizedBox(height: sp.xxs),
                        Text(l10n.checkoutFor(name), style: ct.text.meta),
                      ],
                      SizedBox(height: sp.section),
                      Text(
                        formatMoney(variant.price),
                        style: ct.text.checkoutPrice,
                      ),
                    ],
                  ),
                ),
                if (followed != null)
                  AppCheckRow(
                    key: const Key('checkout-start-after-current'),
                    value: afterCurrent,
                    title: l10n.checkoutStartAfterCurrent,
                    hint: followed.validUntil == null
                        ? null
                        : l10n.checkoutStartAfterCurrentHint(
                            followed.productName,
                            formatPassDate(followed.validUntil!),
                          ),
                    onChanged: locked
                        ? null
                        : (value) => setState(() => _startAfterCurrent = value),
                  ),
                if (!afterCurrent)
                  AppRow(
                    key: const Key('checkout-start-date'),
                    leading: Icon(
                      Icons.calendar_today_outlined,
                      color: ct.colors.muted,
                      size: ct.sizes.icon,
                    ),
                    title: l10n.checkoutStartDate,
                    subtitle: formatPassDate(_startsOn),
                    topLine: false,
                    bottomLine: true,
                    onTap: locked ? null : _pickStartDate,
                  ),
                Container(
                  padding: EdgeInsets.symmetric(
                    horizontal: sp.page,
                    vertical: sp.l,
                  ),
                  decoration: BoxDecoration(
                    border: Border(bottom: ct.hairline),
                  ),
                  child: Text(
                    l10n.passValidFromUntil(
                      formatPassDate(validFrom),
                      formatPassDate(validUntil),
                    ),
                    style: strong,
                  ),
                ),
                AppCheckRow(
                  key: const Key('checkout-terms'),
                  value: _termsAccepted,
                  title: l10n.checkoutTermsAccept,
                  onChanged: _submitting
                      ? null
                      : (value) => setState(() => _termsAccepted = value),
                ),
                if (_failed)
                  Padding(
                    padding: EdgeInsets.fromLTRB(sp.page, sp.l, sp.page, 0),
                    child: SheetWarning(l10n.checkoutError),
                  ),
              ],
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: EdgeInsets.fromLTRB(sp.page, sp.m, sp.page, sp.section),
              child: AppButton.primary(
                key: const Key('checkout-pay'),
                label: l10n.checkoutPay(formatMoney(variant.price)),
                loading: _submitting,
                onPressed: _termsAccepted
                    ? () => _pay(afterCurrent ? followed : null)
                    : null,
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// The profile's usable pass that ends last, to start the new one after it.
  OwnPass? _latestRunningPass() {
    final passes = ref.watch(ownPassesProvider(widget.profileId)).value;
    if (passes == null) {
      return null;
    }
    OwnPass? latest;
    for (final pass in passes) {
      final running =
          pass.status == PassStatus.active ||
          pass.status == PassStatus.suspended ||
          pass.status == PassStatus.scheduled;
      final validUntil = pass.validUntil;
      if (!running || validUntil == null) {
        continue;
      }
      if (latest == null || validUntil.isAfter(latest.validUntil!)) {
        latest = pass;
      }
    }
    return latest;
  }

  /// The day after [pass] ends, or today if it has already ended; the
  /// backend counts it the same way.
  static DateTime _dayAfter(OwnPass pass) {
    final today = passToday();
    final validUntil = pass.validUntil;
    if (validUntil == null) {
      return today;
    }
    final dayAfter = DateTime(
      validUntil.year,
      validUntil.month,
      validUntil.day + 1,
    );
    return dayAfter.isAfter(today) ? dayAfter : today;
  }

  Future<void> _pickStartDate() async {
    final today = passToday();
    final picked = await showDatePicker(
      context: context,
      initialDate: _startsOn,
      firstDate: today,
      lastDate: today.add(const Duration(days: 365)),
    );
    if (picked != null) {
      setState(() => _startsOn = picked);
    }
  }

  Future<void> _pay(OwnPass? followed) async {
    final l10n = AppLocalizations.of(context);
    final api = ref.read(passesApiProvider);
    setState(() {
      _submitting = true;
      _failed = false;
    });
    try {
      final orderId =
          _placedOrderId ??
          (await api.placeOrder(
            widget.profileId,
            variantId: widget.variant.id,
            startsOn: followed == null ? _startsOn : null,
            startAfterPassId: followed?.id,
          )).id;
      _placedOrderId = orderId;
      final order = await api.payOrderWithMock(orderId);
      ref.invalidate(ownPassesProvider(widget.profileId));
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            l10n.checkoutSuccess(
              formatPassDate(order.validFrom),
              formatPassDate(order.validUntil),
            ),
          ),
        ),
      );
      Navigator.of(context).pop(order);
    } on Exception {
      if (mounted) {
        setState(() {
          _submitting = false;
          _failed = true;
        });
      }
    }
  }
}
