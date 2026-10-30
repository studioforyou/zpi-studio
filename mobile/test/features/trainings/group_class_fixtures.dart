Map<String, dynamic> reservationJson({
  String profileId = 'self',
  bool active = true,
  bool entryReturned = true,
}) => {
  'id': 'reservation-1',
  'eventId': 'class-1',
  'customerId': profileId,
  'status': active ? 'ACTIVE' : 'CANCELLED',
  'customerPassId': 'pass-1',
  'freeCancelUntil': '2030-11-02T10:00:00Z',
  'entryReturned': active ? null : entryReturned,
  'cancellation': {
    'allowed': active,
    'reason': active ? null : 'RESERVATION_NOT_ACTIVE',
    'entryReturned': entryReturned,
  },
  'version': 3,
};

Map<String, dynamic> groupClassJson({
  String profileId = 'self',
  String title = 'Pole dance',
  int? availablePlaces = 3,
  bool reserved = false,
  bool entryReturned = true,
  bool allowed = true,
  String? reason,
  int passCount = 1,
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
      ? reservationJson(profileId: profileId, entryReturned: entryReturned)
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
};
