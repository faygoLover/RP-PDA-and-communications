package faygolover.rppda.server;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/** Для игровых тестов: речь (шёпот) снаружи пакета. */
public final class ChatInterceptAccess {
    private ChatInterceptAccess() {}

    public static String garble(String text, double drop, RandomSource rnd) {
        return ChatIntercept.garble(text, drop, rnd);
    }

    /** Что слышно от шёпота на расстоянии {@code d} ("" — ничего). */
    public static String heardWhisper(Component name, String text, double d, RandomSource rnd) {
        var c = ChatIntercept.heard(ChatIntercept.Speech.WHISPER, name, text, d, rnd);
        return c == null ? "" : c.getString();
    }
}
