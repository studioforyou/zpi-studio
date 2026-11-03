import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/features/passes/presentation/pass_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

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
  });

  final String profileId;

  /// Shown when the account acts for more than one profile.
  final String? profileName;
  final PassProduct product;
  final PassVariant variant;

  /// The pass being renewed with the same variant, if any.
  final OwnPass? renewedPass;

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
    final textTheme = Theme.of(context).textTheme;
    final variant = widget.variant;
    final followed = widget.renewedPass ?? _latestRunningPass();
    final afterCurrent = followed != null && (_startAfterCurrent ?? true);
    final validFrom = afterCurrent ? _dayAfter(followed) : _startsOn;
    final validUntil = validFrom.add(Duration(days: variant.validityDays - 1));

    return Scaffold(
      appBar: AppBar(title: Text(l10n.checkoutTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 4,
                children: [
                  Text(widget.product.name, style: textTheme.titleMedium),
                  Text(
                    '${switch (variant.entriesCount) {
                      null => l10n.passVariantUnlimited,
                      final count => l10n.passVariantEntries(count),
                    }}, ${l10n.passVariantValidity(variant.validityDays)}',
                  ),
                  if (widget.profileName case final name?)
                    Text(l10n.checkoutFor(name)),
                  Text(formatMoney(variant.price), style: textTheme.titleLarge),
                ],
              ),
            ),
          ),
          const SizedBox(height: 8),
          if (followed != null)
            CheckboxListTile(
              key: const Key('checkout-start-after-current'),
              contentPadding: EdgeInsets.zero,
              value: afterCurrent,
              onChanged: _submitting || _placedOrderId != null
                  ? null
                  : (value) => setState(() => _startAfterCurrent = value),
              title: Text(l10n.checkoutStartAfterCurrent),
              subtitle: followed.validUntil == null
                  ? null
                  : Text(
                      l10n.checkoutStartAfterCurrentHint(
                        followed.productName,
                        formatPassDate(followed.validUntil!),
                      ),
                    ),
            ),
          if (!afterCurrent)
            ListTile(
              key: const Key('checkout-start-date'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.event),
              title: Text(l10n.checkoutStartDate),
              subtitle: Text(formatPassDate(_startsOn)),
              onTap: _submitting || _placedOrderId != null
                  ? null
                  : _pickStartDate,
            ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              l10n.passValidFromUntil(
                formatPassDate(validFrom),
                formatPassDate(validUntil),
              ),
              style: textTheme.bodyLarge,
            ),
          ),
          CheckboxListTile(
            key: const Key('checkout-terms'),
            contentPadding: EdgeInsets.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: _termsAccepted,
            onChanged: _submitting
                ? null
                : (value) => setState(() => _termsAccepted = value ?? false),
            title: Text(l10n.checkoutTermsAccept),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              l10n.checkoutMockPaymentInfo,
              style: textTheme.bodySmall,
            ),
          ),
          if (_failed)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(
                l10n.checkoutError,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
          FilledButton(
            key: const Key('checkout-pay'),
            onPressed: _termsAccepted && !_submitting
                ? () => _pay(afterCurrent ? followed : null)
                : null,
            child: _submitting
                ? const SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(l10n.checkoutPay(formatMoney(variant.price))),
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
