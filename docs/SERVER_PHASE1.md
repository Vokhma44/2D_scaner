# Сервер netscan — Фаза 1

Первый блок fleet-менеджмента создаёт центральный реестр агентов. Сканированные
коды на сервер не передаются и остаются в локальном контуре телефон → ПК.

## Реализовано

- PostgreSQL и автоматические миграции Flyway;
- одноразовые коды подключения с ограниченным сроком жизни;
- регистрация агента и выдача индивидуального секрета;
- heartbeat агента;
- единый список агентов со статусом `online` / `offline`;
- хранение только SHA-256-хешей секретов;
- Docker Compose для воспроизводимого запуска.

## Локальный запуск сервера

Создайте файл `.env.server` рядом с `docker-compose.server.yml`:

```dotenv
NETSCAN_DB_PASSWORD=<случайный пароль PostgreSQL>
NETSCAN_ADMIN_TOKEN=<случайный административный токен длиной от 32 символов>
```

Запустите:

```bash
docker compose --env-file .env.server -f docker-compose.server.yml up --build -d
curl http://127.0.0.1:8081/health
```

Ожидаемый ответ:

```json
{"status":"ok"}
```

Публичный API обслуживается Caddy по адресу `https://fleet.ruznak.io`. Caddy
автоматически выпускает и продлевает TLS-сертификат. Порт приложения `8081` и
PostgreSQL доступны только внутри VPS/Docker-сети.

Административная панель доступна по адресу `https://fleet.ruznak.io/admin`.
Для входа используется `NETSCAN_ADMIN_TOKEN`; браузер хранит его только в
`sessionStorage` текущей вкладки. Панель показывает статусы агентов, выпускает
одноразовые коды подключения и позволяет отзывать доступ агента.

## API первого блока

Все административные запросы используют заголовок:

```http
Authorization: Bearer <NETSCAN_ADMIN_TOKEN>
```

### Выпустить одноразовый код подключения

```http
POST /api/v1/admin/enrollment-tokens
Content-Type: application/json

{"label":"Склад Москва — ПК 1","ttlMinutes":30}
```

Сервер возвращает сырой код только один раз. В базе остаётся его хеш.

### Зарегистрировать агент

```http
POST /api/v1/agents/enroll
Content-Type: application/json

{
  "enrollmentToken":"<одноразовый код>",
  "displayName":"Приёмка — ПК 1",
  "hostName":"WAREHOUSE-PC-01",
  "agentVersion":"1.1.0",
  "osName":"Windows 11",
  "osVersion":"10.0"
}
```

В ответ агент получает собственный `agentToken`. Повторно использовать код
подключения нельзя.

### Heartbeat

```http
POST /api/v1/agents/heartbeat
Authorization: Bearer <agentToken>
Content-Type: application/json

{"agentVersion":"1.1.0","hostName":"WAREHOUSE-PC-01"}
```

### Получить реестр агентов

```http
GET /api/v1/admin/agents
Authorization: Bearer <NETSCAN_ADMIN_TOKEN>
```

Агент считается `offline`, если heartbeat отсутствует более 90 секунд.

## Подключение агента

После получения одноразового кода запустите агент один раз с параметрами:

```text
netscan.exe --fleet-server https://fleet.example.ru \
  --agent-name "Склад — ПК 1" \
  --enrollment-token <одноразовый код>
```

Адрес сервера и имя рабочего места сохраняются в `config.json`. Индивидуальный
секрет сохраняется отдельно в `fleet-credentials.json` и никогда не попадает в
конфигурацию или логи. После регистрации агент автоматически отправляет
heartbeat каждые 30 секунд. При обрыве связи используется экспоненциальный
повтор с интервалом до 60 секунд; локальное сканирование при этом не прерывается.

## Следующий блок

Удалённая раздача конфигурации и команды отзыва сопряжённых телефонов через уже
созданный исходящий канал агент → сервер.
