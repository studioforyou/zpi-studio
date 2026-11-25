import 'package:flutter/material.dart';
import 'package:mobile/features/communication/data/conversations_api.dart';
import 'package:mobile/shared/widgets/client/client_widgets.dart';

/// Colours of people's avatars; a person always gets the same one.
const _personColors = [
  Color(0xFF7A5C99),
  Color(0xFF438F95),
  Color(0xFFB5654A),
  Color(0xFF4E5A92),
  Color(0xFF8A7A2E),
  Color(0xFF5A7D4E),
  Color(0xFF235B68),
  Color(0xFF9B4F72),
];

/// A person's square avatar with their initials.
class PersonAvatar extends StatelessWidget {
  const PersonAvatar({
    super.key,
    required this.name,
    required this.seed,
    this.size = 44,
  });

  final String name;

  /// Picks the colour, e.g. the account id.
  final String seed;
  final double size;

  @override
  Widget build(BuildContext context) {
    if (context.inClientTheme) {
      return SquareAvatar(
        initials: SquareAvatar.initialsOf(name),
        background: clientAvatarColor(context, seed),
        foreground: context.ct.colors.onChatAvatar,
        size: size,
        style: size < context.ct.sizes.avatar ? context.ct.text.badge : null,
      );
    }
    final color = _personColors[seed.hashCode.abs() % _personColors.length];
    return Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(size * 0.27),
      ),
      child: Text(
        initials(name),
        style: TextStyle(
          color: Colors.white,
          fontWeight: FontWeight.w500,
          fontSize: size * 0.36,
        ),
      ),
    );
  }
}

/// The square icon of a group or class chat.
class ChatKindAvatar extends StatelessWidget {
  const ChatKindAvatar({super.key, required this.kind, this.size = 44});

  final ConversationKind kind;
  final double size;

  @override
  Widget build(BuildContext context) {
    if (context.inClientTheme) return _ClientKindAvatar(kind: kind, size: size);
    final scheme = Theme.of(context).colorScheme;
    final isEvent = kind == ConversationKind.event;
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        color: isEvent ? scheme.tertiaryContainer : scheme.primaryContainer,
        borderRadius: BorderRadius.circular(size * 0.32),
      ),
      child: Icon(
        isEvent ? Icons.event : Icons.groups,
        size: size * 0.55,
        color: isEvent ? scheme.onTertiaryContainer : scheme.onPrimaryContainer,
      ),
    );
  }
}

/// The avatar of a conversation: the other person, or the chat's icon.
class ConversationAvatar extends StatelessWidget {
  const ConversationAvatar({
    super.key,
    required this.kind,
    required this.title,
    required this.counterpart,
    this.size = 44,
  });

  final ConversationKind kind;
  final String title;
  final ChatPerson? counterpart;
  final double size;

  @override
  Widget build(BuildContext context) {
    final person = counterpart;
    if (kind == ConversationKind.direct && person != null) {
      return PersonAvatar(name: title, seed: person.accountId, size: size);
    }
    return ChatKindAvatar(kind: kind, size: size);
  }
}

/// Up to two initials of a name, e.g. `AW` for `Anna Wiśniewska`.
String initials(String name) => name
    .split(' ')
    .where((part) => part.isNotEmpty)
    .take(2)
    .map((part) => part[0].toUpperCase())
    .join();

/// The square avatar of a conversation in the client app (#281): a person's
/// initials, or the icon of a group or a class chat.
class ClientConversationAvatar extends StatelessWidget {
  const ClientConversationAvatar(this.conversation, {super.key, this.size});

  final ConversationSummary conversation;

  /// Defaults to [ClientSizes.avatar].
  final double? size;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final dimension = size ?? ct.sizes.avatar;
    final person = conversation.counterpart;
    if (conversation.kind == ConversationKind.direct && person != null) {
      return SquareAvatar(
        initials: SquareAvatar.initialsOf(conversation.title),
        background: clientAvatarColor(context, person.accountId),
        foreground: c.onChatAvatar,
        size: dimension,
      );
    }
    return _ClientKindAvatar(kind: conversation.kind, size: dimension);
  }
}

/// The square icon of a group or class chat in the client app.
class _ClientKindAvatar extends StatelessWidget {
  const _ClientKindAvatar({required this.kind, required this.size});

  final ConversationKind kind;
  final double size;

  @override
  Widget build(BuildContext context) {
    final ct = context.ct;
    final c = ct.colors;
    final isEvent = kind == ConversationKind.event;
    return ExcludeSemantics(
      child: Container(
        width: size,
        height: size,
        color: isEvent ? c.primarySoft : c.primary,
        alignment: Alignment.center,
        child: Icon(
          isEvent ? Icons.calendar_today_outlined : Icons.group_outlined,
          size: ct.sizes.icon,
          color: c.ink,
        ),
      ),
    );
  }
}

/// A person's avatar colour from [ClientPalette.chatAvatars]; a person always
/// gets the same one.
Color clientAvatarColor(BuildContext context, String seed) {
  final colors = context.ct.colors.chatAvatars;
  return colors[seed.hashCode.abs() % colors.length];
}
