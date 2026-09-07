import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ZPI Studio',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      home: const PingPage(),
    );
  }
}

class PingPage extends StatefulWidget {
  const PingPage({super.key});

  @override
  State<PingPage> createState() => _PingPageState();
}

class _PingPageState extends State<PingPage> {
  String _serverResponse = "Naciśnij przycisk, aby połączyć z backendem";
  bool _isLoading = false;

  // UWAGA: Wybierz odpowiedni adres IP!
  // Emulator Androida: 'http://10.0.2.2:8080/api/ping'
  // Symulator iOS: 'http://127.0.0.1:8080/api/ping'
  // Fizyczny telefon: 'http://192.168.X.X:8080/api/ping' (wpisz IP swojego komputera z Wi-Fi)
  final String backendUrl = 'http://127.0.0.1:8080/api/ping';

  Future<void> _fetchPing() async {
    setState(() {
      _isLoading = true;
      _serverResponse = "Łączenie z serwerem...";
    });

    try {
      final response = await http.get(Uri.parse(backendUrl));

      if (response.statusCode == 200) {
        setState(() {
          _serverResponse = "Sukces!\n\nOdpowiedź Springa:\n${response.body}";
        });
      } else {
        setState(() {
          _serverResponse = "Błąd serwera. Status HTTP: ${response.statusCode}";
        });
      }
    } catch (e) {
      setState(() {
        _serverResponse = "Błąd połączenia (sprawdź adres IP):\n$e";
      });
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ZPI - Test Połączenia'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              const Icon(Icons.cloud_sync, size: 60, color: Colors.blueAccent),
              const SizedBox(height: 24),
              Text(
                _serverResponse,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.w500,
                ),
              ),
              const SizedBox(height: 24),
              if (_isLoading) const CircularProgressIndicator(),
            ],
          ),
        ),
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _isLoading ? null : _fetchPing,
        tooltip: 'Wyślij Ping',
        child: const Icon(Icons.network_ping),
      ),
    );
  }
}
