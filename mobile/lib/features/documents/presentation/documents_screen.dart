import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile/core/auth/auth_controller.dart';
import 'package:mobile/core/auth/auth_state.dart';
import 'package:mobile/features/documents/data/documents_api.dart';
import 'package:mobile/features/documents/presentation/document_widgets.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// Documents and consents of the active customer profile: required documents
/// to read and accept, and optional consents to give or withdraw.
class DocumentsScreen extends ConsumerWidget {
  const DocumentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final session = switch (ref.watch(authControllerProvider)) {
      Authenticated(:final session) => session,
      _ => null,
    };
    final profileId = session?.activeProfileId;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.documentsTitle)),
      body: session == null || profileId == null
          ? _Message(l10n.documentsNoProfile)
          : ref
                .watch(ownDocumentsProvider(profileId))
                .when(
                  loading: () =>
                      const Center(child: CircularProgressIndicator()),
                  error: (_, _) => _LoadError(
                    onRetry: () =>
                        ref.invalidate(ownDocumentsProvider(profileId)),
                  ),
                  data: (documents) =>
                      _DocumentList(profileId: profileId, documents: documents),
                ),
    );
  }
}

class _DocumentList extends ConsumerWidget {
  const _DocumentList({required this.profileId, required this.documents});

  final String profileId;
  final List<OwnDocument> documents;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final required = [
      for (final document in documents)
        if (document.required) document,
    ];
    final consents = [
      for (final document in documents)
        if (!document.required) document,
    ];
    final pending = [
      for (final document in required)
        if (!document.isAccepted) document,
    ];

    return RefreshIndicator(
      onRefresh: () => ref.refresh(ownDocumentsProvider(profileId).future),
      child: ListView(
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          if (documents.isEmpty) _Message(l10n.documentsEmpty),
          if (pending.isNotEmpty)
            _PendingBanner(
              count: pending.length,
              blocksBooking: pending.any((d) => d.blocksBooking),
            ),
          if (required.isNotEmpty) ...[
            _SectionTitle(l10n.documentsSectionRequired),
            for (final document in required)
              _RequiredDocumentTile(document: document, profileId: profileId),
          ],
          if (consents.isNotEmpty) ...[
            _SectionTitle(l10n.documentsSectionConsents),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 4),
              child: Text(
                l10n.documentsConsentsHint,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
            for (final document in consents)
              _ConsentTile(document: document, profileId: profileId),
          ],
        ],
      ),
    );
  }
}

class _PendingBanner extends StatelessWidget {
  const _PendingBanner({required this.count, required this.blocksBooking});

  final int count;
  final bool blocksBooking;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final colors = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    return Container(
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 8),
      decoration: BoxDecoration(
        color: colors.errorContainer,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          spacing: 12,
          children: [
            Icon(
              Icons.assignment_late_outlined,
              color: colors.onErrorContainer,
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                spacing: 4,
                children: [
                  Text(
                    l10n.documentsPendingBanner(count),
                    style: textTheme.titleSmall?.copyWith(
                      color: colors.onErrorContainer,
                    ),
                  ),
                  Text(
                    blocksBooking
                        ? l10n.documentsPendingBlocksBooking
                        : l10n.documentsPendingInfo,
                    style: textTheme.bodyMedium?.copyWith(
                      color: colors.onErrorContainer,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RequiredDocumentTile extends StatelessWidget {
  const _RequiredDocumentTile({
    required this.document,
    required this.profileId,
  });

  final OwnDocument document;
  final String profileId;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final colors = Theme.of(context).colorScheme;
    final status = documentStatusText(l10n, document);
    return ListTile(
      leading: Icon(documentIcon(document.kind)),
      title: Text(document.title),
      subtitle: Text(
        [
          status,
          if (document.requiredFor.isNotEmpty)
            l10n.documentRequiredFor(document.requiredFor.join(', ')),
        ].join('\n'),
        style: document.isAccepted ? null : TextStyle(color: colors.error),
      ),
      isThreeLine: document.requiredFor.isNotEmpty,
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          DocumentStatusIcon(document),
          const Icon(Icons.chevron_right),
        ],
      ),
      onTap: () => context.push('$documentsRoute/${document.id}'),
    );
  }
}

class _ConsentTile extends ConsumerStatefulWidget {
  const _ConsentTile({required this.document, required this.profileId});

  final OwnDocument document;
  final String profileId;

  @override
  ConsumerState<_ConsentTile> createState() => _ConsentTileState();
}

class _ConsentTileState extends ConsumerState<_ConsentTile> {
  bool _saving = false;

  Future<void> _toggle(bool give) async {
    final l10n = AppLocalizations.of(context);
    final messenger = ScaffoldMessenger.of(context);
    if (!give && !await confirmRevoke(context)) {
      return;
    }
    setState(() => _saving = true);
    final api = ref.read(documentsApiProvider);
    final document = widget.document;
    try {
      if (give) {
        await api.accept(widget.profileId, document.id, document.versionId);
      } else {
        await api.revoke(widget.profileId, document.id);
      }
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            give ? l10n.documentConsentSaved : l10n.documentConsentRevoked,
          ),
        ),
      );
    } on DocumentApiException catch (e) {
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            e.isVersionConflict
                ? l10n.documentVersionConflict
                : l10n.documentActionError,
          ),
        ),
      );
    } finally {
      if (mounted) {
        setState(() => _saving = false);
      }
      ref.invalidate(ownDocumentsProvider(widget.profileId));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final document = widget.document;
    return ListTile(
      leading: Icon(documentIcon(document.kind)),
      title: Text(document.title),
      subtitle: Text(documentStatusText(l10n, document)),
      trailing: _saving
          ? const SizedBox.square(
              dimension: 24,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : Switch(value: document.isAccepted, onChanged: _toggle),
      onTap: () => context.push('$documentsRoute/${document.id}'),
    );
  }
}

/// Asks before withdrawing a consent.
Future<bool> confirmRevoke(BuildContext context) async {
  final l10n = AppLocalizations.of(context);
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(l10n.documentRevokeTitle),
      content: Text(l10n.documentRevokeMessage),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: Text(l10n.documentCancel),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: Text(l10n.documentRevokeConfirm),
        ),
      ],
    ),
  );
  return confirmed ?? false;
}

/// Where the documents screen lives, under the client's profile tab.
const documentsRoute = '/client/profile/documents';

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(text, style: Theme.of(context).textTheme.titleSmall),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Text(text, textAlign: TextAlign.center),
    );
  }
}

class _LoadError extends StatelessWidget {
  const _LoadError({required this.onRetry});

  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        spacing: 16,
        children: [
          Text(l10n.documentsLoadError),
          OutlinedButton(
            onPressed: onRetry,
            child: Text(l10n.documentsRetryButton),
          ),
        ],
      ),
    );
  }
}
