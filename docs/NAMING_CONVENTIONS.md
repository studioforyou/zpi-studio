# docs/NAMING_CONVENTIONS.md

## 1. Gałęzie (Branches)
Stosujemy krótkotrwale żyjące gałęzie zadaniowe (feature branches). Nazwa musi zawierać typ, numer zadania z GitHuba oraz krótki opis.

**Format:** `<typ>/<numer-issue>-<krotki-opis>`
* `feature/12-user-registration` – dla nowych funkcjonalności.
* `fix/34-payment-crash` – dla naprawy błędów.
* `refactor/45-booking-module` – dla zmian w strukturze.
* `chore/55-github-actions` – dla zadań technicznych.

## 2. Commity
Pracujemy w repozytorium typu monorepo, dlatego w commitach obowiązkowo określamy zakres (scope). Używamy trybu rozkazującego.

**Format:** `<typ>(<zakres-monorepo>): <opis> (#<numer-issue>)`
* `feat(backend): dodaj walidację zniżek karnetu (#12)`
* `fix(mobile): napraw błąd parsowania daty w kalendarzu (#34)`
* `infra(terraform): dodaj bucket na zdjęcia profilowe`

## 3. Pull Requesty (PR)
Tytuł PR będzie stanowić treść głównego commita na gałęzi docelowej po wykonaniu operacji "Squash and merge".

**Format:** `[#<numer-issue>] <Biznesowy opis zmiany>`
* `[#12] Rejestracja nowego klienta z weryfikacją e-mail`
* **Ważne:** W opisie PR zawsze umieszczamy frazę `Closes #<numer-issue>`, aby w momencie łączenia gałęzi automatycznie zamknąć powiązane zadanie na tablicy.