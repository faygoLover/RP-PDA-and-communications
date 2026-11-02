package faygolover.rppda.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Серверный конфиг ({@code <мир>/serverconfig/rppda-server.toml}). Значения читаются через {@link #bake()}. */
public final class PdaConfig {
    private PdaConfig() {}

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue CHAT_TO_PDA;
    private static final ForgeConfigSpec.IntValue CHAT_RADIUS;
    private static final ForgeConfigSpec.IntValue WHISPER_RADIUS;
    private static final ForgeConfigSpec.IntValue SHOUT_RADIUS;
    private static final ForgeConfigSpec.IntValue ME_RADIUS;
    private static final ForgeConfigSpec.BooleanValue ALLOW_CHAT_OUTPUT;
    private static final ForgeConfigSpec.BooleanValue TELL_REQUIRES_DIALOG;
    private static final ForgeConfigSpec.BooleanValue TELL_BY_NAME;
    private static final ForgeConfigSpec.IntValue ADMIN_LEVEL;
    private static final ForgeConfigSpec.IntValue MAX_LENGTH;
    private static final ForgeConfigSpec.IntValue RATE_LIMIT;
    private static final ForgeConfigSpec.IntValue CHECK_TICKS;
    private static final ForgeConfigSpec.IntValue NAME_TICKS;
    private static final ForgeConfigSpec.IntValue ARCHIVE_MINUTES;
    private static final ForgeConfigSpec.IntValue PENDING_LIMIT;
    private static final ForgeConfigSpec.IntValue PASSWORD_ATTEMPTS;
    private static final ForgeConfigSpec.IntValue LOCKOUT_SECONDS;
    private static final ForgeConfigSpec.DoubleValue SOUND_VOLUME;
    private static final ForgeConfigSpec.ConfigValue<String> STYLE;
    private static final ForgeConfigSpec.BooleanValue RP_TIME;
    private static final ForgeConfigSpec.IntValue HACK_ATTEMPTS;
    private static final ForgeConfigSpec.IntValue DECRYPTOR_CHARGES;
    private static final ForgeConfigSpec.IntValue DECRYPTOR_COOLDOWN;
    private static final ForgeConfigSpec.IntValue CIPHER_SECONDS;
    private static final ForgeConfigSpec.IntValue EXCHANGE_RADIUS;
    private static final ForgeConfigSpec.IntValue AVATAR_UPLOAD_SECONDS;
    private static final ForgeConfigSpec.BooleanValue REALISTIC_RETRO;
    private static final ForgeConfigSpec.IntValue RETRO_MEMORY;
    private static final ForgeConfigSpec.IntValue RETRO_SPEED;
    private static final ForgeConfigSpec.IntValue MODERN_SPEED;
    private static final ForgeConfigSpec.IntValue TRANSFER_RADIUS;
    private static final ForgeConfigSpec.IntValue TRANSFER_PEERS;
    private static final ForgeConfigSpec.IntValue INVITE_MINUTES;
    private static final ForgeConfigSpec.IntValue GLITCH_CHANCE;

    public static boolean chatToPda = false;
    public static int chatRadius = 16;
    public static int whisperRadius = 3;
    public static int shoutRadius = 48;
    public static int meRadius = 16;
    public static boolean allowChatOutput = false;
    public static boolean tellRequiresDialog = true;
    public static boolean tellByName = false;
    public static int adminLevel = 2;
    public static int maxLength = 256;
    public static int rateLimit = 8;
    public static int checkTicks = 40;
    public static int nameTicks = 100;
    public static int archiveMinutes = 10;
    public static int pendingLimit = 100;
    public static int passwordAttempts = 5;
    public static int lockoutSeconds = 60;
    public static double soundVolume = 0.7;
    /** Вид КПК, которому при выдаче не задан свой: "retro" или "modern". */
    public static String style = "retro";
    /** Время в КПК — RP (часы мира, номер дня RP) или реальное. */
    public static boolean rpTime = true;
    public static int hackAttempts = 4;
    public static int decryptorCharges = 4;
    public static int decryptorCooldown = 10;
    public static int cipherSeconds = 60;
    public static int exchangeRadius = 5;
    public static int avatarUploadSeconds = 30;
    /** Реалистичные ретро-КПК: память в байтах, заметки — только приглашением с медленной передачей рядом. */
    public static boolean realisticRetro = false;
    public static int retroMemory = 2048;
    public static int retroSpeed = 10;
    public static int modernSpeed = 200;
    public static int transferRadius = 16;
    public static int transferPeers = 2;
    public static int inviteMinutes = 5;
    public static int glitchChance = 20;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("speech");
        CHAT_TO_PDA = b.comment("Обычный чат игры пишется в общий чат КПК (как в первой версии). Выключено — обычный чат — это речь рядом,",
                        "а в общий чат пишут только из КПК.",
                        "Plain chat goes to the PDA global chat. Off: plain chat is local speech.")
                .define("chat_to_pda_global", false);
        CHAT_RADIUS = b.comment("Речь (обычный чат): слышно в этом радиусе, блоки. Админы слышат всех.")
                .defineInRange("chat_radius", 16, 1, 512);
        WHISPER_RADIUS = b.comment("Шёпот (сообщение с ~ в начале): ясно слышно в этом радиусе. Дальше — до ×1,17 пропадают части фразы,",
                        "до ×1,33 — большая часть, до ×1,67 слышно только «*шёпот*».")
                .defineInRange("whisper_radius", 3, 1, 64);
        SHOUT_RADIUS = b.comment("Крик (сообщение с ! в начале): ясно слышно в этом радиусе, до ×1,5 — «*отдалённый крик*».")
                .defineInRange("shout_radius", 48, 1, 512);
        ME_RADIUS = b.comment("/me видят игроки в этом радиусе (блоки) — в обычном чате, как действие персонажа.")
                .defineInRange("me_radius", 16, 1, 512);
        b.pop();
        b.push("chat");
        ALLOW_CHAT_OUTPUT = b.comment("Игроки могут включить вывод чата КПК ещё и в чат игры (кнопка в чате КПК). Выключено — только в КПК.",
                        "Админы видят общий чат КПК в чате игры всегда.")
                .define("allow_chat_output", false);
        TELL_REQUIRES_DIALOG = b.comment("/tell <ID учётки> работает только если с этой учёткой уже есть диалог (начинают его в КПК).")
                .define("tell_requires_dialog", true);
        TELL_BY_NAME = b.comment("/tell <ник> между обычными игроками (через их КПК). Выключено — по нику можно писать только админу",
                        "(и админ — любому). Игрокам — по ID учётки.")
                .define("tell_by_name", false);
        ADMIN_LEVEL = b.comment("Уровень оператора, с которого игрок — админ: системный КПК, /tell без КПК, слышит всю речь.")
                .defineInRange("admin_permission_level", 2, 1, 4);
        MAX_LENGTH = b.comment("Наибольшая длина сообщения, символов.").defineInRange("max_message_length", 256, 16, 2000);
        RATE_LIMIT = b.comment("Не больше стольких сообщений за 10 секунд от одной учётки.").defineInRange("messages_per_10s", 8, 1, 100);
        PENDING_LIMIT = b.comment("Сколько сообщений админам хранить за игроком без учётки (старые удаляются).")
                .defineInRange("pending_limit", 100, 0, 1000);
        SOUND_VOLUME = b.comment("Громкость звука уведомления (его слышат игроки рядом). 0 — без звука.")
                .defineInRange("notify_volume", 0.7, 0.0, 4.0);
        b.pop();
        b.push("look");
        STYLE = b.comment("Вид КПК, которому ГМ при выдаче не задал свой: retro — 80-е (корпус с клавишами, люминофор),",
                        "modern — современный. PDA look for devices without their own: retro or modern.")
                .define("pda_style", "retro", v -> v instanceof String s && (s.equals("retro") || s.equals("modern")));
        RP_TIME = b.comment("Время в КПК: true — RP-время (часы мира, как у RP Time; дата — день RP), false — реальное время.",
                        "Переключает вместе часы КПК и время сообщений, заметок, журнала. Сообщения хранят реальный момент —",
                        "переключать можно когда угодно, старые покажутся по-новому.",
                        "PDA time: true — RP time (world clock, RP day number), false — real time.")
                .define("rp_time", true);
        b.pop();
        b.push("devices");
        CHECK_TICKS = b.comment("Как часто (тики) искать активный КПК игрока и проверять, не ушёл ли разблокированный КПК с паролем.")
                .defineInRange("check_interval_ticks", 40, 10, 400);
        NAME_TICKS = b.comment("Как часто (тики) перечитывать имя игрока (fakename) для его учёток.")
                .defineInRange("name_refresh_ticks", 100, 20, 2400);
        PASSWORD_ATTEMPTS = b.comment("Неверных паролей подряд до паузы.").defineInRange("password_attempts", 5, 1, 100);
        LOCKOUT_SECONDS = b.comment("Пауза после неверных паролей, секунд.").defineInRange("lockout_seconds", 60, 0, 3600);
        EXCHANGE_RADIUS = b.comment("Обмен контактами рядом: в скольких блоках видно других игроков с КПК (связь не нужна).")
                .defineInRange("exchange_radius", 5, 1, 32);
        AVATAR_UPLOAD_SECONDS = b.comment("Своя картинка-аватарка: игрок меняет её не чаще раза в столько секунд.")
                .defineInRange("avatar_upload_seconds", 30, 0, 86400);
        b.pop();
        b.push("hacking");
        HACK_ATTEMPTS = b.comment("Взлом КПК отмычкой: попыток до блокировки КПК (ошибки помнит КПК, не отмычка).")
                .defineInRange("hack_attempts", 4, 1, 10);
        DECRYPTOR_CHARGES = b.comment("Зарядов у нового дешифратора (шифрование или расшифровка заметки — 1 заряд).")
                .defineInRange("decryptor_charges", 4, 1, 64);
        DECRYPTOR_COOLDOWN = b.comment("После каждого заряда дешифратор перегенерирует матрицу столько минут.")
                .defineInRange("decryptor_cooldown_minutes", 10, 0, 1440);
        CIPHER_SECONDS = b.comment("Расшифровка заметки: секунд на матрицу (отсчёт с первого выбора).")
                .defineInRange("decrypt_seconds", 60, 10, 600);
        b.pop();
        b.push("retro_memory");
        REALISTIC_RETRO = b.comment("Реалистичные ретро-КПК: у ретро-КПК своя маленькая память (контакты, аватарка, заметки, подгруженная",
                        "история чатов), ячейки расширения с платами памяти. Заметками делятся только приглашением в чате: файл",
                        "медленно передаётся рядом (у всех КПК). Realistic retro PDAs: tiny memory, slow nearby note transfers.")
                .define("realistic_retro", false);
        RETRO_MEMORY = b.comment("Своя память ретро-КПК без плат, байт (1 знак = 1 байт).")
                .defineInRange("retro_memory_bytes", 2048, 256, 1_048_576);
        RETRO_SPEED = b.comment("Скорость передачи файла ретро-КПК, байт/с. Передача идёт по самому медленному звену.")
                .defineInRange("retro_bytes_per_second", 10, 1, 100_000);
        MODERN_SPEED = b.comment("Скорость передачи файла современного КПК, байт/с.")
                .defineInRange("modern_bytes_per_second", 200, 1, 100_000);
        TRANSFER_RADIUS = b.comment("Передача файла: все, кто качает, должны быть не дальше этого от отправителя, блоки.")
                .defineInRange("transfer_radius", 16, 1, 256);
        TRANSFER_PEERS = b.comment("Сколько человек могут качать один файл сразу (скорость отправителя делится между ними).")
                .defineInRange("transfer_max_peers", 2, 1, 32);
        INVITE_MINUTES = b.comment("Приглашение скачать файл живёт столько минут (или пока отправитель его не отменит).")
                .defineInRange("invite_minutes", 5, 1, 120);
        GLITCH_CHANCE = b.comment("Память переполнена: с таким шансом (%) при смене экрана КПК начинает глючить.")
                .defineInRange("overflow_glitch_percent", 20, 0, 100);
        b.pop();
        b.push("storage");
        ARCHIVE_MINUTES = b.comment("Как часто (минуты) перекладывать старые сообщения в архив (и при сохранении мира).")
                .defineInRange("archive_interval_minutes", 10, 1, 240);
        b.pop();
        SPEC = b.build();
    }

    public static void bake() {
        chatToPda = CHAT_TO_PDA.get();
        chatRadius = CHAT_RADIUS.get();
        whisperRadius = WHISPER_RADIUS.get();
        shoutRadius = SHOUT_RADIUS.get();
        meRadius = ME_RADIUS.get();
        allowChatOutput = ALLOW_CHAT_OUTPUT.get();
        tellRequiresDialog = TELL_REQUIRES_DIALOG.get();
        tellByName = TELL_BY_NAME.get();
        adminLevel = ADMIN_LEVEL.get();
        maxLength = MAX_LENGTH.get();
        rateLimit = RATE_LIMIT.get();
        checkTicks = CHECK_TICKS.get();
        nameTicks = NAME_TICKS.get();
        archiveMinutes = ARCHIVE_MINUTES.get();
        pendingLimit = PENDING_LIMIT.get();
        passwordAttempts = PASSWORD_ATTEMPTS.get();
        lockoutSeconds = LOCKOUT_SECONDS.get();
        soundVolume = SOUND_VOLUME.get();
        style = STYLE.get();
        rpTime = RP_TIME.get();
        hackAttempts = HACK_ATTEMPTS.get();
        decryptorCharges = DECRYPTOR_CHARGES.get();
        decryptorCooldown = DECRYPTOR_COOLDOWN.get();
        cipherSeconds = CIPHER_SECONDS.get();
        exchangeRadius = EXCHANGE_RADIUS.get();
        avatarUploadSeconds = AVATAR_UPLOAD_SECONDS.get();
        realisticRetro = REALISTIC_RETRO.get();
        retroMemory = RETRO_MEMORY.get();
        retroSpeed = RETRO_SPEED.get();
        modernSpeed = MODERN_SPEED.get();
        transferRadius = TRANSFER_RADIUS.get();
        transferPeers = TRANSFER_PEERS.get();
        inviteMinutes = INVITE_MINUTES.get();
        glitchChance = GLITCH_CHANCE.get();
    }
}
