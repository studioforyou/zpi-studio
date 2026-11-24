import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';

/// The kinds of classes (service types) a participant wants to hear about:
/// when booking of such a class opens, they get a notification.
class ClassInterestsApi {
  ClassInterestsApi(this._dio);

  final Dio _dio;

  String _path(String profileId) =>
      '/api/v1/me/customer-profiles/$profileId/interests';

  /// The ids of the marked service types.
  Future<Set<String>> fetch(String profileId) async =>
      _ids((await _dio.get<Map<String, dynamic>>(_path(profileId))).data!);

  /// Marks ([interested]) or unmarks the service type; returns the marked
  /// ones after the change.
  Future<Set<String>> change(
    String profileId,
    String serviceTypeId, {
    required bool interested,
  }) async {
    final path = '${_path(profileId)}/$serviceTypeId';
    final response = interested
        ? await _dio.put<Map<String, dynamic>>(path)
        : await _dio.delete<Map<String, dynamic>>(path);
    return _ids(response.data!);
  }

  static Set<String> _ids(Map<String, dynamic> json) => {
    for (final id in json['serviceTypeIds'] as List) id as String,
  };
}

final classInterestsApiProvider = Provider<ClassInterestsApi>(
  (ref) => ClassInterestsApi(ref.watch(dioProvider)),
);

/// The participant's marked service types, kept up to date by [change].
class ClassInterestsController extends AsyncNotifier<Set<String>> {
  ClassInterestsController(this.scope);

  final BookingScope scope;

  @override
  Future<Set<String>> build() =>
      ref.watch(classInterestsApiProvider).fetch(scope.profileId);

  Future<void> change(String serviceTypeId, {required bool interested}) async {
    final marked = await ref
        .read(classInterestsApiProvider)
        .change(scope.profileId, serviceTypeId, interested: interested);
    if (ref.mounted) state = AsyncData(marked);
  }
}

final classInterestsProvider = AsyncNotifierProvider.autoDispose
    .family<ClassInterestsController, Set<String>, BookingScope>(
      ClassInterestsController.new,
      retry: (_, _) => null,
    );
