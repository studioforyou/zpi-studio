/// Personal and contact details of a customer profile the account acts for:
/// its own profile or a child it is a guardian of.
class CustomerProfile {
  const CustomerProfile({
    required this.id,
    required this.isChild,
    required this.firstName,
    required this.lastName,
    required this.birthDate,
    required this.phone,
    required this.emergencyContactName,
    required this.emergencyPhone,
  });

  factory CustomerProfile.fromJson(Map<String, dynamic> json) {
    final birthDate = json['birthDate'] as String?;
    return CustomerProfile(
      id: json['id'] as String,
      isChild: json['kind'] == 'CHILD',
      firstName: json['firstName'] as String,
      lastName: json['lastName'] as String,
      birthDate: birthDate == null ? null : DateTime.parse(birthDate),
      phone: json['phone'] as String?,
      emergencyContactName: json['emergencyContactName'] as String?,
      emergencyPhone: json['emergencyPhone'] as String?,
    );
  }

  final String id;
  final bool isChild;
  final String firstName;
  final String lastName;

  /// A calendar date; only year, month and day are meaningful.
  final DateTime? birthDate;
  final String? phone;
  final String? emergencyContactName;
  final String? emergencyPhone;

  String get displayName => '$firstName $lastName';
}
