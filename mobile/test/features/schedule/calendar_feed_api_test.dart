import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/calendar_feed_api.dart';

class _FeedAdapter implements HttpClientAdapter {
  int statusCode = 200;
  Object? body;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    requests.add(options);
    return ResponseBody.fromString(
      body == null ? '' : jsonEncode(body),
      statusCode,
      headers: {
        Headers.contentTypeHeader: [Headers.jsonContentType],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}

final _link = CalendarFeedLink(
  url: 'https://api.example.com/api/v1/calendar-feeds/abc/events.ics',
  webcalUrl: 'webcal://api.example.com/api/v1/calendar-feeds/abc/events.ics',
  createdAt: DateTime.utc(2026, 11, 26, 15),
);

void main() {
  late _FeedAdapter adapter;
  late Dio dio;
  late CalendarFeedApi api;

  setUp(() {
    adapter = _FeedAdapter();
    dio = Dio()..httpClientAdapter = adapter;
    api = CalendarFeedApi(dio);
  });

  tearDown(() => dio.close(force: true));

  test('reads whether the account has a working link', () async {
    adapter.body = {'active': true, 'createdAt': '2026-11-26T15:00:00Z'};

    final status = await api.fetchStatus();

    expect(adapter.requests.single.method, 'GET');
    expect(adapter.requests.single.path, '/api/v1/me/calendar-feed');
    expect(status.active, isTrue);
    expect(status.createdAt, DateTime.utc(2026, 11, 26, 15));
  });

  test('creates a link and turns it off', () async {
    adapter
      ..statusCode = 201
      ..body = _link.toJson();

    final link = await api.create();
    expect(adapter.requests.last.method, 'POST');
    expect(link.url, _link.url);
    expect(link.webcalUrl, _link.webcalUrl);
    expect(link.createdAt, _link.createdAt);

    adapter
      ..statusCode = 204
      ..body = null;
    await api.turnOff();
    expect(adapter.requests.last.method, 'DELETE');
    expect(adapter.requests.last.path, '/api/v1/me/calendar-feed');
  });

  test('reports failures as API exceptions', () async {
    adapter
      ..statusCode = 403
      ..body = {'code': 'ACCESS_DENIED'};

    expect(api.create(), throwsA(isA<ApiException>()));
  });

  test('limits the addresses to the chosen part of the schedule', () {
    expect(_link.httpsFor(CalendarFeedScope.all), _link.url);
    expect(
      _link.httpsFor(CalendarFeedScope.trainer),
      '${_link.url}?role=TRAINER',
    );
    expect(
      _link.webcalFor(CalendarFeedScope.client),
      '${_link.webcalUrl}?role=CLIENT',
    );
  });

  test('opens Google Calendar with the https address of the feed', () {
    final google = _link.googleFor(CalendarFeedScope.client);

    expect(google.host, 'calendar.google.com');
    expect(google.path, '/calendar/render');
    expect(google.queryParameters['cid'], '${_link.url}?role=CLIENT');
  });
}
