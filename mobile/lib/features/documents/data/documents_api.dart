import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Documents and consents of a customer profile the account acts for
/// (`OwnDocumentController` in the backend).
String ownDocumentsPath(String profileId) =>
    '/api/v1/me/profiles/$profileId/documents';

/// Kind of a document (`DocumentKind` in the backend).
enum DocumentKind {
  terms,
  gdpr,
  participationConsent,
  parentalConsent,
  marketingEmail,
  marketingSms,
  other;

  static DocumentKind fromCode(String code) => switch (code) {
    'TERMS' => terms,
    'GDPR' => gdpr,
    'PARTICIPATION_CONSENT' => participationConsent,
    'PARENTAL_CONSENT' => parentalConsent,
    'MARKETING_EMAIL' => marketingEmail,
    'MARKETING_SMS' => marketingSms,
    _ => other,
  };
}

/// Whether the profile accepted a document (`DocumentAcceptanceStatus` in
/// the backend).
enum AcceptanceStatus {
  accepted,
  reacceptanceRequired,
  notAccepted;

  static AcceptanceStatus fromCode(String code) => switch (code) {
    'ACCEPTED' => accepted,
    'REACCEPTANCE_REQUIRED' => reacceptanceRequired,
    'NOT_ACCEPTED' => notAccepted,
    _ => throw FormatException('Unknown acceptance status: $code'),
  };
}

/// A document or consent in force for a customer profile.
class OwnDocument {
  const OwnDocument({
    required this.id,
    required this.title,
    required this.kind,
    required this.required,
    required this.blocksBooking,
    required this.requiredFor,
    required this.versionId,
    required this.versionNo,
    required this.publishedAt,
    required this.hasFile,
    required this.status,
    required this.acceptedVersionNo,
    required this.acceptedAt,
    required this.revocable,
    required this.content,
  });

  factory OwnDocument.fromJson(Map<String, dynamic> json) {
    return OwnDocument(
      id: json['id'] as String,
      title: json['title'] as String,
      kind: DocumentKind.fromCode(json['kind'] as String),
      required: json['required'] as bool,
      blocksBooking: json['blocksBooking'] as bool,
      requiredFor: [
        for (final service in json['requiredFor'] as List)
          (service as Map<String, dynamic>)['name'] as String,
      ],
      versionId: json['versionId'] as String,
      versionNo: json['versionNo'] as int,
      publishedAt: _dateTime(json['publishedAt']),
      hasFile: json['hasFile'] as bool,
      status: AcceptanceStatus.fromCode(json['status'] as String),
      acceptedVersionNo: json['acceptedVersionNo'] as int?,
      acceptedAt: _dateTime(json['acceptedAt']),
      revocable: json['revocable'] as bool,
      content: json['content'] as String?,
    );
  }

  final String id;
  final String title;
  final DocumentKind kind;

  /// Whether it has to be accepted; `false` for optional consents.
  final bool required;

  /// Whether classes cannot be booked until it is accepted.
  final bool blocksBooking;

  /// Names of the services it is required for; empty when required for all.
  final List<String> requiredFor;

  /// The version in force, the one to accept.
  final String versionId;
  final int versionNo;
  final DateTime? publishedAt;

  /// Whether the content is a file rather than text.
  final bool hasFile;
  final AcceptanceStatus status;
  final int? acceptedVersionNo;
  final DateTime? acceptedAt;

  /// Whether the consent may be withdrawn: optional and given.
  final bool revocable;

  /// The text of the version in force; only on a single document.
  final String? content;

  bool get isAccepted => status == AcceptanceStatus.accepted;

  static DateTime? _dateTime(Object? value) =>
      value == null ? null : DateTime.parse(value as String).toLocal();
}

/// A failed documents request, with the backend's error code when it sent
/// one (e.g. `VERSION_CONFLICT`).
class DocumentApiException extends ApiException {
  const DocumentApiException({
    super.statusCode,
    required super.message,
    this.code,
  });

  factory DocumentApiException.fromDio(DioException e) {
    final data = e.response?.data;
    final base = ApiException.fromDio(e);
    return DocumentApiException(
      statusCode: base.statusCode,
      message: base.message,
      code: data is Map ? data['code'] as String? : null,
    );
  }

  final String? code;

  /// A newer version was published since the document was read.
  bool get isVersionConflict => code == 'VERSION_CONFLICT';
}

class DocumentsApi {
  const DocumentsApi(this._dio);

  final Dio _dio;

  /// Documents in force for [profileId], required ones first.
  Future<List<OwnDocument>> fetchDocuments(String profileId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ownDocumentsPath(profileId),
      );
      return [
        for (final item in response.data!['items'] as List)
          OwnDocument.fromJson(item as Map<String, dynamic>),
      ];
    } on DioException catch (e) {
      throw DocumentApiException.fromDio(e);
    }
  }

  /// One document with the text of the version in force.
  Future<OwnDocument> fetchDocument(String profileId, String documentId) =>
      _send(() => _dio.get('${ownDocumentsPath(profileId)}/$documentId'));

  /// Accepts [versionId], the version shown to the user.
  Future<OwnDocument> accept(
    String profileId,
    String documentId,
    String versionId,
  ) => _send(
    () => _dio.post(
      '${ownDocumentsPath(profileId)}/$documentId/acceptances',
      data: {'versionId': versionId},
    ),
  );

  /// Withdraws an optional consent.
  Future<OwnDocument> revoke(String profileId, String documentId) => _send(
    () => _dio.delete('${ownDocumentsPath(profileId)}/$documentId/acceptances'),
  );

  Future<OwnDocument> _send(
    Future<Response<dynamic>> Function() request,
  ) async {
    try {
      final response = await request();
      return OwnDocument.fromJson(response.data as Map<String, dynamic>);
    } on DioException catch (e) {
      throw DocumentApiException.fromDio(e);
    }
  }
}

final documentsApiProvider = Provider<DocumentsApi>(
  (ref) => DocumentsApi(ref.watch(dioProvider)),
);

/// Documents of a customer profile, by profile id.
final ownDocumentsProvider = FutureProvider.autoDispose
    .family<List<OwnDocument>, String>(
      (ref, profileId) =>
          ref.watch(documentsApiProvider).fetchDocuments(profileId),
    );

/// One document with its text, by profile and document id.
final ownDocumentProvider = FutureProvider.autoDispose
    .family<OwnDocument, ({String profileId, String documentId})>(
      (ref, key) => ref
          .watch(documentsApiProvider)
          .fetchDocument(key.profileId, key.documentId),
    );
