import 'package:intl/intl.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// When a conversation was last active, as shown in the list: the time
/// today, `wczoraj`, the weekday within a week, otherwise the date.
String formatListTime(DateTime time, AppLocalizations l10n, DateTime now) {
  final days = _dayDifference(time, now);
  if (days == 0) {
    return DateFormat('HH:mm', 'pl').format(time);
  }
  if (days == 1) {
    return l10n.messagesYesterday;
  }
  if (days < 7) {
    return DateFormat('EEE', 'pl').format(time);
  }
  return DateFormat('d.MM', 'pl').format(time);
}

/// Short Polish weekdays, Monday first.
const _shortWeekdays = ['pon', 'wt', 'śr', 'czw', 'pt', 'sob', 'ndz'];

/// The day and time shown above the first message of a chain and above a
/// tapped message: only the time today (`14:05`), `wczoraj, 18:30`,
/// `pt, 18:30` within a week, otherwise `6 listopada, 18:30` (with the year
/// when it is not this year's).
String formatChainTime(DateTime time, AppLocalizations l10n, DateTime now) {
  final clock = DateFormat('HH:mm', 'pl').format(time);
  final days = _dayDifference(time, now);
  if (days == 0) {
    return clock;
  }
  final String day;
  if (days == 1) {
    day = l10n.messagesYesterday;
  } else if (days > 1 && days < 7) {
    day = _shortWeekdays[time.weekday - 1];
  } else {
    day = DateFormat(
      time.year == now.year ? 'd MMMM' : 'd MMMM y',
      'pl',
    ).format(time);
  }
  return '$day, $clock';
}

/// The start of a class, e.g. `sob 8.11, 11:00`.
String formatClassStart(DateTime time) =>
    DateFormat('EEE d.MM, HH:mm', 'pl').format(time);

bool isSameDay(DateTime a, DateTime b) =>
    a.year == b.year && a.month == b.month && a.day == b.day;

int _dayDifference(DateTime time, DateTime now) => DateTime(
  now.year,
  now.month,
  now.day,
).difference(DateTime(time.year, time.month, time.day)).inDays;

/// A message for a failed conversations request, by its HTTP status.
String messagesErrorMessage(
  Object error,
  AppLocalizations l10n, {
  bool loading = false,
}) {
  if (error is ApiException) {
    return switch (error.statusCode) {
      null => l10n.messagesNetworkError,
      404 => l10n.messagesNotFound,
      409 => l10n.messagesChatExists,
      422 => l10n.messagesReadOnlyError,
      _ => loading ? l10n.messagesLoadError : l10n.messagesActionError,
    };
  }
  return loading ? l10n.messagesLoadError : l10n.messagesActionError;
}
