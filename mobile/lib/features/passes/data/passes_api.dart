import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/core/network/dio_provider.dart';

/// Pass products on sale (`PassProductController` in the backend).
const passProductsPath = '/api/v1/pass-products';

/// Service types, to name the services a pass product pays for.
const serviceTypesPath = '/api/v1/service-types';

/// Passes of a customer profile the account acts for (`OwnPassController`).
String ownPassesPath(String profileId) =>
    '/api/v1/me/profiles/$profileId/passes';

/// An amount of money as the backend sends it: minor units and an ISO 4217
/// currency code.
class Money {
  const Money({required this.amountMinor, required this.currency});

  factory Money.fromJson(Map<String, dynamic> json) {
    return Money(
      amountMinor: json['amountMinor'] as int,
      currency: json['currency'] as String,
    );
  }

  final int amountMinor;
  final String currency;
}

/// Status of a customer's pass (`CustomerPassStatus` in the backend), as of
/// today.
enum PassStatus {
  pendingPayment,
  scheduled,
  active,
  suspended,
  expired,
  cancelled;

  static PassStatus fromCode(String code) => switch (code) {
    'PENDING_PAYMENT' => pendingPayment,
    'SCHEDULED' => scheduled,
    'ACTIVE' => active,
    'SUSPENDED' => suspended,
    'EXPIRED' => expired,
    'CANCELLED' => cancelled,
    _ => throw FormatException('Unknown pass status: $code'),
  };
}

/// A pass of the active customer profile.
class OwnPass {
  const OwnPass({
    required this.id,
    required this.productName,
    required this.entriesTotal,
    required this.entriesRemaining,
    required this.status,
    required this.validFrom,
    required this.validUntil,
  });

  factory OwnPass.fromJson(Map<String, dynamic> json) {
    return OwnPass(
      id: json['id'] as String,
      productName: json['productName'] as String,
      entriesTotal: json['entriesTotal'] as int?,
      entriesRemaining: json['entriesRemaining'] as int?,
      status: PassStatus.fromCode(json['status'] as String),
      validFrom: _date(json['validFrom']),
      validUntil: _date(json['validUntil']),
    );
  }

  final String id;
  final String productName;

  /// Number of entries, or `null` for unlimited entries.
  final int? entriesTotal;

  /// Entries left, or `null` for unlimited entries.
  final int? entriesRemaining;
  final PassStatus status;
  final DateTime? validFrom;

  /// The last day the pass is valid, inclusive.
  final DateTime? validUntil;

  static DateTime? _date(Object? value) =>
      value == null ? null : DateTime.parse(value as String);
}

/// Product kind (`PassProductKind` in the backend).
enum PassProductKind {
  entryPass,
  singleEntry,
  ptPackage,
  timePass,
  voucher;

  static PassProductKind fromCode(String code) => switch (code) {
    'ENTRY_PASS' => entryPass,
    'SINGLE_ENTRY' => singleEntry,
    'PT_PACKAGE' => ptPackage,
    'TIME_PASS' => timePass,
    'VOUCHER' => voucher,
    _ => throw FormatException('Unknown pass product kind: $code'),
  };
}

/// Who a product is meant for (`PassProductAudience` in the backend).
enum PassAudience {
  any,
  adult,
  child;

  static PassAudience fromCode(String code) => switch (code) {
    'ANY' => any,
    'ADULT' => adult,
    'CHILD' => child,
    _ => throw FormatException('Unknown pass audience: $code'),
  };
}

class PassVariant {
  const PassVariant({
    required this.id,
    required this.entriesCount,
    required this.validityDays,
    required this.price,
  });

  factory PassVariant.fromJson(Map<String, dynamic> json) {
    return PassVariant(
      id: json['id'] as String,
      entriesCount: json['entriesCount'] as int?,
      validityDays: json['validityDays'] as int,
      price: Money.fromJson(json['price'] as Map<String, dynamic>),
    );
  }

  final String id;

  /// Number of entries, or `null` for unlimited entries.
  final int? entriesCount;
  final int validityDays;
  final Money price;
}

/// A pass product on sale with its active variants.
class PassProduct {
  const PassProduct({
    required this.id,
    required this.name,
    required this.description,
    required this.kind,
    required this.audience,
    required this.suspensionAllowed,
    required this.maxSuspensionDays,
    required this.serviceTypeIds,
    required this.variants,
  });

  factory PassProduct.fromJson(Map<String, dynamic> json) {
    return PassProduct(
      id: json['id'] as String,
      name: json['name'] as String,
      description: json['description'] as String?,
      kind: PassProductKind.fromCode(json['kind'] as String),
      audience: PassAudience.fromCode(json['audience'] as String),
      suspensionAllowed: json['suspensionAllowed'] as bool,
      maxSuspensionDays: json['maxSuspensionDays'] as int?,
      serviceTypeIds: [
        for (final id in json['serviceTypeIds'] as List) id as String,
      ],
      variants: [
        for (final variant in json['variants'] as List)
          PassVariant.fromJson(variant as Map<String, dynamic>),
      ],
    );
  }

  final String id;
  final String name;
  final String? description;
  final PassProductKind kind;
  final PassAudience audience;
  final bool suspensionAllowed;

  /// The longest allowed suspension, or `null` without a limit.
  final int? maxSuspensionDays;
  final List<String> serviceTypeIds;
  final List<PassVariant> variants;
}

/// The studio's offer: products on sale and the names of the services they
/// pay for, by service type id.
class PassOffer {
  const PassOffer({required this.products, required this.serviceNames});

  final List<PassProduct> products;
  final Map<String, String> serviceNames;
}

class PassesApi {
  const PassesApi(this._dio);

  final Dio _dio;

  /// Passes of [profileId], newest first, without cancelled ones.
  Future<List<OwnPass>> fetchOwnPasses(String profileId) async {
    final items = await _items(ownPassesPath(profileId));
    return [for (final item in items) OwnPass.fromJson(item)];
  }

  /// Products on sale; the backend returns only active products and variants
  /// to clients.
  Future<PassOffer> fetchOffer() async {
    final [products, serviceTypes] = await Future.wait([
      _items(passProductsPath),
      _items(serviceTypesPath),
    ]);
    return PassOffer(
      products: [
        for (final product in products)
          if ((product['variants'] as List).isNotEmpty)
            PassProduct.fromJson(product),
      ],
      serviceNames: {
        for (final type in serviceTypes)
          type['id'] as String: type['name'] as String,
      },
    );
  }

  Future<List<Map<String, dynamic>>> _items(String path) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(path);
      return [
        for (final item in response.data!['items'] as List)
          item as Map<String, dynamic>,
      ];
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }
}

final passesApiProvider = Provider<PassesApi>(
  (ref) => PassesApi(ref.watch(dioProvider)),
);

/// Passes of a customer profile, by profile id.
final ownPassesProvider = FutureProvider.autoDispose
    .family<List<OwnPass>, String>(
      (ref, profileId) =>
          ref.watch(passesApiProvider).fetchOwnPasses(profileId),
    );

final passOfferProvider = FutureProvider.autoDispose<PassOffer>(
  (ref) => ref.watch(passesApiProvider).fetchOffer(),
);
