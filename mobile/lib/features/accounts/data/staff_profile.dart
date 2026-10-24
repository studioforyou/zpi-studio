/// The signed-in trainer's personal and contact details.
class StaffProfile {
  const StaffProfile({
    required this.id,
    required this.firstName,
    required this.lastName,
    required this.phone,
  });

  factory StaffProfile.fromJson(Map<String, dynamic> json) {
    return StaffProfile(
      id: json['id'] as String,
      firstName: json['firstName'] as String,
      lastName: json['lastName'] as String,
      phone: json['phone'] as String?,
    );
  }

  final String id;
  final String firstName;
  final String lastName;
  final String? phone;

  String get displayName => '$firstName $lastName';
}
