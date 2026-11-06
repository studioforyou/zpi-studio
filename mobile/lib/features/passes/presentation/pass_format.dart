import 'package:intl/intl.dart';
import 'package:mobile/features/passes/data/passes_api.dart';

/// `2026-11-01` → `01.11.2026`.
String formatPassDate(DateTime date) => DateFormat('dd.MM.yyyy').format(date);

/// `26000 PLN` → `260,00 PLN`, as on the web.
String formatMoney(Money money) {
  final amount = NumberFormat('#,##0.00', 'pl').format(money.amountMinor / 100);
  return '$amount ${money.currency}';
}

/// Today's date, without the time.
DateTime passToday() {
  final now = DateTime.now();
  return DateTime(now.year, now.month, now.day);
}
