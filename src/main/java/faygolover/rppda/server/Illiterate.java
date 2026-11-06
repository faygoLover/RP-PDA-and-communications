package faygolover.rppda.server;

import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rpcore.api.RpIds;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * Особенность «Неграмотный» (RP Perks 2.0, 0.4.1): чужие сообщения и записки он видит каракулями — та же длина,
 * пробелы и знаки на месте, буквы — закорючки (каждый раз одинаковые: от текста). Свои читает. Имена — читаемые.
 */
public final class Illiterate {
    private Illiterate() {}

    private static final char[] SQUIGGLES = "~∿≈ʃʒɿʅꞁɷ∾".toCharArray();

    /** Кто сидит в этой сессии КПК (окно может открыть не владелец учётки). */
    @Nullable
    static ServerPlayer reader(PdaServer.Session s) {
        for (Map.Entry<UUID, PdaServer.Session> e : PdaServer.sessions.entrySet()) {
            if (e.getValue() != s) continue;
            var server = ServerLifecycleHooks.getCurrentServer();
            return server == null ? null : server.getPlayerList().getPlayer(e.getKey());
        }
        return null;
    }

    static boolean is(@Nullable ServerPlayer sp) {
        return sp != null && RpCoreAPI.hasTrait(sp, RpIds.ILLITERATE);
    }

    // Взлом и Технофоб (RP Perks 2.8.1): черт ещё нет константами в Core 0.12 — по имени.
    private static final net.minecraft.resources.ResourceLocation HACKER = new net.minecraft.resources.ResourceLocation("rpcore", "hacker"),
            TECHNOPHOBE = new net.minecraft.resources.ResourceLocation("rpcore", "technophobe");

    /** «Взлом»: лишняя попытка отмычки, больше времени на расшифровку. */
    static boolean hacker(ServerPlayer sp) {
        return RpCoreAPI.hasTrait(sp, HACKER);
    }

    /** «Технофоб»: не взламывает КПК, не шифрует и не расшифровывает заметки. */
    static boolean technophobe(ServerPlayer sp) {
        return RpCoreAPI.hasTrait(sp, TECHNOPHOBE);
    }

    static boolean is(PdaServer.Session s) {
        return !s.system && is(reader(s));
    }

    /** Текст каракулями. */
    public static String scribble(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder b = new StringBuilder(text.length());
        int h = text.hashCode();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                h = h * 31 + i;
                b.append(SQUIGGLES[Math.floorMod(h, SQUIGGLES.length)]);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
