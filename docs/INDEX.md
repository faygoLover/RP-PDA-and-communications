# RP PDA and communications — указатель кода

Создано `python _family/tools/mapgen.py`, версия мода 0.5.4-1.20.1-forge.
**Руками не править**: пересоздаётся скриптом. Смысл, потоки работы и рецепты — в `docs/MAP.md`.
Разбор по шаблонам, без компиляции: необычно оформленная регистрация может отсутствовать в списках.

Всего: 57 файлов, 13664 строк Java.

## 1. Пакеты

| Пакет | Файлов | Строк |
|---|---:|---:|
| `faygolover.rppda` | 1 | 62 |
| `faygolover.rppda.api` | 2 | 128 |
| `faygolover.rppda.client` | 13 | 5808 |
| `faygolover.rppda.config` | 2 | 290 |
| `faygolover.rppda.data` | 7 | 1195 |
| `faygolover.rppda.gametest` | 2 | 772 |
| `faygolover.rppda.item` | 6 | 427 |
| `faygolover.rppda.mixin` | 2 | 69 |
| `faygolover.rppda.network` | 1 | 88 |
| `faygolover.rppda.server` | 19 | 4627 |
| `faygolover.rppda.util` | 2 | 198 |

## 2. Классы

### `faygolover.rppda`
- `RpPda`, 62 — RP PDA — КПК для семейства RP-модов (план — docs/00_plan.md): игровой мессенджер вместо чата и /tell.

### `faygolover.rppda.api`
- `PdaApps`, 102 — Разделы КПК от аддонов (только клиент).
- `PdaSkins`, 26 — Корпуса КПК от аддонов.

### `faygolover.rppda.client`
- `AvatarPics`, 128 — Свои картинки-аватарки (§7.6).
- `Avatars`, 104 — Вид аватарок по ID учёток (§7.6): клиент спрашивает сервер, помнит минуту.
- `ClientPda`, 70 — Клиент КПК: принимает ответы сервера, держит счётчик непрочитанного и открывает окно.
- `ClientSetup`, 127 — Клиент: клавиша КПК, значок непрочитанного в общем HUD RP Core.
- `ClientTime`, 67 — Время КПК на клиенте (находка 56): режим (RP или реальное) и таблица RP-часов с сервера (RpClock).
- `Duotone`, 202 — Аватарки в ретро-КПК (docs/20_retro_memory.md §3): картинка перекрашивается в плавный переход между двумя цветами экрана — каждая точка по своей яркости, тёмное к тёмному цвету, светлое к светлому (на ЖК тёмный — текст).
- `Field`, 194 — Однострочное поле в стиле КПК (вместо поля Minecraft): без чёрной подложки и тени, текст по высоте по центру, курсор считается по тому, что на экране (у пароля — по звёздочкам).
- `NoteArea`, 277 — Текст заметки в стиле КПК: фон экрана, без тени и чёрной рамки Minecraft.
- `PdaScreen`, 3921 — Окно КПК.
- `Sfx`, 77 — Звуки окна КПК (только у игрока, в sounds.json — rppda:pda.*; громкость файлов выровнена).
- `Skins`, 82 — Корпуса ретро-КПК: цвет (одноцветные) или текстура textures/gui/skin/ .png (с рисунком, из scripts/gen_skins.py), цвет и место надписи модели, рамка экрана, светлые/тёмные клавиши.
- `Ui`, 385 — Оформление КПК: цвета, значки атласа textures/gui/icons.png, кнопки-значки, аватары, время.
- `Xfers`, 174 — Передачи файлов этого игрока (docs/20_retro_memory.md §4): сервер раз в секунду присылает список.

### `faygolover.rppda.config`
- `PdaCommonConfig`, 66 — Общий конфиг (config/rppda-common.toml): то, что нужно до загрузки мира.
- `PdaConfig`, 224 — Серверный конфиг ( /serverconfig/rppda-server.toml).

### `faygolover.rppda.data`
- `Account`, 194 — Учётка КПК.
- `Chat`, 97 — Чат: общий, личный диалог двух учёток, группа или канал (пишет только админ).
- `ChatLog`, 43 — «Свежие» сообщения одного чата в памяти сервера (по возрастанию номера).
- `Look`, 68 — Внешний вид КПК, который задаёт ГМ: вид окна (свой у этого КПК или как у всех), корпус, цвет экрана (ретро), акцент (современный), название модели на корпусе.
- `Message`, 71 — Сообщение чата.
- `Note`, 106 — Заметка учётки.
- `PdaStore`, 616 — Все данные КПК на сервере, папка /rppda/: accounts.dat — учётки; chats.dat — список чатов; misc.dat — отложенные сообщения админам, журнал ГМов, последняя учётка игрока; chats/ .dat — «свежие» сообщения чата (грузятся, когда чат н…

### `faygolover.rppda.gametest`
- `MemoryGameTests`, 246 — Память ретро-КПК и передача заметок (docs/20_retro_memory.md).
- `PdaGameTests`, 526 — Проверка в настоящем сервере: регистрация, активный КПК, пароль и блокировка, /tell, чат без КПК, группы.

### `faygolover.rppda.item`
- `DecryptorItem`, 70 — Дешифратор: шифрует свою заметку или начинает расшифровку чужой (1 заряд, лежит в инвентаре).
- `LockpickItem`, 69 — Отмычка КПК.
- `MemoryBoardItem`, 31 — Плата памяти для ретро-КПК (docs/20_retro_memory.md): ставится в ячейку расширения в профиле и добавляет #kb КБ.
- `ModItems`, 69 — Предметы и звук мода.
- `PdaItem`, 150 — КПК игрока.
- `SystemPdaItem`, 38 — Системный КПК — инструмент ГМа (не его персонажа): все чаты и учётки.

### `faygolover.rppda.mixin`
- `ChatComponentAccess` (interface), 30 — Строки загрузки файла в чате игры (docs/20_retro_memory.md §4.1): строка «всегда внизу» — раз в секунду старая убирается и новая добавляется последней.
- `ChatComponentMixin`, 39 — Голубой фон у строк КПК в чате игры.

### `faygolover.rppda.network`
- `Net`, 88 — Сеть КПК — два общих пакета: действие игрока серверу (Action: имя + данные) и ответ/событие клиенту (View).

### `faygolover.rppda.server`
- `AccountActions`, 113 — Операции входа: регистрация аккаунта (игрока и NPC), разблокировка и проверка пароля.
- `Actions`, 519 — Действия из окна КПК.
- `AvatarPics`, 96 — Своя картинка-аватарка учётки (§7.6) — через картинки по ключу RP Core (Pictures), ключ rppda:avatar/ : так она есть и у NPC (у них нет игрока).
- `ChatIntercept`, 382 — Чат игры.
- `ChatInterceptAccess`, 20 — Для игровых тестов: речь (шёпот) снаружи пакета.
- `Cipher`, 328 — Зашифрованные заметки.
- `Devices`, 98 — Где у игрока КПК и какой из них активный.
- `GroupActions`, 164 — Операции групповых чатов: создание, участники, приглашения, права, выход.
- `Hack`, 384 — Взлом КПК отмычкой — как терминал Fallout.
- `Illiterate`, 71 — Особенность «Неграмотный» (RP Perks 2.0, 0.4.1): чужие сообщения и записки он видит каракулями — та же длина, пробелы и знаки на месте, буквы — закорючки (каждый раз одинаковые: от текста).
- `LookActions`, 115 — Операции ГМа над аккаунтом: внешний вид, сигнал, привязка к персонажу, удаление.
- `Memory`, 126 — Память ретро-КПК (docs/20_retro_memory.md).
- `Messaging`, 280 — Отправка сообщений и доставка: окна КПК, строки в чате игры (с голубым фоном — рисует клиент), звук, счётчик непрочитанного.
- `Nearby`, 270 — Обмен контактами рядом (§7.6): игроки в exchange_radius с рабочим КПК, запрос — второму, принял — оба в контактах друг у друга.
- `PdaCommands`, 155 — /rppda — для ГМа и командных блоков (основное — в системном КПК): signal player on|off, signal pda on|off — связь; look style|body|screen|accent|model …
- `PdaServer`, 568 — Состояние КПК на сервере: хранилище, открытые окна (сессии), активный КПК каждого игрока, разблокированные КПК с паролем и их держатели.
- `SystemActions`, 255 — Операции системного КПК (ГМ): список аккаунтов, чужая переписка, журнал, правки аккаунтов.
- `Transfers`, 420 — Передача заметок при реалистичных ретро-КПК (docs/20_retro_memory.md §4).
- `Views`, 263 — Что уходит клиенту: состояние окна, строки списка чатов, сообщения.

### `faygolover.rppda.util`
- `Passwords`, 40 — Пароли КПК: хранится только хэш PBKDF2 с солью — даже ГМ видит лишь «пароль есть».
- `RpClock`, 158 — RP-время на реальный момент (находка 56).

Классов без описания: 0 из 57.

## 3. Подписки на события

| Событие | Кто слушает |
|---|---|
| `ClientPlayerNetworkEvent.LoggingOut` | `ClientSetup.onLogout` |
| `FMLClientSetupEvent` | `ClientSetup.setup` |
| `FMLCommonSetupEvent` | `RpPda.commonSetup` |
| `ItemTossEvent` | `PdaServer.onToss` |
| `LevelEvent.Save` | `PdaServer.onSave` |
| `LivingDeathEvent` | `PdaServer.onDeath` |
| `ModConfigEvent.Loading` | `RpPda.onConfig` |
| `ModConfigEvent.Reloading` | `RpPda.onConfigReload` |
| `PictureChangedEvent` | `AvatarPics.onChanged` |
| `PlayerEvent.PlayerLoggedInEvent` | `PdaServer.onLogin` |
| `PlayerEvent.PlayerLoggedOutEvent` | `PdaServer.onLogout` |
| `RegisterCommandsEvent` | `ChatIntercept.onCommands`, `PdaCommands.register` |
| `RegisterKeyMappingsEvent` | `ClientSetup.keys` |
| `ServerChatEvent` | `ChatIntercept.onChat` |
| `ServerStartingEvent` | `PdaServer.onStarting` |
| `ServerStoppedEvent` | `PdaServer.onStopped` |
| `ServerStoppingEvent` | `PdaServer.onStopping` |
| `SoundEvent` | `Sfx.play` |
| `TickEvent.ClientTickEvent` | `ClientSetup.onClientTick` |
| `TickEvent.ServerTickEvent` | `PdaServer.onTick` |
| `net.minecraftforge.client.event.ScreenEvent.KeyPressed.Pre` | `ClientSetup.onScreenKey` |
| `net.minecraftforge.client.event.ScreenEvent.KeyReleased.Pre` | `ClientSetup.onScreenKeyUp` |

## 4. Сеть

Канал: `faygolover/rppda/network/Net.java`, имя `main`, версия протокола `1`.
- `Action` (направление не указано в регистрации)
- `View` (направление не указано в регистрации)

Классы с обработчиками пакетов (1): `Net`.

## 5. Команды

- `faygolover/rppda/server/ChatIntercept.java`: `msg` `tell` `w` `me`
- `faygolover/rppda/server/PdaCommands.java`: `rppda` `signal` `player` `on` `off` `pda` `look` `model` `reset` `give` `gm`

## 6. Данные

- свои файлы на диске: `PdaStore`

## 7. Конфиги

- server: `PdaConfig`, вызовов define 36
- common: `PdaCommonConfig`, вызовов define 1

## 8. Что берёт из RP Core и что ему даёт

Методы `RpCoreAPI` (сколько вызовов): `SpeechChannel` 5, `filterSpeech` 5, `handsFree` 1, `hasTrait` 3, `registerModule` 1, `unconscious` 11.

События Core: `PictureChangedEvent`, `RpMoodEvent`.

## 9. Связи с другими модами

Проверки `isLoaded`: `curios` (PdaGameTests).

Пакеты других модов семейства в коде: `rpcore` — 12 файлов.

Миксины (2):
- `ChatComponentMixin` (client) → ChatComponent.class
- `ChatComponentAccess` (client) → ChatComponent.class

## 10. Зарегистрированные id

- `faygolover/rppda/item/ModItems.java` (7): `pda` `system_pda` `lockpick` `decryptor` `notify` `main` `memory_`
