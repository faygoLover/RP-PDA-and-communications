package faygolover.rppda.client;

import faygolover.rppda.util.RpClock;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

/**
 * Время КПК на клиенте (находка 56): режим (RP или реальное) и таблица RP-часов с сервера ({@link RpClock}). Последняя
 * точка таблицы — «сейчас» по часам мира у клиента, так что новое показывается точно и между рассылками таблицы.
 */
public final class ClientTime {
    private ClientTime() {}

    private static boolean rp;
    private static long[] points = new long[0];
    /** Часы сервера минус часы клиента: моменты в сообщениях — по часам сервера. */
    private static long offset;

    static void receive(CompoundTag d) {
        rp = d.getBoolean("rp");
        points = d.getLongArray("p");
        offset = d.getLong("now") - System.currentTimeMillis();
    }

    static void reset() {
        rp = false;
        points = new long[0];
        offset = 0;
    }

    public static boolean rp() {
        return rp;
    }

    /** Момент на часах клиента → на часах сервера. */
    public static long toServer(long clientMs) {
        return clientMs + offset;
    }

    /** RP-время (тики суток мира) сейчас. */
    public static long nowTicks() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) return mc.level.getDayTime();
        return RpClock.at(points, toServer(System.currentTimeMillis()));
    }

    /** RP-время на момент {@code serverMs} (по часам сервера). */
    public static long ticksAt(long serverMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return RpClock.at(points, serverMs);
        long now = toServer(System.currentTimeMillis()), day = mc.level.getDayTime();
        int k = points.length;
        if (k == 0) return day;
        long r = points[k - 2], t = points[k - 1];
        if (serverMs > now) {
            // Будущее (срок приглашения): от «сейчас» с ходом последнего отрезка; короткий (только что разослали) —
            // с ходом прошлого отрезка таблицы.
            double rate = now - r >= 60_000 ? (double) (day - t) / (now - r)
                    : k >= 4 && r > points[k - 4] ? (double) (t - points[k - 3]) / (r - points[k - 4]) : 0;
            return day + Math.round(rate * (serverMs - now));
        }
        // После последней точки — по прямой до «сейчас».
        if (serverMs >= r && now > r) return t + Math.round((double) (day - t) * (serverMs - r) / (now - r));
        return RpClock.at(points, serverMs);
    }
}
