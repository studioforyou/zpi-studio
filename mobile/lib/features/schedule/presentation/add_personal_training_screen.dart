import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/personal_training_planning_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/plan_messages.dart';
import 'package:mobile/features/schedule/presentation/time_input.dart';
import 'package:mobile/features/schedule/presentation/widgets/plan_preview_section.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/features/trainings/data/trainees_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

class AddPersonalTrainingScreen extends ConsumerStatefulWidget {
  const AddPersonalTrainingScreen({super.key});

  @override
  ConsumerState<AddPersonalTrainingScreen> createState() =>
      _AddPersonalTrainingScreenState();
}

class _AddPersonalTrainingScreenState
    extends ConsumerState<AddPersonalTrainingScreen> {
  final _time = TextEditingController(text: '18:00');
  final _duration = TextEditingController(text: '60');
  late final TrainerOverviewScope? _scope;
  DateTime _day = DateUtils.dateOnly(
    DateTime.now().add(const Duration(days: 1)),
  );
  CustomerSummary? _customer;
  String? _serviceId;
  String? _roomId;
  String? _passId;
  Timer? _timer;
  int _revision = 0;
  bool _loading = false;
  bool _saving = false;
  PersonalTrainingPreview? _preview;
  Object? _error;
  Object? _saveError;

  bool get _active =>
      mounted &&
      _scope != null &&
      trainerOverviewScope(ref.read(authControllerProvider)) == _scope;

  @override
  void initState() {
    super.initState();
    _scope = trainerOverviewScope(ref.read(authControllerProvider));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _time.dispose();
    _duration.dispose();
    super.dispose();
  }

  PersonalTrainingPlan? get _plan {
    final time = parseTime(_time.text);
    final minutes = int.tryParse(_duration.text);
    if (_customer == null ||
        _serviceId == null ||
        _roomId == null ||
        time == null ||
        minutes == null ||
        minutes < 5 ||
        minutes > 720) {
      return null;
    }
    final start = DateTime(
      _day.year,
      _day.month,
      _day.day,
      time.hour,
      time.minute,
    );
    return PersonalTrainingPlan(
      customerId: _customer!.id,
      serviceTypeId: _serviceId!,
      roomId: _roomId!,
      startsAt: start,
      endsAt: start.add(Duration(minutes: minutes)),
    );
  }

  void _changed(VoidCallback update) {
    _timer?.cancel();
    final revision = ++_revision;
    setState(() {
      update();
      _preview = null;
      _error = null;
      _saveError = null;
      _loading = _plan != null;
    });
    final plan = _plan;
    if (plan != null) {
      _timer = Timer(
        const Duration(milliseconds: 350),
        () => _check(plan, revision),
      );
    }
  }

  Future<void> _check(PersonalTrainingPlan plan, int revision) async {
    try {
      final preview = await ref
          .read(personalTrainingPlanningApiProvider)
          .preview(plan);
      if (!_active || revision != _revision) return;
      setState(() {
        _preview = preview;
        _loading = false;
        if (!preview.passes.any((p) => p.id == _passId)) {
          _passId = preview.passes.length == 1
              ? preview.passes.single.id
              : null;
        }
      });
    } catch (e) {
      if (!_active || revision != _revision) return;
      setState(() {
        _error = e;
        _loading = false;
      });
    }
  }

  bool get _canSave =>
      _active &&
      !_saving &&
      !_loading &&
      (_preview?.allowed ?? false) &&
      _plan != null &&
      (_preview?.passes.any((p) => p.id == _passId) ?? false);

  Future<void> _save() async {
    if (!_canSave) return;
    final plan = _plan!;
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      final id = await ref
          .read(personalTrainingPlanningApiProvider)
          .create(plan, _passId!);
      if (!mounted || !_active) return;
      ref.invalidate(ownScheduleProvider);
      ref.invalidate(ownPersonalTrainingsProvider);
      ref.invalidate(ownTraineesProvider);
      ref.invalidate(traineeOverviewsProvider);
      ref.invalidate(traineeOverviewProvider);
      ref.invalidate(traineeCalendarProvider);
      context.pop(id);
    } catch (e) {
      if (!_active) return;
      setState(() {
        _saving = false;
        _saveError = e;
        _loading = true;
        _preview = null;
      });
      await _check(plan, ++_revision);
    }
  }

  Future<void> _pickCustomer() async {
    final customer = await showModalBottomSheet<CustomerSummary>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (_) => _CustomerPicker(scope: _scope!),
    );
    if (_active && customer != null) {
      _changed(() {
        _customer = customer;
        _passId = null;
      });
    }
  }

  Future<void> _pickDay() async {
    final today = DateUtils.dateOnly(DateTime.now());
    final day = await showDatePicker(
      context: context,
      initialDate: _day.isBefore(today) ? today : _day,
      firstDate: today,
      lastDate: DateTime(today.year + 2, today.month, today.day),
    );
    if (_active && day != null) _changed(() => _day = day);
  }

  String _reason(String? code, AppLocalizations l10n) => switch (code) {
    'CUSTOMER_SCHEDULE_CONFLICT' => l10n.personalPlanCustomerBusy,
    'CUSTOMER_NOT_ACTIVE' => l10n.personalPlanCustomerInactive,
    'REQUIRED_DOCUMENTS_MISSING' => l10n.personalPlanDocuments,
    'AGE_RESTRICTION' => l10n.personalPlanAge,
    'NO_ELIGIBLE_PASS' ||
    'PASS_NOT_USABLE' ||
    'PASS_ENTRIES_EXHAUSTED' ||
    'PASS_SERVICE_MISMATCH' => l10n.personalPlanNoPass,
    _ => l10n.addClassErrorInvalid,
  };

  String _errorMessage(Object error, AppLocalizations l10n) {
    if (error is PlanRejectedException &&
        const {
          'CUSTOMER_SCHEDULE_CONFLICT',
          'CUSTOMER_NOT_ACTIVE',
          'REQUIRED_DOCUMENTS_MISSING',
          'AGE_RESTRICTION',
          'NO_ELIGIBLE_PASS',
          'PASS_NOT_USABLE',
          'PASS_ENTRIES_EXHAUSTED',
          'PASS_SERVICE_MISMATCH',
        }.contains(error.code)) {
      return _reason(error.code, l10n);
    }
    return planErrorMessage(error, l10n);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    if (!_active || scope != _scope) return const SizedBox();
    final services = ref.watch(personalPlanningServicesProvider);
    final rooms = ref.watch(planningRoomsProvider);
    final preview = _preview;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.personalPlanTitle)),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(16, 8, 16, 12),
        child: FilledButton.icon(
          key: const Key('personalPlanSave'),
          onPressed: _canSave ? _save : null,
          icon: _saving
              ? const SizedBox.square(
                  dimension: 18,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.event_available_outlined),
          label: Text(l10n.personalPlanSave),
        ),
      ),
      body: AbsorbPointer(
        absorbing: _saving,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                key: const Key('personalPlanCustomer'),
                leading: CircleAvatar(
                  child: _customer == null
                      ? const Icon(Icons.person_add_alt_1)
                      : Text(_customer!.displayName.characters.first),
                ),
                title: Text(
                  _customer?.displayName ?? l10n.personalPlanChooseCustomer,
                ),
                subtitle: _customer == null
                    ? null
                    : Text(
                        _customer!.isChild
                            ? l10n.personalPlanChild
                            : l10n.personalPlanAdult,
                      ),
                trailing: const Icon(Icons.search),
                onTap: _pickCustomer,
              ),
            ),
            const SizedBox(height: 16),
            services.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => _retry(
                _errorMessage(e, l10n),
                () => ref.invalidate(personalPlanningServicesProvider),
              ),
              data: (items) => items.isEmpty
                  ? Text(l10n.personalPlanNoServices)
                  : DropdownButtonFormField<String>(
                      key: const Key('personalPlanService'),
                      initialValue: _serviceId,
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: l10n.personalPlanService,
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.fitness_center),
                      ),
                      items: [
                        for (final item in items)
                          DropdownMenuItem(
                            value: item.id,
                            child: Text(
                              item.name,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (id) => _changed(() {
                        _serviceId = id;
                        _duration.text = items
                            .firstWhere((s) => s.id == id)
                            .defaultDurationMinutes
                            .toString();
                      }),
                    ),
            ),
            const SizedBox(height: 12),
            Card(
              margin: EdgeInsets.zero,
              child: ListTile(
                key: const Key('personalPlanDay'),
                leading: const Icon(Icons.calendar_today_outlined),
                title: Text(DateFormat('EEEE, d MMMM y', 'pl').format(_day)),
                trailing: const Icon(Icons.edit_calendar_outlined),
                onTap: _pickDay,
              ),
            ),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: TextField(
                    key: const Key('personalPlanTime'),
                    controller: _time,
                    keyboardType: TextInputType.datetime,
                    inputFormatters: const [TimeInputFormatter()],
                    decoration: InputDecoration(
                      labelText: l10n.addClassStartTime,
                      border: const OutlineInputBorder(),
                      prefixIcon: const Icon(Icons.schedule),
                      errorText: parseTime(_time.text) == null
                          ? l10n.addClassStartTimeInvalid
                          : null,
                    ),
                    onChanged: (_) => _changed(() {}),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    key: const Key('personalPlanDuration'),
                    controller: _duration,
                    keyboardType: TextInputType.number,
                    inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                    decoration: InputDecoration(
                      labelText: l10n.addClassDuration,
                      border: const OutlineInputBorder(),
                      errorText:
                          (int.tryParse(_duration.text) ?? 0) < 5 ||
                              (int.tryParse(_duration.text) ?? 0) > 720
                          ? l10n.addClassDurationInvalid
                          : null,
                    ),
                    onChanged: (_) => _changed(() {}),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            rooms.when(
              loading: () => const LinearProgressIndicator(),
              error: (e, _) => _retry(
                _errorMessage(e, l10n),
                () => ref.invalidate(planningRoomsProvider),
              ),
              data: (items) => items.isEmpty
                  ? Text(l10n.addClassNoRooms)
                  : DropdownButtonFormField<String>(
                      key: const Key('personalPlanRoom'),
                      initialValue: _roomId,
                      isExpanded: true,
                      decoration: InputDecoration(
                        labelText: l10n.addClassRoom,
                        border: const OutlineInputBorder(),
                        prefixIcon: const Icon(Icons.meeting_room_outlined),
                      ),
                      items: [
                        for (final item in items)
                          DropdownMenuItem(
                            value: item.id,
                            child: Text(
                              item.name,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (id) => _changed(() => _roomId = id),
                    ),
            ),
            const SizedBox(height: 16),
            if (_loading) const LinearProgressIndicator(),
            if (_error != null)
              _retry(_errorMessage(_error!, l10n), () => _changed(() {})),
            if (preview != null) ...[
              if (preview.allowed)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Row(
                    spacing: 8,
                    children: [
                      Icon(
                        Icons.check_circle_outline,
                        color: Theme.of(context).colorScheme.primary,
                        size: 20,
                      ),
                      Text(l10n.personalPlanAvailable),
                    ],
                  ),
                ),
              for (final issue in preview.issues)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: PlanIssueRow(issue: issue),
                ),
              if (preview.participantReason != null)
                _notice(_reason(preview.participantReason, l10n), error: true),
              if (preview.startsCooperation)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Row(
                    spacing: 8,
                    children: [
                      const Icon(Icons.person_add_alt_1, size: 18),
                      Expanded(child: Text(l10n.personalPlanNewCooperation)),
                    ],
                  ),
                ),
              if (preview.passes.isNotEmpty) ...[
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        l10n.personalPlanPayment,
                        style: Theme.of(context).textTheme.titleSmall,
                      ),
                    ),
                    IconButton(
                      tooltip: l10n.personalPlanPaymentInfoTitle,
                      icon: const Icon(Icons.info_outline, size: 20),
                      onPressed: () => showModalBottomSheet<void>(
                        context: context,
                        showDragHandle: true,
                        builder: (context) => SafeArea(
                          child: Padding(
                            padding: const EdgeInsets.fromLTRB(24, 0, 24, 24),
                            child: Column(
                              mainAxisSize: MainAxisSize.min,
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(
                                  l10n.personalPlanPaymentInfoTitle,
                                  style: Theme.of(context).textTheme.titleLarge,
                                ),
                                const SizedBox(height: 12),
                                Text(l10n.personalPlanPaymentInfo),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
                for (final pass in preview.passes)
                  Card(
                    margin: const EdgeInsets.only(bottom: 8),
                    color: pass.id == _passId
                        ? Theme.of(context).colorScheme.secondaryContainer
                        : null,
                    child: ListTile(
                      key: ValueKey('personalPass-${pass.id}'),
                      onTap: () => setState(() => _passId = pass.id),
                      leading: Icon(
                        pass.id == _passId
                            ? Icons.radio_button_checked
                            : Icons.radio_button_off,
                      ),
                      title: Text(pass.name),
                      subtitle: Text(
                        l10n.personalPlanPassValidity(
                          DateFormat('d.MM.y').format(pass.validUntil),
                        ),
                      ),
                      trailing: Text(
                        pass.entriesRemaining == null
                            ? '∞'
                            : '${pass.entriesRemaining} → ${pass.entriesRemaining! - 1}',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                  ),
              ],
            ],
            if (_saveError != null) ...[
              _notice(_errorMessage(_saveError!, l10n), error: true),
              Text(
                l10n.personalPlanVerifySave,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _notice(String text, {required bool error}) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8),
    child: Text(
      text,
      style: TextStyle(
        color: error ? Theme.of(context).colorScheme.error : null,
      ),
    ),
  );

  Widget _retry(String text, VoidCallback onRetry) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      _notice(text, error: true),
      TextButton(
        onPressed: onRetry,
        child: Text(AppLocalizations.of(context).scheduleRetryButton),
      ),
    ],
  );
}

class _CustomerPicker extends ConsumerStatefulWidget {
  const _CustomerPicker({required this.scope});
  final TrainerOverviewScope scope;

  @override
  ConsumerState<_CustomerPicker> createState() => _CustomerPickerState();
}

class _CustomerPickerState extends ConsumerState<_CustomerPicker> {
  Timer? _timer;
  String _query = '';
  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    if (trainerOverviewScope(ref.watch(authControllerProvider)) !=
        widget.scope) {
      return const SizedBox();
    }
    final provider = customerSearchProvider(_query);
    return SizedBox(
      height: MediaQuery.sizeOf(context).height * .78,
      child: Padding(
        padding: EdgeInsets.fromLTRB(
          16,
          0,
          16,
          MediaQuery.viewInsetsOf(context).bottom,
        ),
        child: Column(
          children: [
            TextField(
              autofocus: true,
              decoration: InputDecoration(
                labelText: l10n.personalPlanChooseCustomer,
                prefixIcon: const Icon(Icons.search),
                border: const OutlineInputBorder(),
              ),
              onChanged: (value) {
                _timer?.cancel();
                _timer = Timer(const Duration(milliseconds: 300), () {
                  if (mounted) setState(() => _query = value.trim());
                });
              },
            ),
            const SizedBox(height: 8),
            Expanded(
              child: ref
                  .watch(provider)
                  .when(
                    loading: () =>
                        const Center(child: CircularProgressIndicator()),
                    error: (e, _) => Center(
                      child: TextButton(
                        onPressed: () => ref.invalidate(provider),
                        child: Text(l10n.scheduleRetryButton),
                      ),
                    ),
                    data: (items) => items.isEmpty
                        ? Center(child: Text(l10n.personalPlanNoCustomers))
                        : ListView.builder(
                            itemCount: items.length,
                            itemBuilder: (context, index) {
                              final customer = items[index];
                              return ListTile(
                                leading: CircleAvatar(
                                  child: Text(
                                    customer.displayName.characters.first,
                                  ),
                                ),
                                title: Text(customer.displayName),
                                subtitle: Text(
                                  customer.isChild
                                      ? l10n.personalPlanChild
                                      : l10n.personalPlanAdult,
                                ),
                                trailing: const Icon(Icons.chevron_right),
                                onTap: () => Navigator.pop(context, customer),
                              );
                            },
                          ),
                  ),
            ),
          ],
        ),
      ),
    );
  }
}
