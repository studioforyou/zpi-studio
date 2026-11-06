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

/// Pass orders of a customer profile the account acts for
/// (`OwnPassOrderController`).
String ownPassOrdersPath(String profileId) =>
    '/api/v1/me/profiles/$profileId/pass-orders';

/// The simulated payment of a pass order, until a payment provider is
/// connected.
String passOrderMockPaymentPath(String orderId) =>
    '/api/v1/me/pass-orders/$orderId/mock-payment';

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
    this.variantId,
    required this.productName,
    required this.entriesTotal,
    required this.entriesRemaining,
    required this.status,
    required this.validFrom,
    required this.validUntil,
    this.expiringSoon = false,
  });

  factory OwnPass.fromJson(Map<String, dynamic> json) {
    return OwnPass(
      id: json['id'] as String,
      variantId: json['variantId'] as String?,
      productName: json['productName'] as String,
      entriesTotal: json['entriesTotal'] as int?,
      entriesRemaining: json['entriesRemaining'] as int?,
      status: PassStatus.fromCode(json['status'] as String),
      validFrom: _date(json['validFrom']),
      validUntil: _date(json['validUntil']),
      expiringSoon: json['expiringSoon'] as bool? ?? false,
    );
  }

  final String id;

  /// The variant the pass was assigned or bought with, to buy it again.
  final String? variantId;
  final String productName;

  /// Number of entries, or `null` for unlimited entries.
  final int? entriesTotal;

  /// Entries left, or `null` for unlimited entries.
  final int? entriesRemaining;
  final PassStatus status;
  final DateTime? validFrom;

  /// The last day the pass is valid, inclusive.
  final DateTime? validUntil;

  /// An active or suspended pass with at most 7 days or at most 2 entries
  /// left, as the backend decides.
  final bool expiringSoon;

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

/// An order for one pass, with the pass it buys.
class PassOrder {
  const PassOrder({
    required this.id,
    required this.paid,
    required this.productName,
    required this.price,
    required this.passStatus,
    required this.validFrom,
    required this.validUntil,
  });

  factory PassOrder.fromJson(Map<String, dynamic> json) {
    final pass = json['pass'] as Map<String, dynamic>;
    return PassOrder(
      id: json['id'] as String,
      paid: json['status'] == 'PAID',
      productName: json['productName'] as String,
      price: Money.fromJson(json['price'] as Map<String, dynamic>),
      passStatus: PassStatus.fromCode(pass['status'] as String),
      validFrom: DateTime.parse(pass['validFrom'] as String),
      validUntil: DateTime.parse(pass['validUntil'] as String),
    );
  }

  final String id;
  final bool paid;
  final String productName;
  final Money price;
  final PassStatus passStatus;
  final DateTime validFrom;

  /// The last day the pass is valid, inclusive.
  final DateTime validUntil;
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

  /// Orders a pass of [variantId] for [profileId]. The pass starts on
  /// [startsOn], or the day after [startAfterPassId] ends; give exactly one.
  /// The client must have accepted the terms of sale.
  Future<PassOrder> placeOrder(
    String profileId, {
    required String variantId,
    DateTime? startsOn,
    String? startAfterPassId,
  }) async {
    return _order(
      () => _dio.post<Map<String, dynamic>>(
        ownPassOrdersPath(profileId),
        data: {
          'variantId': variantId,
          if (startsOn != null) 'startsOn': _isoDate(startsOn),
          'startAfterPassId': ?startAfterPassId,
          'termsAccepted': true,
        },
      ),
    );
  }

  /// Pays the order with the simulated payment: it is paid at once and its
  /// pass becomes active or scheduled.
  Future<PassOrder> payOrderWithMock(String orderId) async {
    return _order(
      () => _dio.post<Map<String, dynamic>>(passOrderMockPaymentPath(orderId)),
    );
  }

  Future<PassOrder> _order(
    Future<Response<Map<String, dynamic>>> Function() request,
  ) async {
    try {
      final response = await request();
      return PassOrder.fromJson(response.data!);
    } on DioException catch (e) {
      throw ApiException.fromDio(e);
    }
  }

  /// `2026-11-20`, the API's date format.
  static String _isoDate(DateTime date) =>
      '${date.year.toString().padLeft(4, '0')}-'
      '${date.month.toString().padLeft(2, '0')}-'
      '${date.day.toString().padLeft(2, '0')}';

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
