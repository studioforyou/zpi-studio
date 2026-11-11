import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/features/communication/presentation/chat_format.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Longest chat name the studio accepts.
const maxChatTitleLength = 120;

/// The trainer's form of a new chat of a fixed group or of one class. Pops
/// with the id of the conversation to open.
class NewGroupChatScreen extends ConsumerStatefulWidget {
  const NewGroupChatScreen({super.key});

  @override
  ConsumerState<NewGroupChatScreen> createState() => _NewGroupChatScreenState();
}

/// A group or class the chat can be created for.
class _Target {
  const _Target({
    required this.id,
    required this.label,
    required this.count,
    required this.defaultTitle,
    required this.conversationId,
  });

  final String id;
  final String label;
  final int count;
  final String defaultTitle;
  final String? conversationId;
}

class _NewGroupChatScreenState extends ConsumerState<NewGroupChatScreen> {
  bool _forClass = false;
  String? _groupId;
  String? _eventId;
  final _title = TextEditingController();
  bool _clientsCanReply = true;
  bool _saving = false;

  /// The name filled in for the selected target, replaced when the user picks
  /// another target without editing it.
  String? _filledTitle;

  @override
  void dispose() {
    _title.dispose();
    super.dispose();
  }

  List<_Target> _targets(GroupChatTargets targets) => _forClass
      ? [
          for (final event in targets.events)
            _Target(
              id: event.eventId,
              label: '${event.title} · ${formatClassStart(event.startsAt)}',
              count: event.participantCount,
              defaultTitle: _limit(
                '${event.title} · '
                '${DateFormat('d.MM', 'pl').format(event.startsAt)}',
              ),
              conversationId: event.conversationId,
            ),
        ]
      : [
          for (final group in targets.groups)
            _Target(
              id: group.trainingGroupId,
              label: group.name,
              count: group.memberCount,
              defaultTitle: _limit(group.name),
              conversationId: group.conversationId,
            ),
        ];

  static String _limit(String title) => title.length <= maxChatTitleLength
      ? title
      : title.substring(0, maxChatTitleLength);

  void _select(_Target target) {
    setState(() {
      if (_forClass) {
        _eventId = target.id;
      } else {
        _groupId = target.id;
      }
      if (_title.text.trim().isEmpty || _title.text == _filledTitle) {
        _title.text = target.defaultTitle;
        _filledTitle = target.defaultTitle;
      }
    });
  }

  Future<void> _create(_Target target) async {
    final existing = target.conversationId;
    if (existing != null) {
      context.pop(existing);
      return;
    }
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _saving = true);
    try {
      final conversation = await ref
          .read(conversationsApiProvider)
          .createGroupChat(
            trainingGroupId: _forClass ? null : target.id,
            eventId: _forClass ? target.id : null,
            title: _title.text.trim(),
            clientsCanReply: _clientsCanReply,
          );
      if (mounted) {
        context.pop(conversation.id);
      }
    } catch (e) {
      messenger.showSnackBar(
        SnackBar(content: Text(messagesErrorMessage(e, l10n))),
      );
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final accountId = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session.accountId,
      _ => null,
    };
    if (accountId == null) {
      return const SizedBox();
    }
    final provider = groupChatTargetsProvider(accountId);

    return Scaffold(
      appBar: AppBar(
        leading: const CloseButton(),
        title: Text(l10n.newChatTitle),
      ),
      body: ref
          .watch(provider)
          .when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (error, _) => Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  spacing: 16,
                  children: [
                    Text(
                      messagesErrorMessage(error, l10n, loading: true),
                      textAlign: TextAlign.center,
                    ),
                    OutlinedButton.icon(
                      onPressed: () => ref.invalidate(provider),
                      icon: const Icon(Icons.refresh),
                      label: Text(l10n.messagesRetry),
                    ),
                  ],
                ),
              ),
            ),
            data: (targets) => _form(context, l10n, _targets(targets)),
          ),
    );
  }

  Widget _form(
    BuildContext context,
    AppLocalizations l10n,
    List<_Target> targets,
  ) {
    final theme = Theme.of(context);
    final selectedId = _forClass ? _eventId : _groupId;
    final selected = targets.where((t) => t.id == selectedId).firstOrNull;
    final hasChat = selected?.conversationId != null;
    final canCreate =
        selected != null &&
        !_saving &&
        (hasChat || _title.text.trim().isNotEmpty);

    return SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
              children: [
                SegmentedButton<bool>(
                  segments: [
                    ButtonSegment(
                      value: false,
                      label: Text(l10n.newChatFixedGroup),
                    ),
                    ButtonSegment(
                      value: true,
                      label: Text(l10n.newChatSingleClass),
                    ),
                  ],
                  selected: {_forClass},
                  onSelectionChanged: (value) => setState(() {
                    _forClass = value.single;
                    if (_title.text == _filledTitle) {
                      _title.clear();
                      _filledTitle = null;
                    }
                  }),
                ),
                const SizedBox(height: 20),
                _Info(
                  count: selected == null
                      ? null
                      : _forClass
                      ? l10n.newChatClassParticipants(selected.count)
                      : l10n.newChatGroupMembers(selected.count),
                  text: _forClass
                      ? l10n.newChatClassInfo
                      : l10n.newChatGroupInfo,
                ),
                const SizedBox(height: 28),
                if (targets.isEmpty)
                  Text(
                    _forClass ? l10n.newChatNoClasses : l10n.newChatNoGroups,
                    textAlign: TextAlign.center,
                    style: theme.textTheme.bodyLarge,
                  )
                else ...[
                  _TargetField(
                    key: ValueKey(_forClass),
                    label: _forClass
                        ? l10n.newChatClassLabel
                        : l10n.newChatGroupLabel,
                    header: _forClass
                        ? l10n.newChatClassesHeader
                        : l10n.newChatGroupsHeader,
                    icon: _forClass ? Icons.event : Icons.groups,
                    targets: targets,
                    selected: selected,
                    countLabel: (target) => _forClass
                        ? l10n.newChatClassParticipants(target.count)
                        : l10n.newChatGroupMembers(target.count),
                    hasChatLabel: l10n.newChatHasChat,
                    onSelected: _select,
                  ),
                  const SizedBox(height: 28),
                  TextField(
                    controller: _title,
                    enabled: !hasChat,
                    maxLength: maxChatTitleLength,
                    textCapitalization: TextCapitalization.sentences,
                    decoration: InputDecoration(
                      labelText: l10n.newChatName,
                      border: const OutlineInputBorder(),
                      counterText: '',
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 24),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Text(l10n.newChatClientsReply),
                    subtitle: Text(l10n.newChatClientsReplyHint),
                    value: _clientsCanReply,
                    onChanged: hasChat
                        ? null
                        : (value) => setState(() => _clientsCanReply = value),
                  ),
                ],
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
            child: SizedBox(
              width: double.infinity,
              height: 52,
              child: FilledButton(
                onPressed: canCreate ? () => _create(selected) : null,
                child: _saving
                    ? const SizedBox.square(
                        dimension: 22,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : Text(
                        hasChat ? l10n.newChatOpenExisting : l10n.newChatCreate,
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// What the chat includes: the count of the selected target and how new
/// people join.
class _Info extends StatelessWidget {
  const _Info({required this.count, required this.text});

  final String? count;
  final String text;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final style = theme.textTheme.bodyMedium?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      spacing: 12,
      children: [
        Icon(Icons.info, size: 20, color: theme.colorScheme.primary),
        Expanded(
          child: Text.rich(
            TextSpan(
              children: [
                if (count != null) ...[
                  TextSpan(
                    text: count,
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  const TextSpan(text: ' · '),
                ],
                TextSpan(text: text),
              ],
            ),
            style: style,
          ),
        ),
      ],
    );
  }
}

/// The dropdown of the trainer's own groups or classes.
class _TargetField extends StatelessWidget {
  const _TargetField({
    super.key,
    required this.label,
    required this.header,
    required this.icon,
    required this.targets,
    required this.selected,
    required this.countLabel,
    required this.hasChatLabel,
    required this.onSelected,
  });

  final String label;
  final String header;
  final IconData icon;
  final List<_Target> targets;
  final _Target? selected;
  final String Function(_Target) countLabel;
  final String hasChatLabel;
  final ValueChanged<_Target> onSelected;

  /// The value of the header row, which cannot be picked.
  static const _headerValue = '';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return DropdownButtonFormField<String>(
      initialValue: selected?.id,
      isExpanded: true,
      itemHeight: null,
      decoration: InputDecoration(
        labelText: label,
        prefixIcon: Icon(icon),
        border: const OutlineInputBorder(),
      ),
      selectedItemBuilder: (_) => [
        const SizedBox(),
        for (final target in targets)
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: Text(target.label, maxLines: 2),
          ),
      ],
      items: [
        DropdownMenuItem(
          value: _headerValue,
          enabled: false,
          child: Text(
            header,
            style: theme.textTheme.labelLarge?.copyWith(
              color: theme.colorScheme.primary,
            ),
          ),
        ),
        for (final target in targets)
          DropdownMenuItem(
            value: target.id,
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(target.label),
                  Text(
                    [
                      countLabel(target),
                      if (target.conversationId != null) hasChatLabel,
                    ].join(' · '),
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
          ),
      ],
      onChanged: (id) {
        final target = targets.where((t) => t.id == id).firstOrNull;
        if (target != null) {
          onSelected(target);
        }
      },
    );
  }
}
