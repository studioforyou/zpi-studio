import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/accounts/data/staff_profile.dart';
import 'package:mobile/features/accounts/data/staff_profile_api.dart';

/// Keeps trainer details separate from the session's customer profiles.
class StaffProfileController extends AsyncNotifier<StaffProfile> {
  StaffProfileController(this.accountId);

  // Account scoping prevents another signed-in account from reusing this data.
  final String accountId;

  @override
  Future<StaffProfile> build() {
    return ref.watch(staffProfileApiProvider).fetchProfile();
  }

  Future<StaffProfile> save({
    required String firstName,
    required String lastName,
    required String phone,
  }) async {
    final profile = await ref
        .read(staffProfileApiProvider)
        .updateProfile(firstName: firstName, lastName: lastName, phone: phone);
    if (ref.mounted) {
      state = AsyncData(profile);
    }
    return profile;
  }
}

final staffProfileProvider = AsyncNotifierProvider.autoDispose
    .family<StaffProfileController, StaffProfile, String>(
      StaffProfileController.new,
      retry: (_, _) => null,
    );
