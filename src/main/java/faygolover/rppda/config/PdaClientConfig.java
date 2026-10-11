package faygolover.rppda.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Клиентский конфиг ({@code config/rppda-client.toml}): то, что каждый игрок решает сам. */
public final class PdaClientConfig {
    private PdaClientConfig() {}

    public enum Style { RETRO, MODERN }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.EnumValue<Style> STYLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        STYLE = b.comment("Вид КПК: RETRO — 80-е (корпус с клавишами, люминофор), MODERN — современный.",
                        "Переключается и в профиле КПК. Сервер может задать вид всем (pda_style в rppda-server.toml).",
                        "PDA look: RETRO (80s) or MODERN. The server may force one.")
                .defineEnum("style", Style.RETRO);
        SPEC = b.build();
    }

    public static boolean retro() {
        try {
            return STYLE.get() == Style.RETRO;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static void setRetro(boolean retro) {
        try {
            STYLE.set(retro ? Style.RETRO : Style.MODERN);
            STYLE.save();
        } catch (IllegalStateException ignored) {
            // Конфиг ещё не загружен — выбор не сохраняется.
        }
    }
}
