# ugame-server

Каркас бэкенда онлайн-игры в духе Brawl Stars: клиент на Unity, сервер на Java.

```
                 HTTP (JSON, JWT)                       UDP (kcp2k)
 Unity ───────────────────────────▶ meta-service    Unity ───────────────▶ game-server
 клиент   регистрация, вход,          WebFlux         клиент  JoinRequest(ticket),   Netty + KCP
          профиль, матчмейкинг            │                   ввод 30 Гц / снапшоты      │
                                          │                                              │
                                          ▼                                              ▼
                                 PostgreSQL   Redis ◀─────── билеты, heartbeat, результаты ──┘
```

| Модуль | Что внутри |
|---|---|
| `protocol` | `game.proto` (надёжные сообщения) и ручная бинарная упаковка частых пакетов (`InputPacket`, `SnapshotPacket`) |
| `game-server` | UDP-сервер на Netty, совместимый с **kcp2k**; комнаты матчей с фиксированным тиком; работа с Redis через Lettuce |
| `meta-service` | Spring Boot 4 + **WebFlux** (реактивный, неблокирующий): регистрация, вход, JWT + refresh-токены, профиль, матчмейкинг, приём результатов матчей. PostgreSQL через R2DBC, Redis через реактивный Lettuce, Lombok |
| `unity-client` | C#-скрипты для Unity: протокол, подключение через kcp2k, HTTP-клиент мета-сервиса |

## Быстрый старт

Нужны JDK 21 (Gradle скачает сам, если его нет) и Docker.

```bash
docker compose up -d                 # PostgreSQL 17 + Redis 7
./gradlew :meta-service:bootRun      # http://localhost:8080
./gradlew :game-server:run           # udp://0.0.0.0:7777
```

На Windows — `gradlew.bat`. В IntelliJ IDEA: *Open* → папка проекта, Gradle-проект подхватится сам.

Тесты: `./gradlew test`. Самый важный — `KcpServerCompatibilityTest`: поднимает сервер и гоняет его
против клиента, повторяющего поведение kcp2k (рукопожатие с cookie, оба канала, фрагментация, таймаут).

## Путь игрока

```bash
# 1. регистрация (или /api/auth/login)
curl -X POST localhost:8080/api/auth/register -H 'Content-Type: application/json' \
     -d '{"username":"alice","password":"secret123"}'
# → {"accessToken":"…","refreshToken":"…","expiresIn":900,"playerId":1,"username":"alice"}

# 2. встать в очередь и опрашивать статус
curl -X POST localhost:8080/api/matchmaking/queue -H "Authorization: Bearer $TOKEN"
curl        localhost:8080/api/matchmaking/status -H "Authorization: Bearer $TOKEN"
# → {"state":"MATCHED","matchId":"…","host":"127.0.0.1","port":7777,"ticket":"…","team":0}
```

3. Клиент подключается к `host:port` через kcp2k и первым сообщением отправляет `JoinRequest{ticket}`.
4. Игровой сервер гасит билет в Redis (`GETDEL` — второй раз не сработает) и сажает игрока в комнату.
5. Когда собрались все (или прошло `match.join-timeout-sec`), приходит `MatchStart`, затем снапшоты 30 раз в секунду.
6. По окончании — `MatchEnd`; результат уходит в Redis Stream, мета-сервис начисляет кубки (+8 / −5).

| Метод | Путь | Доступ |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | открыт |
| POST | `/api/auth/refresh` (ротация refresh-токена), `/api/auth/logout` | открыт |
| GET | `/api/me` | JWT |
| POST / DELETE | `/api/matchmaking/queue` | JWT |
| GET | `/api/matchmaking/status` | JWT |

Ошибки — `application/problem+json` с полем `code` (`username_taken`, `invalid_credentials`, …).

## Протокол игрового сервера

**Транспорт — kcp2k.** kcp2k — это не «голый» KCP: перед каждым UDP-пакетом он пишет
`[u8 канал][u32 cookie]`, а внутри KCP-сообщений — свой заголовок (Hello / Ping / Data / Pong)
и отдельный Disconnect. Поэтому из `java-kcp` взято только ядро алгоритма (`kcp.Kcp`),
а обвязка (`game-server/.../net/KcpConnection.java`, `KcpServer.java`) повторяет
`KcpPeer` / `KcpServerConnection` из kcp2k. Параметры KCP (MTU 1200, окна 32/128, interval 10)
**должны совпадать** в `game-server.properties` и в `KcpConfig` на клиенте.

**Прикладной уровень**: первый байт — `MessageType`, дальше тело.

| Тип | Канал | Формат | Сообщение |
|---|---|---|---|
| 1 C2S_JOIN | reliable | protobuf | `JoinRequest` |
| 2 S2C_JOIN_RESULT | reliable | protobuf | `JoinResult` |
| 3 S2C_MATCH_START | reliable | protobuf | `MatchStart` |
| 4 S2C_MATCH_END | reliable | protobuf | `MatchEnd` |
| 20 C2S_INPUT | unreliable | ручной | последние 1–4 команды ввода (7 байт каждая) |
| 21 S2C_SNAPSHOT | unreliable | ручной | 12 байт заголовок + 11 байт на сущность |

Ввод содержит несколько последних команд, поэтому потеря одного пакета не теряет нажатие.
В снапшоте есть `ackInputSeq` — номер последней обработанной команды игрока для
client-side prediction и сверки.

## meta-service: реактивный стек

Сервис полностью неблокирующий: запросы обслуживает небольшой пул потоков Netty, а каждый
обработчик возвращает `Mono`/`Flux`.

| Задача | Чем сделано |
|---|---|
| HTTP | Spring WebFlux (Reactor Netty) |
| Безопасность | Spring Security для WebFlux, `ReactiveJwtDecoder` |
| PostgreSQL | Spring Data R2DBC + `r2dbc-postgresql`; счётчики игрока — атомарный `UPDATE` |
| Транзакции | `TransactionalOperator` (`mono.as(tx::transactional)`) |
| Redis | `ReactiveStringRedisTemplate`; `SCAN` вместо `KEYS` |
| Фоновые задачи | `Flux.interval` вместо `@Scheduled`, без наложения запусков |
| Миграции | Flyway через JDBC — один раз при старте, до приёма запросов |
| bcrypt | на `Schedulers.boundedElastic()`, чтобы не занимать потоки event loop |

Правило для нового кода: внутри `Mono`/`Flux` нельзя вызывать блокирующие API (JDBC, `RestTemplate`,
`Thread.sleep`, `.block()`). Если без этого никак — `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`.

**Lombok** подключён только в meta-service: `@RequiredArgsConstructor` для внедрения зависимостей,
`@Slf4j` для логгера, `@Getter`/`@Builder` для сущности `Player`. DTO оставлены Java-записями (`record`) —
они и так компактны. В IntelliJ IDEA плагин Lombok встроен; включите
*Settings → Build → Compiler → Annotation Processors → Enable annotation processing*.

## Потоки в игровом сервере

- **1 поток event loop Netty**: приём UDP, KCP update каждые 10 мс, отправка. Без блокировок.
- **Пул `room-tick-*`** (по числу ядер): каждая комната тикает 30 раз в секунду, тики одной комнаты
  никогда не идут параллельно. Сеть общается с комнатой только через очередь `Room.post()`.
- **Lettuce** — асинхронно; ни один поток не ждёт Redis.

## Unity

1. Скопируйте `unity-client/Assets/Scripts/Net` в проект.
2. Добавьте исходники [kcp2k](https://github.com/MirrorNetworking/kcp2k) (папка `kcp2k/kcp2k`) — или используйте ту, что идёт в Mirror.
3. Установите `Google.Protobuf` (например, через NuGetForUnity) той же мажорной версии, что и `protoc`.
4. Сгенерируйте C#-классы:
   `protoc --csharp_out=Assets/Scripts/Net/Generated protocol/src/main/proto/game.proto`
5. `MetaApi` — вход и матчмейкинг, `GameConnection` — подключение к матчу и отправка ввода.

## Что дальше

Это каркас: путь от регистрации до записи результата матча реализован целиком, но игровая логика минимальна.

- **Симуляция** (`World.java`): арена без стен, один тип атаки. Дальше — карты с препятствиями,
  бойцы с разными атаками, супер, кусты, режимы.
- **Компенсация лагов**: хранить историю позиций и проверять попадания «в прошлом» на RTT клиента.
- **Снапшоты**: сейчас полные; при росте числа сущностей — дельта-сжатие относительно подтверждённого
  снапшота и область видимости (кусты!).
- **Матчмейкинг**: «первые N из очереди». Дальше — подбор по кубкам и атомарный набор (Lua-скрипт),
  если экземпляров мета-сервиса несколько.
- **Шифрование**: kcp2k не шифрует трафик; билет одноразовый и живёт 60 секунд, cookie защищает от
  подмены адреса, но для защиты содержимого нужен DTLS или своё шифрование поверх.
- **Масштабирование**: несколько игровых серверов уже поддерживаются (heartbeat в Redis, мета-сервис
  выбирает наименее загруженный); для продакшена — Agones/Kubernetes.

## Конфигурация

- `game-server/src/main/resources/game-server.properties` — любой ключ переопределяется переменной
  `UGAME_<КЛЮЧ>` (`UGAME_UDP_PORT=7778`) или `-Dugame.udp.port=7778`.
- `meta-service/src/main/resources/application.yml` — БД (`UGAME_DB_HOST`, `UGAME_DB_USER`, …), Redis, JWT, размер матча
  (`UGAME_PLAYERS_PER_MATCH`, по умолчанию 2 для отладки; для 3×3 — 6).
- **Секрет JWT в `application.yml` только для разработки.** В проде задайте `UGAME_JWT_SECRET`
  (`openssl rand -base64 32`).
