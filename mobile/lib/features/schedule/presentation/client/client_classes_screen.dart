import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/app/router/app_router.dart';
import 'package:mobile/app/shell/client_shell.dart';
import 'package:mobile/core/auth/app_role.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/session.dart';
import 'package:mobile/features/accounts/data/profile_view_preferences.dart';
import 'package:mobile/features/schedule/data/client_schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/data/schedule_service_types.dart';
import 'package:mobile/features/schedule/data/series_changes.dart';
import 'package:mobile/features/schedule/data/studio_trainers.dart';
import 'package:mobile/features/schedule/presentation/client/calendar_strip.dart';
import 'package:mobile/features/schedule/presentation/client/class_day_list.dart';
import 'package:mobile/features/schedule/presentation/client/classes_bars.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_filter.dart';
import 'package:mobile/features/schedule/presentation/widgets/client_schedule_filters.dart';
import 'package:mobile/features/schedule/presentation/widgets/series_changes_banner.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/features/trainings/data/personal_trainings_api.dart';
import 'package:mobile/features/trainings/presentation/personal_training_screen.dart';
import 'package:mobile/features/trainings/presentation/widgets/group_class_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Whose classes the Zajęcia tab shows.
enum ClassesMode { mine, all }

/// The Zajęcia tab: one calendar of the person's own classes and the
/// studio's whole offer (#281). It replaces Oferta and Mój grafik.
class ClientClassesScreen extends ConsumerWidget {
  const ClientClassesScreen({super.key, this.today, this.now});

  /// Fixed dates for tests; the device clock otherwise.
  final DateTime? today;
  final DateTime? now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final state = ref.watch(authControllerProvider);
    final session = clientSession(state);
    final scope = bookingScope(state);
    return Scaffold(
      appBar: ClientTabHeader(
        title: l10n.navClasses,
        actions: [
          HeaderIconButton(
            icon: Icons.history,
            tooltip: l10n.trainingHistoryTitle,
            onPressed: () => context.push(clientTrainingHistoryPath),
          ),
        ],
      ),
      body: scope == null || session == null
          ? ClassMessage(l10n.classesNoProfile)
          : _Classes(
              key: ValueKey(session.accountId),
              scope: scope,
              session: session,
              today: today,
              now: now,
            ),
    );
  }
}

class _Classes extends ConsumerStatefulWidget {
  const _Classes({
    super.key,
    required this.scope,
    required this.session,
    this.today,
    this.now,
  });

  final BookingScope scope;
  final Session session;
  final DateTime? today;
  final DateTime? now;

  @override
  ConsumerState<_Classes> createState() => _ClassesState();
}

/// How many weeks the day list shows at first.
const _initialWeeks = 2;

class _ClassesState extends ConsumerState<_Classes> {
  late DateTime _today = _dateOnly(widget.today ?? DateTime.now());
  late DateTime _selected = _today;
  late DateTime _rangeStart = weekStart(_today);
  int _weeks = _initialWeeks;
  bool _expanded = false;
  ClassesMode _mode = ClassesMode.mine;
  bool _allProfiles = false;
  ClientScheduleFilter _filter = const ClientScheduleFilter();

  final _listController = ScrollController();
  final _dayKeys = <DateTime, GlobalKey>{};

  /// Set while the list scrolls to the selected day, so that the scroll
  /// does not pick another day.
  bool _scrollingToDay = false;

  static DateTime _dateOnly(DateTime date) =>
      DateTime(date.year, date.month, date.day);

  DateTime get _now => widget.now ?? DateTime.now();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToSelected());
  }

  @override
  void dispose() {
    _listController.dispose();
    super.dispose();
  }

  bool get _showRelated =>
      ref
          .watch(
            profileViewPreferencesProvider((
              accountId: widget.scope.accountId,
              profileId: widget.scope.profileId,
            )),
          )
          .value ??
      false;

  bool get _hasFamily => _showRelated && widget.session.profiles.length > 1;

  bool get _showFamily =>
      _allProfiles && _hasFamily && _mode == ClassesMode.mine;

  ScheduleWindow _week(DateTime start) => (from: start, to: addDays(start, 7));

  List<ScheduleWindow> get _listWeeks => [
    for (var i = 0; i < _weeks; i++) _week(addDays(_rangeStart, 7 * i)),
  ];

  /// The weeks of the month grid, for its markers.
  List<ScheduleWindow> get _monthWeeks {
    final first = DateTime(_selected.year, _selected.month);
    final last = DateTime(
      _selected.year,
      _selected.month,
      DateUtils.getDaysInMonth(_selected.year, _selected.month),
    );
    return [
      for (
        var start = weekStart(first);
        !start.isAfter(last);
        start = addDays(start, 7)
      )
        _week(start),
    ];
  }

  ProviderListenable<AsyncValue<List<ScheduleEvent>>> _mineFor(
    ScheduleWindow window,
  ) => _showFamily
      ? familyScheduleProvider((
          accountId: widget.session.accountId,
          window: window,
        ))
      : clientScheduleProvider((
          scope: widget.scope,
          window: window,
          reservationsOnly: true,
        ));

  ProviderListenable<AsyncValue<List<ScheduleEvent>>> _allFor(
    ScheduleWindow window,
  ) => clientScheduleProvider((
    scope: widget.scope,
    window: window,
    reservationsOnly: false,
  ));

  /// The events of [windows] once they all load; the error or `null` while
  /// one is still loading.
  ({List<ScheduleEvent>? events, Object? error}) _load(
    List<ScheduleWindow> windows,
    ProviderListenable<AsyncValue<List<ScheduleEvent>>> Function(ScheduleWindow)
    provider,
  ) {
    final events = <ScheduleEvent>[];
    Object? error;
    var loading = false;
    for (final window in windows) {
      final value = ref.watch(provider(window));
      if (value.hasError && !value.hasValue) {
        error ??= value.error;
      } else if (value.value case final list?) {
        events.addAll(list);
      } else {
        loading = true;
      }
    }
    if (error != null) return (events: null, error: error);
    return (events: loading ? null : events, error: null);
  }

  Future<void> _refresh() async {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    final scopes = [
      if (_showFamily)
        for (final p in widget.session.profiles)
          (accountId: widget.session.accountId, profileId: p.id)
      else
        widget.scope,
    ];
    for (final window in {..._listWeeks, if (_expanded) ..._monthWeeks}) {
      for (final scope in scopes) {
        final query = (scope: scope, window: window);
        ref.invalidate(personalTrainingsProvider(query));
        ref.invalidate(classReservationsProvider(query));
        ref.invalidate(groupClassesProvider(query));
      }
      ref.invalidate(clientScheduleProvider);
      ref.invalidate(familyScheduleProvider);
    }
    ref.invalidate(scheduleChangesProvider(ParticipantChanges(widget.scope)));
  }

  void _select(DateTime day) {
    day = _dateOnly(day);
    setState(() {
      _selected = day;
      final end = addDays(_rangeStart, 7 * _weeks);
      if (day.isBefore(_rangeStart) || !day.isBefore(end)) {
        _rangeStart = weekStart(day);
        _weeks = _initialWeeks;
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _scrollToSelected());
  }

  /// Scrolls the list to the selected day, or to the next day with classes.
  Future<void> _scrollToSelected() async {
    if (!mounted) return;
    final days = _dayKeys.keys.toList()..sort();
    final target = days.where((day) => !day.isBefore(_selected)).firstOrNull;
    final context = target == null ? null : _dayKeys[target]?.currentContext;
    if (context == null || !_listController.hasClients) return;
    _scrollingToDay = true;
    await Scrollable.ensureVisible(
      context,
      duration: this.context.ct.motion.medium,
      curve: Curves.easeOutCubic,
    );
    _scrollingToDay = false;
  }

  /// After the person scrolls the list, the strip follows the day at the top.
  bool _onScrollEnd(ScrollEndNotification notification) {
    if (_scrollingToDay || notification.depth != 0) return false;
    final list = _listKey.currentContext?.findRenderObject();
    if (list is! RenderBox || !list.attached) return false;
    DateTime? top;
    final days = _dayKeys.keys.toList()..sort();
    for (final day in days) {
      final box = _dayKeys[day]?.currentContext?.findRenderObject();
      if (box is! RenderBox || !box.attached) continue;
      final y = box.localToGlobal(Offset.zero, ancestor: list).dy;
      if (y <= 1) {
        top = day;
      } else {
        top ??= day;
        break;
      }
    }
    if (top != null && top != _selected) setState(() => _selected = top!);
    return false;
  }

  final _listKey = GlobalKey();

  void _chooseProfile(Profile profile) {
    ref
        .read(authControllerProvider.notifier)
        .choose(SessionChoice(role: AppRole.client, profile: profile));
  }

  void _openClass(String eventId, {Profile? owner}) {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    if (owner != null && owner.id != widget.scope.profileId) {
      _chooseProfile(owner);
    }
    context.push(clientClassDetailsLocation(eventId));
  }

  void _openPersonal(PersonalSession session, {Profile? owner}) {
    if (!isCurrentBookingScope(ref, widget.scope)) return;
    final scope = owner == null
        ? widget.scope
        : (accountId: widget.session.accountId, profileId: owner.id);
    if (owner != null && owner.id != widget.scope.profileId) {
      _chooseProfile(owner);
    }
    Navigator.of(context, rootNavigator: true).push<void>(
      MaterialPageRoute(
        builder: (_) => PersonalTrainingScreen(
          query: (scope: scope, reservationId: session.reservationId),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final sp = ct.spacing;
    final l10n = AppLocalizations.of(context);
    final serviceTypes =
        ref.watch(scheduleServiceTypesProvider).value ?? const {};
    final trainers = ref.watch(studioTrainersProvider).value ?? const [];

    final mine = _load(_listWeeks, _mineFor);
    final all = _mode == ClassesMode.all ? _load(_listWeeks, _allFor) : null;
    final markWeeks = _expanded ? _monthWeeks : _listWeeks;
    final marked = _load(markWeeks, _mineFor).events ?? mine.events ?? [];
    final shown = _mode == ClassesMode.mine ? mine : all!;

    final filterBar = ClientScheduleFilterBar(
      filter: _filter,
      events: [
        for (final event in (all?.events ?? mine.events ?? const []))
          if (_unwrap(event) case final ClientScheduleEvent e) e.groupClass,
      ],
      serviceTypes: serviceTypes,
      trainers: trainers,
      onChanged: (filter) {
        if (mounted) setState(() => _filter = filter);
      },
    );

    final next = _nextClass(mine.events ?? const []);

    return Column(
      children: [
        CalendarStrip(
          selected: _selected,
          today: _today,
          expanded: _expanded,
          marks: _marks(marked),
          onSelect: _select,
          onExpandedChanged: (value) => setState(() => _expanded = value),
        ),
        Padding(
          padding: EdgeInsets.fromLTRB(sp.page, 0, sp.s, sp.m),
          child: Row(
            children: [
              Expanded(
                child: AppSegmented<ClassesMode>(
                  segments: [
                    AppSegment(ClassesMode.mine, l10n.classesMine),
                    AppSegment(ClassesMode.all, l10n.classesAll),
                  ],
                  selected: _mode,
                  onChanged: (mode) {
                    setState(() => _mode = mode);
                    WidgetsBinding.instance.addPostFrameCallback(
                      (_) => _scrollToSelected(),
                    );
                  },
                ),
              ),
              HeaderIconButton(
                icon: Icons.filter_list,
                tooltip: _filter.activeCount == 0
                    ? l10n.scheduleFilters
                    : l10n.scheduleFiltersActive(_filter.activeCount),
                badge: _filter.activeCount,
                onPressed: () => filterBar.open(context),
              ),
            ],
          ),
        ),
        if (_hasFamily)
          Padding(
            padding: EdgeInsets.fromLTRB(sp.page, 0, sp.page, sp.m),
            child: PersonFilter<String?>(
              choices: [
                for (final profile in widget.session.profiles)
                  PersonChoice(
                    value: profile.id,
                    label: profile.isChild
                        ? profile.displayName
                        : l10n.classesSelf,
                  ),
                if (_mode == ClassesMode.mine)
                  PersonChoice(
                    value: null,
                    label: l10n.scheduleAllProfiles,
                    everyone: true,
                  ),
              ],
              selected: _showFamily ? null : widget.scope.profileId,
              onChanged: (id) {
                if (id == null) {
                  setState(() => _allProfiles = true);
                  return;
                }
                setState(() => _allProfiles = false);
                final profile = widget.session.profiles.firstWhere(
                  (p) => p.id == id,
                );
                _chooseProfile(profile);
              },
            ),
          ),
        _ChangesStrip(
          owner: ParticipantChanges(widget.scope),
          onOpen: (eventId) => _openClass(eventId),
        ),
        if (next != null) next,
        Expanded(
          child: KeyedSubtree(
            key: _listKey,
            child: _list(context, shown, serviceTypes),
          ),
        ),
      ],
    );
  }

  Widget _list(
    BuildContext context,
    ({List<ScheduleEvent>? events, Object? error}) shown,
    Map<String, ScheduleServiceType> serviceTypes,
  ) {
    final l10n = AppLocalizations.of(context);
    if (shown.error != null) {
      return ClassesLoadError(onRetry: _refresh);
    }
    final events = shown.events;
    if (events == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final sections = _sections(context, events, serviceTypes);
    _dayKeys.removeWhere(
      (day, _) => !sections.any((section) => section.day == day),
    );
    final empty = sections.isEmpty;
    return NotificationListener<ScrollEndNotification>(
      onNotification: _onScrollEnd,
      child: RefreshIndicator(
        onRefresh: _refresh,
        child: CustomScrollView(
          controller: _listController,
          physics: const AlwaysScrollableScrollPhysics(),
          slivers: [
            if (empty)
              SliverToBoxAdapter(
                child: ClassMessage(
                  _mode == ClassesMode.mine
                      ? l10n.classesMineEmpty
                      : _filter.activeCount > 0
                      ? l10n.classesFilteredEmpty
                      : l10n.classesAllEmpty,
                ),
              ),
            for (final section in sections)
              SliverMainAxisGroup(
                slivers: [
                  SliverToBoxAdapter(
                    child: KeyedSubtree(
                      key: _dayKeys.putIfAbsent(section.day, GlobalKey.new),
                      child: DayHeader(
                        day: section.day,
                        today: _today,
                        count: section.count,
                      ),
                    ),
                  ),
                  SliverList.list(
                    children: [
                      for (final (i, entry) in section.entries.indexed)
                        ClassEntryRow(
                          key: ValueKey(entry.key),
                          entry: entry,
                          showStart:
                              i == 0 ||
                              section.entries[i - 1].startsAt != entry.startsAt,
                        ),
                    ],
                  ),
                ],
              ),
            SliverToBoxAdapter(
              child: Padding(
                padding: EdgeInsets.symmetric(vertical: context.ct.spacing.l),
                child: Center(
                  child: AppButton.ghost(
                    label: l10n.classesShowNextWeek,
                    onPressed: () => setState(() => _weeks++),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static ScheduleEvent _unwrap(ScheduleEvent event) =>
      event is ProfileScheduleEvent ? event.event : event;

  static Profile? _owner(ScheduleEvent event) =>
      event is ProfileScheduleEvent ? event.profile : null;

  Map<DateTime, DayMarks> _marks(List<ScheduleEvent> events) {
    final marks = <DateTime, DayMarks>{};
    for (final source in events) {
      final event = _unwrap(source);
      if (event.status == EventStatus.cancelled) continue;
      final day = _dateOnly(event.startsAt);
      final before = marks[day];
      marks[day] = DayMarks(
        own: true,
        change: (before?.change ?? false) || event.seriesChange != null,
      );
    }
    return marks;
  }

  /// The person's next class, as the bar above the list.
  Widget? _nextClass(List<ScheduleEvent> events) {
    final now = _now;
    final next = events
        .where(
          (e) =>
              _unwrap(e).status != EventStatus.cancelled &&
              e.startsAt.isAfter(now),
        )
        .firstOrNull;
    if (next == null) return null;
    final l10n = AppLocalizations.of(context);
    final event = _unwrap(next);
    final time = DateFormat('HH:mm').format(event.startsAt);
    final day = _dateOnly(event.startsAt);
    final minutes = event.startsAt.difference(now).inMinutes;
    final when = [
      if (day == _dateOnly(now))
        l10n.classesNextToday(time)
      else if (day == addDays(_dateOnly(now), 1))
        l10n.classesNextTomorrow(time)
      else
        DateFormat('EEE d.MM HH:mm', 'pl').format(event.startsAt),
      if (minutes < 60) l10n.classesStartsIn(minutes < 1 ? 1 : minutes),
    ].join(' · ');
    return NextClassBar(
      when: when,
      title: _title(l10n, event),
      onTap: () => event is PersonalSession
          ? _openPersonal(event, owner: _owner(next))
          : _openClass(event.id, owner: _owner(next)),
    );
  }

  String _title(AppLocalizations l10n, ScheduleEvent event) =>
      event is PersonalSession
      ? (event.title ?? event.serviceTypeName ?? l10n.personalTitle)
      : (event is ClientScheduleEvent
                ? event.groupClass.name
                : event.title ?? event.serviceTypeName) ??
            l10n.classesTitle;

  List<DaySection> _sections(
    BuildContext context,
    List<ScheduleEvent> events,
    Map<String, ScheduleServiceType> serviceTypes,
  ) {
    final l10n = AppLocalizations.of(context);
    final c = context.ct.colors;
    final byDay = <DateTime, List<ClassEntry>>{};
    for (final source in events) {
      final event = _unwrap(source);
      final owner = _owner(source);
      ClassEntry? entry;
      if (event is ClientScheduleEvent) {
        if (!_filter.matches(event.groupClass, serviceTypes)) continue;
        entry = _classEntry(l10n, c, event.groupClass, source.id, owner);
      } else if (event is PersonalSession) {
        if (_mode == ClassesMode.all) continue;
        entry = _personalEntry(l10n, c, event, source.id, owner);
      }
      if (entry == null) continue;
      byDay.putIfAbsent(_dateOnly(event.startsAt), () => []).add(entry);
    }
    final days = byDay.keys.toList()..sort();
    return [
      for (final day in days)
        DaySection(
          day: day,
          entries: byDay[day]!
            ..sort((a, b) => a.startsAt.compareTo(b.startsAt)),
          count: _mode == ClassesMode.all ? byDay[day]!.length : null,
        ),
    ];
  }

  static String _leads(List<EventStaff> staff) => [
    for (final person in staff)
      if (person.isLead && person.displayName != null) person.displayName!,
  ].join(', ');

  String? _change(AppLocalizations l10n, GroupClass event) {
    final change = event.seriesChange;
    if (change == null || event.status == 'CANCELLED') return null;
    if (change.usualStartsAt case final usual?) {
      final sameDay = _dateOnly(usual) == _dateOnly(event.startsAt);
      return l10n.classesChangeTime(
        DateFormat(sameDay ? 'HH:mm' : 'EEE d.MM HH:mm', 'pl').format(usual),
      );
    }
    if (change.usualInstructors case final names? when names.isNotEmpty) {
      return l10n.classesChangeSubstitution(names.join(', '));
    }
    if (change.usualRooms case final rooms? when rooms.isNotEmpty) {
      return l10n.classesChangeRoom(rooms.join(', '));
    }
    return null;
  }

  ClassEntry _classEntry(
    AppLocalizations l10n,
    ClientPalette c,
    GroupClass event,
    String key,
    Profile? owner,
  ) {
    final cancelled = event.status == 'CANCELLED';
    final own = event.isReserved || event.isWaitlisted;
    final waiting = classWaitingEntry(event, now: _now);
    final minutes = event.startsAt.difference(_now).inMinutes;
    final soon =
        event.isReserved &&
        !cancelled &&
        minutes >= 0 &&
        minutes < 60 &&
        _dateOnly(event.startsAt) == _dateOnly(_now);
    final staff = _leads(event.staff);
    return ClassEntry(
      key: key,
      startsAt: event.startsAt,
      endsAt: event.endsAt,
      title: event.name ?? l10n.classesTitle,
      color: _color(event.calendarColor, c),
      lines: cancelled
          ? [l10n.classesCancelledLine]
          : [
              if (staff.isNotEmpty) staff,
              if (event.roomNames.isNotEmpty) event.roomNames.join(', '),
            ],
      change: _change(l10n, event),
      label: _mode == ClassesMode.all && event.isReserved
          ? l10n.classesReserved
          : null,
      aside: cancelled
          ? null
          : waiting?.position != null
          ? TextAside(l10n.classesWaitlistPositionShort(waiting!.position!))
          : soon
          ? TextAside(l10n.classesStartsIn(minutes < 1 ? 1 : minutes))
          : _mode == ClassesMode.all &&
                !event.isReserved &&
                event.availablePlaces != null
          ? PlacesAside(event.availablePlaces!)
          : null,
      participant: owner?.displayName,
      dimmed: _mode == ClassesMode.all && !own,
      cancelled: cancelled,
      onTap: () => _openClass(event.id, owner: owner),
    );
  }

  ClassEntry _personalEntry(
    AppLocalizations l10n,
    ClientPalette c,
    PersonalSession event,
    String key,
    Profile? owner,
  ) {
    final cancelled = event.status == EventStatus.cancelled;
    final staff = _leads(event.staff);
    return ClassEntry(
      key: key,
      startsAt: event.startsAt,
      endsAt: event.endsAt,
      title: _title(l10n, event),
      color: c.muted,
      lines: [
        if (staff.isNotEmpty) staff,
        if (event.roomNames.isNotEmpty) event.roomNames.join(', '),
      ],
      participant: owner?.displayName,
      cancelled: cancelled,
      onTap: () => _openPersonal(event, owner: owner),
    );
  }

  static Color _color(String? hex, ClientPalette c) {
    if (hex != null && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(hex)) {
      return Color(0xff000000 | int.parse(hex.substring(1), radix: 16));
    }
    return c.classFallback;
  }
}

/// The open notices about changes in the person's classes, as an info strip
/// that opens their list.
class _ChangesStrip extends ConsumerWidget {
  const _ChangesStrip({required this.owner, required this.onOpen});

  final ChangesOwner owner;
  final ValueChanged<String> onOpen;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final notices =
        ref.watch(scheduleChangesProvider(owner)).value ??
        const <ScheduleChangeNotice>[];
    if (notices.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: EdgeInsets.only(bottom: context.ct.spacing.m),
      child: InfoStrip(
        topGap: false,
        text: l10n.changeBannerTitle(notices.length),
        actionLabel: l10n.changeBannerShow,
        onAction: () => showAppSheet<void>(
          context,
          builder: (sheetContext) => ScheduleChangesSheet(
            owner: owner,
            onOpen: (eventId) {
              Navigator.of(sheetContext).pop();
              onOpen(eventId);
            },
          ),
        ),
      ),
    );
  }
}
