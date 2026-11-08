/// Without [withPass] the studio booked the place without a pass entry, so
/// there is no entry consequence.
Map<String, dynamic> reservationJson({
  String profileId = 'self',
  bool active = true,
  bool entryReturned = true,
  bool withPass = true,
}) => {
  'id': 'reservation-1',
  'eventId': 'class-1',
  'customerId': profileId,
  'status': active ? 'ACTIVE' : 'CANCELLED',
  'customerPassId': withPass ? 'pass-1' : null,
  'freeCancelUntil': '2030-11-02T10:00:00Z',
  'entryReturned': active || !withPass ? null : entryReturned,
  'cancellation': {
    'allowed': active,
    'reason': active ? null : 'RESERVATION_NOT_ACTIVE',
    'entryReturned': withPass ? entryReturned : null,
  },
  'version': 3,
};

Map<String, dynamic> waitlistEntryJson({
  String profileId = 'self',
  bool waiting = true,
  int position = 1,
  String? promotionBlockedReason,
}) => {
  'id': 'waitlist-1',
  'eventId': 'class-1',
  'customerId': profileId,
  'status': waiting ? 'WAITING' : 'CANCELLED',
  'customerPassId': 'pass-1',
  'joinedAt': '2030-11-01T10:00:00Z',
  'resolvedAt': waiting ? null : '2030-11-01T12:00:00Z',
  'reservationId': null,
  'position': waiting ? position : null,
  'promotionBlockedReason': waiting ? promotionBlockedReason : null,
};

/// [waitlistPosition] puts the participant on the waiting list; without
/// [waitlistReason] joining is refused because the class is not full.
Map<String, dynamic> groupClassJson({
  String profileId = 'self',
  String title = 'Pole dance',
  int? availablePlaces = 3,
  bool reserved = false,
  bool entryReturned = true,
  bool reservedWithPass = true,
  bool allowed = true,
  String? reason,
  int passCount = 1,
  bool waitlistAllowed = false,
  String? waitlistReason,
  int? waitlistPosition,
  int? waitingCount,
  String? promotionBlockedReason,
}) => {
  'id': 'class-1',
  'title': title,
  'serviceTypeName': 'Pole dance dla początkujących',
  'serviceTypeId': 'service-1',
  'calendarColor': '#DA70A6',
  'status': 'SCHEDULED',
  'startsAt': '2030-11-02T17:00:00Z',
  'endsAt': '2030-11-02T18:00:00Z',
  'capacity': availablePlaces == null ? null : 8,
  'activeReservations': availablePlaces == null ? 2 : 8 - availablePlaces,
  'availablePlaces': availablePlaces,
  'staff': [
    {'staffId': 'staff-1', 'displayName': 'Anna Trener', 'role': 'LEAD'},
  ],
  'resources': [
    {'resourceId': 'room-1', 'name': 'Sala Turkusowa'},
  ],
  'ownReservation': reserved
      ? reservationJson(
          profileId: profileId,
          entryReturned: entryReturned,
          withPass: reservedWithPass,
        )
      : null,
  'booking': {
    'allowed': allowed && !reserved,
    'reason': reserved ? 'ALREADY_RESERVED' : reason,
    'eligiblePasses': [
      for (var i = 1; i <= passCount; i++)
        {
          'id': 'pass-$i',
          'productName': 'Karnet $i',
          'entriesRemaining': i == 2 ? null : 4,
          'validUntil': '2030-11-30',
        },
    ],
  },
  'waitlist': {
    'allowed': waitlistAllowed && !reserved && waitlistPosition == null,
    'reason': reserved
        ? 'ALREADY_RESERVED'
        : waitlistPosition != null
        ? 'WAITLIST_ALREADY_JOINED'
        : waitlistAllowed
        ? null
        : waitlistReason ?? 'CLASS_NOT_FULL',
    'waitingCount': waitingCount ?? waitlistPosition ?? 0,
    'ownEntry': waitlistPosition == null || reserved
        ? null
        : waitlistEntryJson(
            profileId: profileId,
            position: waitlistPosition,
            promotionBlockedReason: promotionBlockedReason,
          ),
  },
};

/// A full class: booking reports CLASS_FULL and the waiting list decides.
Map<String, dynamic> fullClassJson({
  bool waitlistAllowed = true,
  String? waitlistReason,
  int? waitlistPosition,
  int? waitingCount,
  String? promotionBlockedReason,
  int passCount = 1,
}) => groupClassJson(
  availablePlaces: 0,
  allowed: false,
  reason: 'CLASS_FULL',
  passCount: passCount,
  waitlistAllowed: waitlistAllowed,
  waitlistReason: waitlistReason,
  waitlistPosition: waitlistPosition,
  waitingCount: waitingCount,
  promotionBlockedReason: promotionBlockedReason,
);

/// [json] in a class whose freed places must be confirmed in [minutes].
Map<String, dynamic> withConfirmation(
  Map<String, dynamic> json, {
  int minutes = 30,
}) => {
  ...json,
  'waitlist': {
    ...json['waitlist'] as Map<String, dynamic>,
    'confirmationMinutes': minutes,
  },
};

/// A full class whose freed place is held for the participant to confirm.
Map<String, dynamic> offeredClassJson({
  String? promotionBlockedReason,
  String offerExpiresAt = '2030-11-02T16:00:00Z',
}) {
  final json = withConfirmation(
    fullClassJson(
      waitlistPosition: 1,
      waitingCount: 0,
      promotionBlockedReason: promotionBlockedReason,
    ),
  );
  final waitlist = json['waitlist'] as Map<String, dynamic>;
  return {
    ...json,
    'waitlist': {
      ...waitlist,
      'ownEntry': {
        ...waitlist['ownEntry'] as Map<String, dynamic>,
        'status': 'OFFERED',
        'position': null,
        'offerExpiresAt': offerExpiresAt,
      },
    },
  };
}
