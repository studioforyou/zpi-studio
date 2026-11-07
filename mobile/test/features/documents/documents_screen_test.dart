import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/documents/data/documents_api.dart';
import 'package:mobile/features/documents/presentation/document_screen.dart';
import 'package:mobile/features/documents/presentation/documents_screen.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

const _session = Session(
  accountId: 'parent-user',
  displayName: 'Anna Kowalska',
  roles: [AppRole.client],
  activeRole: AppRole.client,
  staffProfileId: null,
  profiles: [
    Profile(id: 'self', displayName: 'Anna Kowalska', isChild: false),
    Profile(id: 'child-1', displayName: 'Jan Kowalski', isChild: true),
  ],
  activeProfileId: 'self',
);

class _SignedInAuthController extends AuthController {
  _SignedInAuthController(this.session);

  final Session session;

  @override
  AuthState build() => Authenticated(session);
}

OwnDocument _document(
  String id,
  String title, {
  DocumentKind kind = DocumentKind.terms,
  bool required = true,
  bool blocksBooking = true,
  List<String> requiredFor = const [],
  String versionId = 'v2',
  AcceptanceStatus status = AcceptanceStatus.notAccepted,
  bool hasFile = false,
  String? content,
}) {
  final accepted = status != AcceptanceStatus.notAccepted;
  return OwnDocument(
    id: id,
    title: title,
    kind: kind,
    required: required,
    blocksBooking: blocksBooking,
    requiredFor: requiredFor,
    versionId: versionId,
    versionNo: 2,
    publishedAt: DateTime(2026, 9, 30),
    hasFile: hasFile,
    status: status,
    acceptedVersionNo: accepted ? 1 : null,
    acceptedAt: accepted ? DateTime(2026, 10, 12) : null,
    revocable: !required && status == AcceptanceStatus.accepted,
    content: content,
  );
}

/// Keeps documents in memory and records what the screens send.
class _FakeDocumentsApi implements DocumentsApi {
  _FakeDocumentsApi(List<OwnDocument> documents)
    : documents = {for (final d in documents) d.id: d};

  final Map<String, OwnDocument> documents;
  final List<String> calls = [];
  DocumentApiException? failure;
  bool failLoading = false;

  @override
  Future<List<OwnDocument>> fetchDocuments(String profileId) async {
    calls.add('list $profileId');
    if (failLoading) {
      throw const DocumentApiException(statusCode: 500, message: 'error');
    }
    return documents.values.toList();
  }

  @override
  Future<OwnDocument> fetchDocument(String profileId, String documentId) async {
    calls.add('get $profileId $documentId');
    return documents[documentId]!;
  }

  @override
  Future<OwnDocument> accept(
    String profileId,
    String documentId,
    String versionId,
  ) async {
    calls.add('accept $profileId $documentId $versionId');
    final failure = this.failure;
    if (failure != null) {
      throw failure;
    }
    return documents[documentId] = _copy(
      documents[documentId]!,
      AcceptanceStatus.accepted,
    );
  }

  @override
  Future<OwnDocument> revoke(String profileId, String documentId) async {
    calls.add('revoke $profileId $documentId');
    return documents[documentId] = _copy(
      documents[documentId]!,
      AcceptanceStatus.notAccepted,
    );
  }

  static OwnDocument _copy(OwnDocument d, AcceptanceStatus status) => _document(
    d.id,
    d.title,
    kind: d.kind,
    required: d.required,
    blocksBooking: d.blocksBooking,
    requiredFor: d.requiredFor,
    versionId: d.versionId,
    status: status,
    hasFile: d.hasFile,
    content: d.content,
  );
}

Future<void> _pump(
  WidgetTester tester,
  Widget screen,
  _FakeDocumentsApi api, {
  Session session = _session,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        authControllerProvider.overrideWith(
          () => _SignedInAuthController(session),
        ),
        documentsApiProvider.overrideWithValue(api),
      ],
      child: MaterialApp(
        locale: const Locale('pl'),
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        home: screen,
      ),
    ),
  );
  await tester.pumpAndSettle();
}

void main() {
  group('DocumentsScreen', () {
    testWidgets('lists required documents and consents with their status', (
      tester,
    ) async {
      final api = _FakeDocumentsApi([
        _document(
          'terms',
          'Regulamin studia',
          status: AcceptanceStatus.reacceptanceRequired,
        ),
        _document(
          'gdpr',
          'Klauzula RODO',
          kind: DocumentKind.gdpr,
          blocksBooking: false,
          status: AcceptanceStatus.accepted,
        ),
        _document(
          'pole',
          'Zgoda pole dance',
          kind: DocumentKind.participationConsent,
          requiredFor: ['Pole dance', 'Pole sport'],
        ),
        _document(
          'news',
          'Newsletter e-mail',
          kind: DocumentKind.marketingEmail,
          required: false,
          blocksBooking: false,
        ),
      ]);

      await _pump(tester, const DocumentsScreen(), api);

      expect(find.textContaining('Profil:'), findsNothing);
      expect(find.text('Do akceptacji: 2'), findsOneWidget);
      expect(
        find.text('Bez tych dokumentów nie zapiszesz się na zajęcia.'),
        findsOneWidget,
      );
      expect(find.text('Wymagane dokumenty'), findsOneWidget);
      expect(find.text('Nowa wersja – zaakceptuj ponownie'), findsOneWidget);
      expect(find.text('Zaakceptowano 12.10.2026'), findsOneWidget);
      expect(
        find.text('Wymaga akceptacji\nDotyczy: Pole dance, Pole sport'),
        findsOneWidget,
      );
      expect(find.text('Zgody dobrowolne'), findsOneWidget);
      expect(find.text('Brak zgody'), findsOneWidget);
      expect(api.calls, ['list self']);
    });

    testWidgets('gives and withdraws an optional consent with the switch', (
      tester,
    ) async {
      final api = _FakeDocumentsApi([
        _document(
          'news',
          'Newsletter e-mail',
          kind: DocumentKind.marketingEmail,
          required: false,
          blocksBooking: false,
          versionId: 'news-v1',
        ),
      ]);
      await _pump(tester, const DocumentsScreen(), api);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(api.calls, contains('accept self news news-v1'));
      expect(find.text('Zgoda została zapisana.'), findsOneWidget);
      expect(find.text('Zgoda wyrażona 12.10.2026'), findsOneWidget);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(find.text('Wycofać zgodę?'), findsOneWidget);
      await tester.tap(find.text('Anuluj'));
      await tester.pumpAndSettle();
      expect(api.calls.where((c) => c.startsWith('revoke')), isEmpty);

      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Wycofaj'));
      await tester.pumpAndSettle();
      expect(api.calls, contains('revoke self news'));
      expect(find.text('Brak zgody'), findsOneWidget);
    });

    testWidgets('shows a retry button when loading fails', (tester) async {
      final api = _FakeDocumentsApi([_document('terms', 'Regulamin studia')])
        ..failLoading = true;
      await _pump(tester, const DocumentsScreen(), api);

      expect(find.text('Nie udało się pobrać dokumentów.'), findsOneWidget);
      api.failLoading = false;
      await tester.tap(find.text('Spróbuj ponownie'));
      await tester.pumpAndSettle();
      expect(find.text('Regulamin studia'), findsOneWidget);
    });

    testWidgets(
      'explains that an account without a customer profile has none',
      (tester) async {
        const trainer = Session(
          accountId: 'trainer',
          displayName: 'Tomasz Nowak',
          roles: [AppRole.client],
          activeRole: AppRole.client,
          staffProfileId: null,
          profiles: [],
          activeProfileId: null,
        );
        final api = _FakeDocumentsApi([]);
        await _pump(tester, const DocumentsScreen(), api, session: trainer);

        expect(find.textContaining('nie ma profilu klienta'), findsOneWidget);
        expect(api.calls, isEmpty);
      },
    );
  });

  group('DocumentScreen', () {
    testWidgets('accepts the version in force after confirming', (
      tester,
    ) async {
      final api = _FakeDocumentsApi([
        _document(
          'terms',
          'Regulamin studia',
          content: 'Bezpłatne odwołanie do 12 godzin przed zajęciami.',
        ),
      ]);
      await _pump(tester, const DocumentScreen(documentId: 'terms'), api);

      expect(
        find.text('Bezpłatne odwołanie do 12 godzin przed zajęciami.'),
        findsOneWidget,
      );
      expect(find.text('Wersja 2 z 30.09.2026'), findsOneWidget);
      final button = find.widgetWithText(FilledButton, 'Akceptuję');
      expect(tester.widget<FilledButton>(button).onPressed, isNull);

      await tester.tap(
        find.text('Przeczytałem/am dokument i akceptuję jego treść'),
      );
      await tester.pump();
      await tester.tap(button);
      await tester.pumpAndSettle();

      expect(api.calls, contains('accept self terms v2'));
      expect(find.text('Dokument został zaakceptowany.'), findsOneWidget);
      expect(find.text('Zaakceptowano 12.10.2026'), findsOneWidget);
      expect(find.byType(FilledButton), findsNothing);
      expect(
        find.textContaining('nie można wycofać w aplikacji'),
        findsOneWidget,
      );
    });

    testWidgets('tells the user to read a newer version first', (tester) async {
      final api =
          _FakeDocumentsApi([
              _document('terms', 'Regulamin studia', content: 'Treść'),
            ])
            ..failure = const DocumentApiException(
              statusCode: 409,
              message: 'conflict',
              code: 'VERSION_CONFLICT',
            );
      await _pump(tester, const DocumentScreen(documentId: 'terms'), api);

      await tester.tap(find.byType(Checkbox));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Akceptuję'));
      await tester.pumpAndSettle();

      expect(
        find.text(
          'Studio opublikowało nową wersję dokumentu. '
          'Przeczytaj ją przed akceptacją.',
        ),
        findsOneWidget,
      );
      expect(
        api.calls.where((c) => c == 'get self terms'),
        hasLength(2),
        reason: 'the document is read again',
      );
    });

    testWidgets('a guardian accepts for the active child', (tester) async {
      const childActive = Session(
        accountId: 'parent-user',
        displayName: 'Anna Kowalska',
        roles: [AppRole.client],
        activeRole: AppRole.client,
        staffProfileId: null,
        profiles: [
          Profile(id: 'self', displayName: 'Anna Kowalska', isChild: false),
          Profile(id: 'child-1', displayName: 'Jan Kowalski', isChild: true),
        ],
        activeProfileId: 'child-1',
      );
      final api = _FakeDocumentsApi([
        _document(
          'parental',
          'Zgoda rodzica',
          kind: DocumentKind.parentalConsent,
          hasFile: true,
        ),
      ]);
      await _pump(
        tester,
        const DocumentScreen(documentId: 'parental'),
        api,
        session: childActive,
      );

      expect(find.text('W imieniu: Jan Kowalski'), findsOneWidget);
      expect(
        find.textContaining('Treść tego dokumentu jest w pliku'),
        findsOne,
      );
      await tester.tap(find.byType(Checkbox));
      await tester.pump();
      await tester.tap(find.widgetWithText(FilledButton, 'Akceptuję'));
      await tester.pumpAndSettle();
      expect(api.calls, contains('accept child-1 parental v2'));
    });

    testWidgets('withdraws a given consent', (tester) async {
      final api = _FakeDocumentsApi([
        _document(
          'news',
          'Newsletter e-mail',
          kind: DocumentKind.marketingEmail,
          required: false,
          status: AcceptanceStatus.accepted,
          content: 'Zgoda na newsletter.',
        ),
      ]);
      await _pump(tester, const DocumentScreen(documentId: 'news'), api);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Wycofaj zgodę'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(TextButton, 'Wycofaj'));
      await tester.pumpAndSettle();

      expect(api.calls, contains('revoke self news'));
      expect(find.text('Zgoda została wycofana.'), findsOneWidget);
      expect(find.widgetWithText(FilledButton, 'Wyrażam zgodę'), findsOne);
    });

    testWidgets('fits a small screen with large text', (tester) async {
      tester.view.physicalSize = const Size(320, 568);
      tester.view.devicePixelRatio = 1;
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.view.reset);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      final api = _FakeDocumentsApi([
        _document(
          'pole',
          'Zgoda na udział w zajęciach pole dance',
          kind: DocumentKind.participationConsent,
          requiredFor: ['Pole dance'],
          content: 'Oświadczam, że nie mam przeciwwskazań. ' * 20,
        ),
      ]);

      await _pump(tester, const DocumentScreen(documentId: 'pole'), api);
      expect(tester.takeException(), isNull);
      await _pump(tester, const DocumentsScreen(), api);
      expect(tester.takeException(), isNull);
    });
  });

  test('parses a document from the backend', () {
    final document = OwnDocument.fromJson({
      'id': 'd1',
      'title': 'Regulamin studia',
      'kind': 'TERMS',
      'required': true,
      'blocksBooking': true,
      'requiredFor': [
        {'id': 's1', 'name': 'Pole dance'},
      ],
      'versionId': 'v2',
      'versionNo': 2,
      'publishedAt': '2026-09-30T10:00:00Z',
      'hasFile': false,
      'status': 'REACCEPTANCE_REQUIRED',
      'acceptedVersionNo': 1,
      'acceptedAt': '2026-05-01T08:00:00Z',
      'revocable': false,
      'content': null,
    });

    expect(document.kind, DocumentKind.terms);
    expect(document.requiredFor, ['Pole dance']);
    expect(document.status, AcceptanceStatus.reacceptanceRequired);
    expect(document.isAccepted, isFalse);
    expect(document.acceptedVersionNo, 1);
    expect(document.publishedAt!.toUtc(), DateTime.utc(2026, 9, 30, 10));
    expect(DocumentKind.fromCode('SOMETHING_NEW'), DocumentKind.other);
  });
}
