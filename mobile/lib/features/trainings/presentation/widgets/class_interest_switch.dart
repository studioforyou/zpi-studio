import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile/features/trainings/data/class_interests_api.dart';
import 'package:mobile/features/trainings/data/group_classes_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Lets the participant ask to be told when booking opens for classes of
/// this kind (the class's service type). Shows nothing while the choice is
/// loading or could not be loaded, as it is not needed to book the class.
class ClassInterestSwitch extends ConsumerStatefulWidget {
  const ClassInterestSwitch({
    required this.scope,
    required this.serviceTypeId,
    required this.serviceTypeName,
    super.key,
  });

  final BookingScope scope;
  final String serviceTypeId;
  final String serviceTypeName;

  @override
  ConsumerState<ClassInterestSwitch> createState() =>
      _ClassInterestSwitchState();
}

class _ClassInterestSwitchState extends ConsumerState<ClassInterestSwitch> {
  bool _saving = false;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final marked = ref.watch(classInterestsProvider(widget.scope)).value;
    if (marked == null) return const SizedBox.shrink();
    return SwitchListTile(
      contentPadding: EdgeInsets.zero,
      secondary: const Icon(Icons.notifications_active_outlined),
      title: Text(l10n.classInterestTitle(widget.serviceTypeName)),
      subtitle: Text(l10n.classInterestInfo),
      value: marked.contains(widget.serviceTypeId),
      onChanged: _saving ? null : (value) => _change(value),
    );
  }

  Future<void> _change(bool interested) async {
    setState(() => _saving = true);
    final messenger = ScaffoldMessenger.of(context);
    final l10n = AppLocalizations.of(context);
    try {
      await ref
          .read(classInterestsProvider(widget.scope).notifier)
          .change(widget.serviceTypeId, interested: interested);
    } on Exception {
      messenger.showSnackBar(SnackBar(content: Text(l10n.classInterestFailed)));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
