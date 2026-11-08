import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// The studio's trainers by name (`TrainerDirectoryController` in the
/// backend).
const studioTrainersPath = '/api/v1/trainers';

/// A trainer as every signed-in user may see them.
class StudioTrainer {
  const StudioTrainer({required this.staffId, required this.displayName});

  factory StudioTrainer.fromJson(Map<String, dynamic> json) => StudioTrainer(
    staffId: json['staffId'] as String,
    displayName: json['displayName'] as String,
  );

  final String staffId;
  final String displayName;
}

class StudioTrainersApi {
  const StudioTrainersApi(this._dio);

  final Dio _dio;

  /// Active trainers, sorted by last and first name.
  Future<List<StudioTrainer>> fetchAll() async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(studioTrainersPath);
      return [
        for (final item in response.data!['items'] as List)
          StudioTrainer.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final studioTrainersApiProvider = Provider<StudioTrainersApi>(
  (ref) => StudioTrainersApi(ref.watch(dioProvider)),
);

/// Every active trainer, for the trainer filter of the class offer. When the
/// list cannot be loaded, the filter offers the trainers of loaded classes.
final studioTrainersProvider = FutureProvider.autoDispose<List<StudioTrainer>>(
  (ref) => ref.watch(studioTrainersApiProvider).fetchAll(),
);
