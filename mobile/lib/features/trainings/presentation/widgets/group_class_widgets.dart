import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

Session? clientSession(AuthState state) => switch (state) {
  Authenticated(:final session) when session.activeRole == AppRole.client =>
    session,
  _ => null,
};

BookingScope? bookingScope(AuthState state) {
  final session = clientSession(state);
  final profileId = session?.activeProfileId;
  return session == null || profileId == null
      ? null
      : (accountId: session.accountId, profileId: profileId);
}

bool isCurrentBookingScope(WidgetRef ref, BookingScope scope) =>
    bookingScope(ref.read(authControllerProvider)) == scope;

String classDate(DateTime date) => DateFormat('EEEE d MMMM', 'pl').format(date);
String classDateTime(DateTime date) =>
    DateFormat('dd.MM.yyyy, HH:mm', 'pl').format(date);
String classTime(GroupClass event) =>
    '${DateFormat('HH:mm').format(event.startsAt)}–'
    '${DateFormat('HH:mm').format(event.endsAt)}';

String classPlaces(AppLocalizations l10n, GroupClass event) =>
    _places(l10n, event.activeReservations, event.capacity);

String _places(AppLocalizations l10n, int reserved, int? capacity) {
  if (capacity == null) return l10n.classesUnlimited;
  if (reserved >= capacity) return l10n.classesFull;
  return l10n.classesPlaces(capacity - reserved, capacity);
}

Color classServiceColor(GroupClass event, ColorScheme scheme) =>
    serviceColor(event.calendarColor, scheme);

/// A service's configured `#RRGGBB` color, or the theme's primary color.
Color serviceColor(String? color, ColorScheme scheme) {
  if (color != null && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(color)) {
    return Color(0xff000000 | int.parse(color.substring(1), radix: 16));
  }
  return scheme.primary;
}

class ClassOccupancy extends StatelessWidget {
  ClassOccupancy(GroupClass event, {this.compact = false, super.key})
    : reserved = event.activeReservations,
      capacity = event.capacity;

  /// Occupancy of a class known only by its counts, e.g. from its roster.
  const ClassOccupancy.counts({
    required this.reserved,
    required this.capacity,
    this.compact = false,
    super.key,
  });

  /// Places taken by active reservations.
  final int reserved;

  /// Maximum number of participants, or `null` without a limit.
  final int? capacity;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final theme = Theme.of(context);
    final capacity = this.capacity;
    final full = capacity != null && reserved >= capacity;
    final color = full ? theme.colorScheme.error : Colors.green.shade700;
    return Semantics(
      label: _places(l10n, reserved, capacity),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        spacing: 5,
        children: [
          Text(
            capacity == null
                ? l10n.classesUnlimited
                : '${l10n.classesOccupied(reserved, capacity)}'
                      '${full ? ' · ${l10n.classesNoPlacesShort}' : ''}',
            maxLines: compact ? 2 : null,
            overflow: compact ? TextOverflow.ellipsis : null,
            style:
                (compact
                        ? theme.textTheme.labelSmall
                        : theme.textTheme.labelMedium)
                    ?.copyWith(color: color),
          ),
          if (capacity != null && capacity > 0)
            LinearProgressIndicator(
              value: (reserved / capacity).clamp(0, 1),
              minHeight: compact ? 3 : 5,
              borderRadius: BorderRadius.circular(4),
              color: color,
              backgroundColor: color.withValues(alpha: 0.12),
            ),
        ],
      ),
    );
  }
}

String classReason(AppLocalizations l10n, String? reason) => switch (reason) {
  'ALREADY_RESERVED' => l10n.classesReserved,
  'EVENT_NOT_BOOKABLE' => l10n.classesNotBookable,
  'BOOKING_NOT_OPEN' => l10n.classesBookingNotOpen,
  'BOOKING_CLOSED' => l10n.classesBookingClosed,
  'CLASS_FULL' => l10n.classesFull,
  'AGE_RESTRICTION' => l10n.classesAgeRestriction,
  'GROUP_MEMBERSHIP_REQUIRED' => l10n.classesGroupRequired,
  'REQUIRED_DOCUMENTS_MISSING' => l10n.classesDocumentsRequired,
  'NO_ELIGIBLE_PASS' => l10n.classesNoEligiblePass,
  'MAKEUP_CREDIT_NOT_AVAILABLE' => l10n.makeupUnavailable,
  'MAKEUP_CREDIT_NOT_USABLE' => l10n.makeupNotUsable,
  'PASS_NOT_USABLE' ||
  'PASS_ENTRIES_EXHAUSTED' ||
  'PASS_SERVICE_MISMATCH' => l10n.classesNoEligiblePass,
  'CANCELLATION_NOT_ALLOWED' => l10n.classesCancellationNotAllowed,
  'CANCELLATION_TERMS_UNAVAILABLE' => l10n.classesCancellationNotAllowed,
  'SERVICE_TYPE_UNAVAILABLE' => l10n.classesNotBookable,
  'FREE_CANCELLATION_EXPIRED' => l10n.classesFreeCancellationExpired,
  'EVENT_ALREADY_STARTED' => l10n.classesAlreadyStarted,
  'RESERVATION_NOT_ACTIVE' => l10n.classesReservationInactive,
  'CUSTOMER_NOT_ACTIVE' => l10n.classesProfileInactive,
  'WAITLIST_DISABLED' => l10n.classesWaitlistDisabled,
  'CLASS_NOT_FULL' => l10n.classesWaitlistPlaceFreed,
  'WAITLIST_ALREADY_JOINED' => l10n.classesWaitlistJoined,
  'WAITLIST_ENTRY_NOT_WAITING' => l10n.classesWaitlistNotWaiting,
  'WAITLIST_OFFER_EXPIRED' => l10n.classesWaitlistOfferExpired,
  'WAITLIST_ENTRY_NOT_OFFERED' => l10n.classesWaitlistOfferGone,
  _ => l10n.classesActionFailed,
};

/// The selected participant's waiting entry worth marking on a calendar
/// tile: only before a class that can still give them a place.
ClassWaitlistEntry? classWaitingEntry(GroupClass event, {DateTime? now}) {
  final entry = event.waitlist.ownEntry;
  if (entry == null ||
      !entry.isOpen ||
      event.isReserved ||
      event.status == 'CANCELLED' ||
      !(now ?? DateTime.now()).isBefore(event.startsAt)) {
    return null;
  }
  return entry;
}

String classWaitlistMarker(AppLocalizations l10n, ClassWaitlistEntry entry) =>
    entry.isOffered
    ? l10n.classesWaitlistOfferMarker
    : switch (entry.position) {
        final position? => l10n.classesWaitlistMarker(position),
        null => l10n.classesWaitlistShort,
      };

class ClassInfoLine extends StatelessWidget {
  const ClassInfoLine(this.icon, this.text, {super.key});

  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Row(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Icon(
        icon,
        size: 18,
        color: Theme.of(context).colorScheme.onSurfaceVariant,
      ),
      const SizedBox(width: 8),
      Expanded(child: Text(text)),
    ],
  );
}

class ClassMessage extends StatelessWidget {
  const ClassMessage(this.text, {super.key, this.icon = Icons.event_outlined});

  final String text;
  final IconData icon;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 40),
    child: Column(
      spacing: 12,
      children: [
        Icon(icon, size: 40, color: Theme.of(context).colorScheme.primary),
        Text(text, textAlign: TextAlign.center),
      ],
    ),
  );
}

class ClassesLoadError extends StatelessWidget {
  const ClassesLoadError({required this.onRetry, super.key});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ClassMessage(l10n.classesLoadError, icon: Icons.cloud_off_outlined),
        OutlinedButton(
          onPressed: onRetry,
          child: Text(l10n.scheduleRetryButton),
        ),
      ],
    );
  }
}
