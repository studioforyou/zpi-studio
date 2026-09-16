# zpi-studio

## Baza danych (PostgreSQL w Docker Compose)

### Konfiguracja i uruchomienie

Wymagany zainstalowany i uruchomiony Docker (Docker Desktop na Windows/macOS).

1. Skopiuj plik z przykładowymi zmiennymi środowiskowymi:
   
```
cp .env.example .env
```
   i w razie potrzeby zmień w nim `LOCAL_DB_USER` / `LOCAL_DB_PASSWORD`.

2. Uruchom lokalną bazę danych jedną komendą:

```
docker compose up -d postgres
```

Baza wystartuje na porcie `5432` (baza `zpi_studio`),  Dane są trzymane w wolumenie `postgres_data`; przetrwają restart kontenera.

Zatrzymanie bazy:
```
docker compose down
```

### Nawiązanie połączenia

Połączenie z bazą danych może być nawiązane na dwa sposoby:

1. z poziomu kontenera
```
docker exec -it zpi_postgres psql -U zpi_user -d zpi_studio
```

2. z poziomu hosta
```bash/
psql -h localhost -p 5432 -U zpi_user -d zpi_studio
# (hasło jak w .env (LOCAL_DB_PASSWORD))
```

Ewntualnie, 3. poprzez GUI - DBeaver, pgAdmin itd...