import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/dio_provider.dart';

const invitationsPath = '/api/v1/invitations';

enum InvitationFailureReason { notFound, expired, used, network, unknown }

class InvitationFailure implements Exception {
  const InvitationFailure(this.reason);

  final InvitationFailureReason reason;

  @override
  String toString() => 'InvitationFailure($reason)';
}

/// The invitation as returned by `GET /api/v1/invitations/{token}`.
class InvitationDetails {
  const InvitationDetails({
    required this.email,
    required this.displayName,
    required this.roles,
    required this.expiresAt,
  });

  factory InvitationDetails.fromJson(Map<String, dynamic> json) {
    return InvitationDetails(
      email: json['email'] as String,
      displayName: json['displayName'] as String,
      roles: [for (final role in json['roles'] as List) role as String],
      expiresAt: DateTime.parse(json['expiresAt'] as String),
    );
  }

  final String email;
  final String displayName;

  /// Backend role codes, e.g. `CLIENT`, `TRAINER`.
  final List<String> roles;
  final DateTime expiresAt;
}

/// The public invitation endpoints: they take the token from the e-mail
/// link instead of a signed-in user.
class InvitationApi {
  const InvitationApi(this._dio);

  final Dio _dio;

  /// Throws [InvitationFailure] when the invitation cannot be used.
  Future<InvitationDetails> fetch(String token) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(_path(token));
      return InvitationDetails.fromJson(response.data!);
    } on DioException catch (e) {
      throw InvitationFailure(_reasonFor(e));
    }
  }

  /// Activates the account with [password]. Throws [InvitationFailure] when
  /// the invitation cannot be used.
  Future<void> accept(String token, String password) async {
    try {
      await _dio.post<void>(
        '${_path(token)}/accept',
        data: {'password': password},
      );
    } on DioException catch (e) {
      throw InvitationFailure(_reasonFor(e));
    }
  }

  static String _path(String token) =>
      '$invitationsPath/${Uri.encodeComponent(token)}';

  static InvitationFailureReason _reasonFor(DioException e) {
    final response = e.response;
    if (response == null) {
      return InvitationFailureReason.network;
    }
    final data = response.data;
    final code = data is Map ? data['code'] : null;
    return switch (code) {
      'INVITATION_NOT_FOUND' => InvitationFailureReason.notFound,
      'INVITATION_EXPIRED' => InvitationFailureReason.expired,
      'INVITATION_ALREADY_USED' => InvitationFailureReason.used,
      _ => InvitationFailureReason.unknown,
    };
  }
}

final invitationApiProvider = Provider<InvitationApi>(
  (ref) => InvitationApi(ref.watch(dioProvider)),
);
