import 'package:intl/intl.dart';
import 'package:mobile/features/passes/data/passes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// `2026-11-01` → `01.11.2026`.
String formatPassDate(DateTime date) => DateFormat('dd.MM.yyyy').format(date);

/// `2026-12-06` → `6.12`, the short date of the pass screens.
String formatPassDay(DateTime date) => DateFormat('d.MM').format(date);

/// `26000 PLN` → `260 zł`, `26050 PLN` → `260,50 zł`.
String formatMoney(Money money) {
  final whole = money.amountMinor % 100 == 0;
  final amount = NumberFormat(
    whole ? '#,##0' : '#,##0.00',
    'pl',
  ).format(money.amountMinor / 100);
  final currency = money.currency == 'PLN' ? 'zł' : money.currency;
  return '$amount $currency';
}

/// Today's date, without the time.
DateTime passToday() {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
}

/// Days a pass is still valid, counting today and its last day.
int passDaysLeft(DateTime validUntil) {
  final today = passToday();
  final last = DateTime(validUntil.year, validUntil.month, validUntil.day);
  return DateTime.utc(
        last.year,
        last.month,
        last.day,
      ).difference(DateTime.utc(today.year, today.month, today.day)).inDays +
      1;
}

/// Whether the pass can be used or is waiting to start.
bool passIsCurrent(OwnPass pass) => switch (pass.status) {
  PassStatus.active ||
  PassStatus.suspended ||
  PassStatus.scheduled ||
  PassStatus.pendingPayment => true,
  PassStatus.expired || PassStatus.cancelled => false,
};

/// `8 wejść` or `bez limitu wejść`.
String passEntriesShort(AppLocalizations l10n, int? total) =>
    total == null ? l10n.passUnlimitedShort : l10n.passVariantEntries(total);

/// `8 wejść · ważny do 6.12`, under a pass name.
String passSummary(AppLocalizations l10n, OwnPass pass) {
  final validUntil = pass.validUntil;
  return [
    passEntriesShort(l10n, pass.entriesTotal),
    if (validUntil != null) l10n.passValidUntilShort(formatPassDay(validUntil)),
  ].join(' · ');
}

String passStatusLabel(AppLocalizations l10n, PassStatus status) =>
    switch (status) {
      PassStatus.pendingPayment => l10n.passStatusPendingPayment,
      PassStatus.scheduled => l10n.passStatusScheduled,
      PassStatus.active => l10n.passStatusActive,
      PassStatus.suspended => l10n.passStatusSuspended,
      PassStatus.expired => l10n.passStatusExpired,
      PassStatus.cancelled => l10n.passStatusCancelled,
    };
