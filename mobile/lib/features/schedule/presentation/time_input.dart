import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

final _time = RegExp(r'^([01]?\d|2[0-3]):([0-5]\d)$');

/// The time of a `HH:mm` (or `H:mm`) text from 00:00 to 23:59, or `null`
/// for anything else, e.g. `33:33`.
TimeOfDay? parseTime(String text) {
  final match = _time.firstMatch(text.trim());
  if (match == null) {
    return null;
  }
  return TimeOfDay(
    hour: int.parse(match.group(1)!),
    minute: int.parse(match.group(2)!),
  );
}

/// Lets only digits and one colon into a time field, at most `HH:mm`, and
/// adds the colon after two typed digits of the hour.
class TimeInputFormatter extends TextInputFormatter {
  const TimeInputFormatter();

  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    var text = newValue.text;
    if (!RegExp(r'^\d{0,2}(:\d{0,2})?$').hasMatch(text)) {
      return oldValue;
    }
    final typing = text.length > oldValue.text.length;
    if (typing && RegExp(r'^\d{2}$').hasMatch(text)) {
      text = '$text:';
    }
    return TextEditingValue(
      text: text,
      selection: TextSelection.collapsed(offset: text.length),
    );
  }
}
