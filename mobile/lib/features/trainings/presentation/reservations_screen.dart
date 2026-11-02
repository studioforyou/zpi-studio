import 'package:flutter/widgets.dart';
import 'package:mobile/features/schedule/presentation/client_schedule_screen.dart';

class ReservationsScreen extends StatelessWidget {
  const ReservationsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const ClientScheduleScreen(reservationsOnly: true);
  }
}
