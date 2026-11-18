import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/features/schedule/data/consultation_api.dart';
import 'package:mobile/features/schedule/data/schedule_api.dart';
import 'package:mobile/features/schedule/presentation/plan_messages.dart';
import 'package:mobile/features/schedule/presentation/time_input.dart';
import 'package:mobile/features/schedule/presentation/widgets/plan_preview_section.dart';
import 'package:mobile/features/trainings/data/trainee_overview_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Reserves the trainer and a whole room without creating a client account.
class AddConsultationScreen extends ConsumerStatefulWidget {
  const AddConsultationScreen({super.key, this.initialDay});

  final DateTime? initialDay;

  @override
  ConsumerState<AddConsultationScreen> createState() =>
      _AddConsultationScreenState();
}

class _AddConsultationScreenState extends ConsumerState<AddConsultationScreen> {
  final _guest = TextEditingController();
  final _time = TextEditingController(text: '18:00');
  final _duration = TextEditingController(text: '60');
  late final TrainerOverviewScope? _scope;
  late DateTime _day;
  String? _roomId;
  Timer? _timer;
  int _revision = 0;
  bool _loading = false;
  bool _saving = false;
  PlanPreview? _preview;
  Object? _error;
  Object? _saveError;

  bool get _active =>
      mounted &&
      _scope != null &&
      trainerOverviewScope(ref.read(authControllerProvider)) == _scope;

  @override
  void initState() {
    super.initState();
    final now = DateTime.now();
    _day = widget.initialDay ?? DateTime(now.year, now.month, now.day + 1);
    _scope = trainerOverviewScope(ref.read(authControllerProvider));
  }

  @override
  void dispose() {
    _timer?.cancel();
    _guest.dispose();
    _time.dispose();
    _duration.dispose();
    super.dispose();
  }

  int? get _minutes {
    final minutes = int.tryParse(_duration.text);
    return minutes != null && minutes >= 5 && minutes <= 720 ? minutes : null;
  }

  ConsultationPlan? get _plan {
    final time = parseTime(_time.text);
    final minutes = _minutes;
    final room = _roomId;
    if (time == null || minutes == null || room == null) return null;
    final start = DateTime(
      _day.year,
      _day.month,
      _day.day,
      time.hour,
      time.minute,
    );
    return ConsultationPlan(
      roomId: room,
      startsAt: start,
      endsAt: start.add(Duration(minutes: minutes)),
      guestName: _guest.text,
    );
  }

  void _changed(VoidCallback change) {
    _timer?.cancel();
    // Invalidate in-flight responses immediately, including during the debounce.
    final revision = ++_revision;
    setState(() {
      change();
      _preview = null;
      _error = null;
      _saveError = null;
      _loading = _plan != null;
    });
    final plan = _plan;
    if (plan != null) {
      _timer = Timer(
        const Duration(milliseconds: 400),
        () => _check(plan, revision),
      );
    }
  }

  Future<void> _check(ConsultationPlan plan, int revision) async {
    try {
      final preview = await ref.read(consultationApiProvider).preview(plan);
      if (!_active || revision != _revision) return;
      setState(() {
        _preview = preview;
        _loading = false;
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
      !_saving &&
      !_loading &&
      _active &&
      _plan != null &&
      _preview != null &&
      _preview!.conflictCount == 0 &&
      _preview!.occurrences.isNotEmpty &&
      !_preview!.occurrences.any((o) => o.excluded || o.hasBlockingIssue);

  Future<void> _save() async {
    final plan = _plan;
    if (!_canSave || plan == null) return;
    setState(() {
      _saving = true;
      _saveError = null;
    });
    try {
      final event = await ref.read(consultationApiProvider).create(plan);
      if (!mounted || !_active) return;
      ref.invalidate(ownScheduleProvider);
      context.pop(event);
    } catch (e) {
      if (!_active) return;
      setState(() {
        _saving = false;
        _saveError = e;
      });
      // A free preview does not reserve the slot; recheck after a concurrent booking.
      setState(() {
        _preview = null;
        _loading = true;
      });
      await _check(plan, ++_revision);
    }
  }

  Future<void> _pickDay() async {
    final now = DateTime.now();
    final day = await showDatePicker(
      context: context,
      initialDate: _day,
      firstDate: DateTime(now.year, now.month, now.day),
      lastDate: DateTime(now.year + 2, now.month, now.day),
    );
    if (_active && day != null) _changed(() => _day = day);
  }

  String _message(Object error, AppLocalizations l10n) {
    if (error is PlanRejectedException &&
        error.code == 'VALIDATION_FAILED' &&
        error.field == 'startsAt') {
      return l10n.consultationPast;
    }
    if (_saveError != null &&
        error is ApiException &&
        error.statusCode != null &&
        error.statusCode! >= 500) {
      return l10n.consultationSaveError;
    }
    return planErrorMessage(error, l10n, saving: _saveError != null);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scope = trainerOverviewScope(ref.watch(authControllerProvider));
    final rooms = ref.watch(planningRoomsProvider);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.consultationAdd)),
      body: scope == null || scope != _scope
          ? const SizedBox()
          : rooms.when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (_, _) => Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(l10n.addClassLoadError),
                    TextButton.icon(
                      onPressed: () => ref.invalidate(planningRoomsProvider),
                      icon: const Icon(Icons.refresh),
                      label: Text(l10n.scheduleRetryButton),
                    ),
                  ],
                ),
              ),
              data: (rooms) => SafeArea(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  keyboardDismissBehavior:
                      ScrollViewKeyboardDismissBehavior.onDrag,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    spacing: 16,
                    children: [
                      TextField(
                        key: const Key('consultationGuest'),
                        controller: _guest,
                        enabled: !_saving,
                        maxLength: 120,
                        textCapitalization: TextCapitalization.words,
                        decoration: InputDecoration(
                          labelText: l10n.consultationGuest,
                          counterText: '',
                          prefixIcon: const Icon(Icons.person_outline),
                          border: const OutlineInputBorder(),
                        ),
                      ),
                      if (rooms.isEmpty)
                        Text(l10n.addClassNoRooms)
                      else
                        DropdownButtonFormField<String>(
                          key: const Key('consultationRoom'),
                          initialValue: rooms.any((r) => r.id == _roomId)
                              ? _roomId
                              : null,
                          isExpanded: true,
                          decoration: InputDecoration(
                            labelText: l10n.addClassRoom,
                            prefixIcon: const Icon(Icons.meeting_room_outlined),
                            border: const OutlineInputBorder(),
                          ),
                          items: [
                            for (final room in rooms)
                              DropdownMenuItem(
                                value: room.id,
                                child: Text(
                                  room.name,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              ),
                          ],
                          onChanged: _saving
                              ? null
                              : (id) => _changed(() => _roomId = id),
                        ),
                      Card(
                        margin: EdgeInsets.zero,
                        child: ListTile(
                          key: const Key('consultationDay'),
                          leading: const Icon(Icons.calendar_today),
                          title: Text(
                            DateFormat('EEEE, d MMMM y', 'pl').format(_day),
                          ),
                          trailing: const Icon(Icons.edit_calendar_outlined),
                          onTap: _saving ? null : _pickDay,
                        ),
                      ),
                      TextField(
                        key: const Key('consultationTime'),
                        controller: _time,
                        enabled: !_saving,
                        keyboardType: TextInputType.datetime,
                        inputFormatters: const [TimeInputFormatter()],
                        decoration: InputDecoration(
                          labelText: l10n.addClassStartTime,
                          errorText: parseTime(_time.text) == null
                              ? l10n.addClassStartTimeInvalid
                              : null,
                          prefixIcon: const Icon(Icons.schedule),
                          border: const OutlineInputBorder(),
                        ),
                        onChanged: (_) => _changed(() {}),
                      ),
                      TextField(
                        key: const Key('consultationDuration'),
                        controller: _duration,
                        enabled: !_saving,
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly,
                        ],
                        decoration: InputDecoration(
                          labelText: l10n.addClassDuration,
                          errorText: _minutes == null
                              ? l10n.addClassDurationInvalid
                              : null,
                          prefixIcon: const Icon(Icons.timelapse),
                          border: const OutlineInputBorder(),
                        ),
                        onChanged: (_) => _changed(() {}),
                      ),
                      if (_plan == null)
                        Text(l10n.consultationIncomplete)
                      else if (_error != null)
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _message(_error!, l10n),
                              style: TextStyle(
                                color: Theme.of(context).colorScheme.error,
                              ),
                            ),
                            TextButton(
                              onPressed: () => _changed(() {}),
                              child: Text(l10n.scheduleRetryButton),
                            ),
                          ],
                        )
                      else
                        PlanPreviewSection(
                          complete: true,
                          loading: _loading,
                          preview: _preview,
                          error: null,
                          recurring: false,
                          onToggleExcluded: null,
                          onRetry: () => _changed(() {}),
                        ),
                      if (_saveError != null)
                        Text(
                          _message(_saveError!, l10n),
                          style: TextStyle(
                            color: Theme.of(context).colorScheme.error,
                          ),
                        ),
                      FilledButton.icon(
                        key: const Key('consultationSave'),
                        onPressed: _canSave && rooms.any((r) => r.id == _roomId)
                            ? _save
                            : null,
                        icon: _saving
                            ? const SizedBox.square(
                                dimension: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : const Icon(Icons.event_available),
                        label: Text(l10n.consultationSave),
                      ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }
}
