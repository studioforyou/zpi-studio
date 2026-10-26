import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/accounts/data/customer_profile.dart';
import 'package:mobile/features/accounts/data/customer_profile_api.dart';

/// Account scoping prevents another signed-in account from reusing this data.
typedef CustomerProfileKey = ({String accountId, String profileId});

class CustomerProfileController extends AsyncNotifier<CustomerProfile> {
  CustomerProfileController(this.key);

  final CustomerProfileKey key;

  @override
  Future<CustomerProfile> build() {
    return ref.watch(customerProfileApiProvider).fetchProfile(key.profileId);
  }

  Future<CustomerProfile> save({
    required String firstName,
    required String lastName,
    required DateTime? birthDate,
    required String phone,
    required String emergencyContactName,
    required String emergencyPhone,
  }) async {
    final profile = await ref
        .read(customerProfileApiProvider)
        .updateProfile(
          key.profileId,
          firstName: firstName,
          lastName: lastName,
          birthDate: birthDate,
          phone: phone,
          emergencyContactName: emergencyContactName,
          emergencyPhone: emergencyPhone,
        );
    if (ref.mounted) {
      state = AsyncData(profile);
    }
    return profile;
  }
}

final customerProfileProvider = AsyncNotifierProvider.autoDispose
    .family<CustomerProfileController, CustomerProfile, CustomerProfileKey>(
      CustomerProfileController.new,
      retry: (_, _) => null,
    );
