import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Every shared client widget on one scrolling page.
class _Gallery extends StatefulWidget {
  const _Gallery({required this.log});

  final List<String> log;

  @override
  State<_Gallery> createState() => _GalleryState();
}

class _GalleryState extends State<_Gallery> {
  var _tab = 0;
  var _segment = 'mine';
  var _switch = false;
  int? _choice = 1;

  @override
  Widget build(BuildContext context) {
    final log = widget.log;
    return Scaffold(
      body: Column(
        children: [
          PageHeader(
            title: 'Zajęcia',
            actions: [
              HeaderIconButton(
                icon: Icons.notifications_none,
                tooltip: 'Powiadomienia',
                badge: 3,
                onPressed: () => log.add('bell'),
              ),
            ],
          ),
          AppTabs(
            labels: const ['Moje karnety', 'Kup karnet'],
            selected: _tab,
            onChanged: (i) => setState(() => _tab = i),
          ),
          Expanded(
            child: ListView(
              children: [
                Padding(
                  padding: const EdgeInsets.all(20),
                  child: AppSegmented<String>(
                    segments: const [
                      AppSegment('mine', 'Moje'),
                      AppSegment('all', 'Wszystkie'),
                    ],
                    selected: _segment,
                    onChanged: (v) => setState(() => _segment = v),
                  ),
                ),
                const InfoStrip(
                  text: 'Masz 2 zajęcia do odrobienia',
                  actionLabel: 'Dowiedz się więcej',
                ),
                Padding(
                  padding: const EdgeInsets.all(20),
                  child: PickerField(
                    title: 'Karnet 8 wejść',
                    subtitle: 'ważny do 6.12',
                    status: const StatusDot(
                      label: 'Aktywny',
                      tone: StatusTone.ok,
                    ),
                    onTap: () => log.add('picker'),
                  ),
                ),
                AppRow(
                  title: 'Dane osobowe',
                  subtitle: 'Imię, telefon',
                  onTap: () => log.add('row'),
                ),
                const AppRow(title: 'Wiadomość', unread: true, value: '12:30'),
                AppRow(
                  title: 'Ciemny motyw',
                  bottomLine: true,
                  trailing: AppSwitch(
                    value: _switch,
                    onChanged: (v) => setState(() => _switch = v),
                  ),
                ),
                const SectionLabel('Ustawienia'),
                Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      AppButton.primary(
                        label: 'Zapisz się',
                        onPressed: () => log.add('primary'),
                      ),
                      const SizedBox(height: 8),
                      AppButtonPair(
                        first: AppButton(
                          label: 'Historia wejść',
                          small: true,
                          onPressed: () => log.add('history'),
                        ),
                        second: const AppButton(
                          label: 'Kup kolejny',
                          small: true,
                          onPressed: null,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          AppButton(
                            label: 'Wypisz się',
                            kind: AppButtonKind.destructive,
                            onPressed: () => log.add('cancel'),
                          ),
                          AppButton.ghost(
                            label: 'Zachowaj',
                            onPressed: () => log.add('ghost'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      ChoiceOptions<int>(
                        options: const [
                          ChoiceOption(value: 1, title: 'Karnet 8 wejść'),
                          ChoiceOption(
                            value: 2,
                            title: 'Karnet open',
                            subtitle: 'ważny do 1.01',
                          ),
                        ],
                        selected: _choice,
                        onChanged: (v) => setState(() => _choice = v),
                      ),
                      const SquareAvatar(
                        initials: 'AN',
                        background: Color(0xFF2E8A90),
                        foreground: Color(0xFFFFFFFF),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      bottomNavigationBar: AppNavBar(
        currentIndex: 1,
        onTap: (i) => log.add('nav$i'),
        items: const [
          AppNavItem(
            icon: Icons.confirmation_number_outlined,
            label: 'Karnety',
          ),
          AppNavItem(icon: Icons.calendar_today_outlined, label: 'Zajęcia'),
          AppNavItem(
            icon: Icons.chat_bubble_outline,
            label: 'Wiadomości',
            badge: 2,
          ),
          AppNavItem(icon: Icons.person_outline, label: 'Profil'),
        ],
      ),
    );
  }
}

/// A phone-wide screen tall enough to show the whole gallery.
void _tallPhone(WidgetTester tester) {
  tester.view.devicePixelRatio = 3;
  tester.view.physicalSize = const Size(390 * 3, 1400 * 3);
  addTearDown(tester.view.reset);
}

Widget _app(Brightness brightness, Widget home) => MaterialApp(
  theme: buildClientTheme(brightness),
  localizationsDelegates: AppLocalizations.localizationsDelegates,
  supportedLocales: AppLocalizations.supportedLocales,
  locale: const Locale('pl'),
  home: home,
);

void main() {
  for (final brightness in Brightness.values) {
    group('client widgets ($brightness)', () {
      testWidgets('lay out without overflow and react to taps', (tester) async {
        _tallPhone(tester);
        final log = <String>[];
        await tester.pumpWidget(_app(brightness, _Gallery(log: log)));

        expect(tester.takeException(), isNull);
        expect(find.text('Zajęcia'), findsWidgets);
        expect(find.text('3'), findsOneWidget);

        await tester.tap(find.text('Kup karnet'));
        await tester.tap(find.text('Wszystkie'));
        await tester.tap(find.text('Dane osobowe'));
        await tester.tap(find.text('Karnet 8 wejść').first);
        await tester.tap(find.byTooltip('Powiadomienia'));
        await tester.tap(find.text('Profil'));
        await tester.pump();
        expect(log, ['row', 'picker', 'bell', 'nav3']);

        for (final label in [
          'Zapisz się',
          'Historia wejść',
          'Kup kolejny',
          'Wypisz się',
          'Zachowaj',
        ]) {
          await tester.tap(find.text(label));
        }
        expect(log.skip(4), ['primary', 'history', 'cancel', 'ghost']);

        await tester.tap(find.text('Karnet open'));
        await tester.pump();
        expect(find.byIcon(Icons.check), findsOneWidget);
      });

      testWidgets('the switch toggles and reports its state', (tester) async {
        _tallPhone(tester);
        await tester.pumpWidget(_app(brightness, _Gallery(log: [])));
        expect(
          tester.getSemantics(find.byType(AppSwitch)),
          matchesSemantics(
            label: 'Ciemny motyw',
            hasToggledState: true,
            hasEnabledState: true,
            isEnabled: true,
            hasTapAction: true,
          ),
        );
        await tester.tap(find.byType(AppSwitch));
        await tester.pumpAndSettle();
        expect(
          tester.getSemantics(find.byType(AppSwitch)),
          matchesSemantics(
            label: 'Ciemny motyw',
            hasToggledState: true,
            isToggled: true,
            hasEnabledState: true,
            isEnabled: true,
            hasTapAction: true,
          ),
        );
      });

      testWidgets('a picker sheet returns the picked value', (tester) async {
        String? picked;
        await tester.pumpWidget(
          _app(
            brightness,
            Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: AppButton(
                    label: 'Otwórz',
                    onPressed: () async {
                      picked = await showPickerSheet<String>(
                        context,
                        kicker: 'Moje karnety',
                        title: 'Czyje karnety?',
                        selected: 'me',
                        options: const [
                          PickerOption(
                            value: 'me',
                            title: 'Ja',
                            initials: 'AN',
                          ),
                          PickerOption(
                            value: 'kid',
                            title: 'Zosia',
                            subtitle: 'dziecko',
                            initials: 'Z',
                          ),
                        ],
                      );
                    },
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Otwórz'));
        await tester.pumpAndSettle();
        expect(find.text('Czyje karnety?'), findsOneWidget);
        expect(find.byIcon(Icons.check), findsOneWidget);
        expect(tester.takeException(), isNull);

        await tester.tap(find.text('Zosia'));
        await tester.pumpAndSettle();
        expect(picked, 'kid');
        expect(find.text('Czyje karnety?'), findsNothing);
      });

      testWidgets('a sheet shows its blocks and closes', (tester) async {
        await tester.pumpWidget(
          _app(
            brightness,
            Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: AppButton(
                    label: 'Otwórz',
                    onPressed: () => showAppSheet<void>(
                      context,
                      builder: (_) => AppSheet(
                        kicker: 'Zajęcia grupowe',
                        title: 'Pilates',
                        subtitle: 'czw. 27.11 · 17:00–18:00',
                        footer: AppButton.primary(
                          label: 'Zapisz się',
                          onPressed: () {},
                        ),
                        children: [
                          SheetKeyValues(
                            entries: [
                              SheetKeyValues.text('Prowadzi', 'Beata Lis'),
                              SheetKeyValues.text('Sala', 'Duża'),
                            ],
                          ),
                          const SheetLine(
                            label: 'Karnet',
                            value: 'Karnet 8 wejść',
                            actionLabel: 'Zmień',
                          ),
                          const SheetHint('Zaraz po Twoich zajęciach'),
                          const ChangeStrip('Nowa godzina · zwykle 18:00'),
                          const SheetWarning(
                            'Możesz się wypisać do 12 godzin przed zajęciami.',
                          ),
                          const ConsequenceList(
                            items: [
                              Consequence(
                                icon: Icons.check,
                                title: 'Wejście wraca na karnet',
                                positive: true,
                              ),
                              Consequence(
                                icon: Icons.close,
                                title: 'Zwolnisz miejsce',
                                subtitle: 'Dostanie je pierwsza osoba z listy',
                              ),
                            ],
                          ),
                          const WaitlistBox(
                            number: '2',
                            title: 'Twoje miejsce na liście',
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('Otwórz'));
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(find.text('Pilates'), findsOneWidget);
        expect(find.text('Beata Lis'), findsOneWidget);
        expect(find.text('Zapisz się'), findsOneWidget);

        await tester.tap(find.byTooltip('Zamknij'));
        await tester.pumpAndSettle();
        expect(find.text('Pilates'), findsNothing);
      });
    });
  }

  test('initials take the first letters of two words', () {
    expect(SquareAvatar.initialsOf('Anna Nowak'), 'AN');
    expect(SquareAvatar.initialsOf(' zosia '), 'Z');
    expect(SquareAvatar.initialsOf('Jan Maria Rokita'), 'JM');
  });
}
