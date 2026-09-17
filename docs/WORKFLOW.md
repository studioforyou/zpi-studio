# docs/WORKFLOW.md

## Cykl życia kodu i wdrażanie (Workflow)

Nasz zespół pracuje w oparciu o jedną główną, chronioną gałąź `main` oraz krótkotrwałe gałęzie zadaniowe i Pull Requesty. Proces nie przewiduje utrzymywania osobnej gałęzi `dev` w celu ograniczenia kosztów pracy zespołu.

### Krok 1: Pobranie zadania i start prac
* Rozpoczynamy od przypisania się do zadania (Issue) na GitHubie.
* Zawsze tworzymy nową gałąź z aktualnej gałęzi `main`:
  ```bash
  git fetch --all
  git checkout main
  git pull origin main
  git checkout -b feature/12-rejestracja-klienta
  ```
* Na etapie developmentu używamy środowiska `local` (lokalna baza PostgreSQL w Docker Compose, mocki integracji). Uruchamiamy je komendą w głównym katalogu:
  ```bash
  docker-compose up -d --build
  ```

### Krok 2: Zgłoszenie zmian (Pull Request)
* Zapisujemy zmiany w kodzie zgodnie z konwencją, a następnie wypychamy gałąź do zdalnego repozytorium:
  ```bash
  git add .
  git commit -m "feat(backend): dodaj endpoint rejestracji (#12)"
  git push -u origin feature/12-rejestracja-klienta
  ```
* Po wypchnięciu otwieramy Pull Request (PR) celujący w `main` poprzez interfejs GitHuba.
* Narzędzie CI (GitHub Actions) automatycznie uruchamia proces budowania, lintowanie oraz testy (w tym testy architektury modułów).
* Oczekujemy na weryfikację kodu (Code Review) przez minimum jednego członka zespołu.
* Jeśli testy na CI wykazują błędy, wprowadzamy poprawki, tworzymy nowe commity i wypychamy je (`git push`) do tej samej gałęzi – pod żadnym pozorem nie łączymy nieprzetestowanego kodu.

### Krok 3: Integracja (Merge do main)
* Po uzyskaniu akceptacji i zaliczeniu testów CI, zamykamy PR korzystając wyłącznie z opcji **Squash and merge** na stronie GitHuba.
* Weryfikujemy, czy tytuł i opis finalnego commita są zgodne z konwencją projektową (zawierają m.in. `Closes #12`).
* Po zmergowaniu usuwamy gałąź zadaniową na GitHubie oraz czyścimy lokalne repozytorium:
  ```bash
  git checkout main
  git pull origin main
  git branch -d feature/12-rejestracja-klienta
  ```

### Krok 4: Wdrożenia (Staging i Produkcja)
* **Staging:** Deployment staging po merge do main następuje automatycznie. Środowisko to służy do integracji całego systemu i testów manualnych przez właścicieli z użyciem danych testowych.
* **Produkcja:** Wdrożenie produkcyjne do realnego studia wykonujemy po testach na środowisku stagingowym. Tag/release do produkcji jest tworzony w interfejsie GitHuba po akceptacji.
