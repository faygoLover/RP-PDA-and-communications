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
        b.pop();
        b.push("devices");
        CHECK_TICKS = b.comment("Как часто (тики) искать активный КПК игрока и проверять, не ушёл ли разблокированный КПК с паролем.")
                .defineInRange("check_interval_ticks", 40, 10, 400);
        NAME_TICKS = b.comment("Как часто (тики) перечитывать имя игрока (fakename) для его учёток.")
                .defineInRange("name_refresh_ticks", 100, 20, 2400);
        PASSWORD_ATTEMPTS = b.comment("Неверных паролей подряд до паузы.").defineInRange("password_attempts", 5, 1, 100);
        LOCKOUT_SECONDS = b.comment("Пауза после неверных паролей, секунд.").defineInRange("lockout_seconds", 60, 0, 3600);
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
    }
}
