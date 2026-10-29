import 'package:mobile/core/network/api_exception.dart';
import 'package:mobile/features/schedule/data/class_planning_api.dart';
import 'package:mobile/l10n/generated/app_localizations.dart';

/// The message for a plan the backend refused or could not check.
String planErrorMessage(
  Object error,
  AppLocalizations l10n, {
  bool saving = false,
}) {
  if (error is PlanRejectedException) {
    switch (error.code) {
      case 'SERVICE_NOT_ALLOWED_IN_ROOM':
        return l10n.addClassErrorRoomNotAllowed;
      case 'RESOURCE_UNAVAILABLE':
        return l10n.addClassErrorRoomUnavailable;
      case 'SERVICE_TYPE_UNAVAILABLE':
        return l10n.addClassErrorServiceTypeUnavailable;
      case 'STAFF_UNAVAILABLE':
        return l10n.addClassErrorTrainerUnavailable;
      case 'RESOURCE_CAPACITY_EXCEEDED' || 'STAFF_SCHEDULE_CONFLICT':
        return l10n.addClassErrorConflict;
      case 'VALIDATION_FAILED':
        return switch (error.field) {
          'startsAt' || 'startsOn' => l10n.addClassErrorPast,
          'endsOn' => l10n.addClassErrorEndsOn,
          'capacity' => l10n.addClassErrorCapacity,
          'weekdays' => l10n.addClassErrorNoOccurrences,
          _ => l10n.addClassErrorInvalid,
        };
    }
  }
  if (error is ApiException) {
    switch (error.statusCode) {
      case null:
        return l10n.addClassNetworkError;
      case 401 || 403:
        return l10n.addClassAccessError;
      case 404:
        return l10n.addClassNoStaffProfile;
    }
  }
  return saving ? l10n.addClassSaveError : l10n.addClassPreviewError;
}
