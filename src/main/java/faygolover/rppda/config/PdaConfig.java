package faygolover.rppda.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Серверный конфиг ({@code <мир>/serverconfig/rppda-server.toml}). Значения читаются через {@link #bake()}. */
public final class PdaConfig {
    private PdaConfig() {}

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue HIDE_WITHOUT_PDA;
    private static final ForgeConfigSpec.BooleanValue FORCE_PDA_ONLY;
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

    public static boolean hideWithoutPda = true;
    public static boolean forcePdaOnly = false;
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

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("chat");
        HIDE_WITHOUT_PDA = b.comment("Игрок без КПК не видит сообщений игроков (системные и tellraw видит). Его сообщения в чат видят только админы.",
                        "Players without a PDA don't see player messages (system and tellraw still shown).")
                .define("hide_player_chat_without_pda", true);
        FORCE_PDA_ONLY = b.comment("Сообщения КПК только в КПК, не в чате — у всех, кроме админов (они видят общий чат в чате).",
                        "PDA messages only inside the PDA for everyone except admins.")
                .define("force_pda_only", false);
        ADMIN_LEVEL = b.comment("Уровень оператора, с которого игрок — админ: системный КПК, /tell без КПК, видит чат игроков без КПК.")
                .defineInRange("admin_permission_level", 2, 1, 4);
        MAX_LENGTH = b.comment("Наибольшая длина сообщения, символов.").defineInRange("max_message_length", 256, 16, 2000);
        RATE_LIMIT = b.comment("Не больше стольких сообщений за 10 секунд от одной учётки.").defineInRange("messages_per_10s", 8, 1, 100);
        PENDING_LIMIT = b.comment("Сколько сообщений админам хранить за игроком без учётки (старые удаляются).")
                .defineInRange("pending_limit", 100, 0, 1000);
        SOUND_VOLUME = b.comment("Громкость звука уведомления (его слышат игроки рядом). 0 — без звука.")
                .defineInRange("notify_volume", 0.7, 0.0, 4.0);
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
        hideWithoutPda = HIDE_WITHOUT_PDA.get();
        forcePdaOnly = FORCE_PDA_ONLY.get();
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
    }
}
