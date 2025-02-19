# Stream Scout

Projekt ma na celu stworzenie bota na Twitch'a w celu nauki Scali, programowania aktorowego, systemów rozproszonych i wzorców CQRS, Event Sourcing

---
### Funkcjonalności:
- Podstawowe komendy (np. !help, !uptime, !stats)
- Archiwizacja chatu, zapis watchtime'u
- System rekomendacji - polecane kanały na podstawie aktywności
- Analiza sentymentu na podstawie modeli językowych (BERT)
---
### Lista dostępnych komend:
- **$watchtime [channel] [broadcaster]** - watchtime na kanale
- **$topwatchtime [channel]** - ranking kanałów z największym watchtimem
- **$lastseen [channel]** - gdzie i kiedy był ostatnio widziany kanał
- **$lastmessage [channel] [broadcaster]** - ostatnia wiadomość
- **$uptime [channel]** - ile trwa stream
- **$viewers [channel]** - ilu widzów ma kanał
- **$top10pl** - top 10 aktualnych polskich kanałów
- **$recommended [channel]** - top 5 polecanych kanałów
- **$usersentiment [channel]** - analiza sentymentu (*sentiment analysis*) użytkownika
- **$channelsentiment [broadcaster]** - analiza sentymentu (*sentiment analysis*) kanału
---
### Wymagania wstępne

Przed rozpoczęciem upewnij się, że masz zainstalowane następujące narzędzia:

- **Java JDK 18**
- **sbt 1.10.7**
- **Docker**

### Uruchomienie

Aby uruchomić projekt, wykonaj następujące kroki:

1. Sklonuj repozytorium:
   `git clone https://github.com/skni-kod/StreamScout.git`
2. Skonfiguruj pliki `.env` i `.sbtopts`, korzystając z przykładów w plikach `.env.example` i `.sbtopts.example`
3. Dodaj listę streamerów, na których bot będzie aktywny w pliku `src/resources/channels.json` (limit 50 kanałów / token)
4. Uruchom projekt i kontenery za pomocą skryptu `./run.sh`

### Technologie:
- Scala
- Akka
- Cassandra
- Kafka
- Docker