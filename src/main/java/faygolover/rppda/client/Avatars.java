package faygolover.rppda.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Вид аватарок по ID учёток (§7.6): клиент спрашивает сервер, помнит минуту. Пока ответа нет — рисуется как раньше
 * (лицо, если игрок в сети, иначе буква на цвете по ID).
 */
public final class Avatars {
    private Avatars() {}

    /** Вид: 0 — лицо (без сети — буква), 1 — буква на цвете; цвет — номер в {@link #COLORS} или −1 (по ID). */
    public record Style(int mode, int color, String mark) {
        static final Style DEFAULT = new Style(0, -1, "");

        static Style of(CompoundTag t) {
            return new Style(t.getInt("av"), t.contains("avColor") ? t.getInt("avColor") : -1, t.getString("avMark"));
        }
    }

    /** Палитра «буквы на цвете» — 12 цветов, порядок как в профиле. */
    public static final int[] COLORS = {
            0xFFB03A3A, 0xFFC4682B, 0xFFB8962E, 0xFF7E9A35, 0xFF3F8E4E, 0xFF2E8C83,
            0xFF2F7BAE, 0xFF3D55A8, 0xFF6A48A6, 0xFF9C4192, 0xFF6E6259, 0xFF3A3F47
    };

    private static final long LIFE_MS = 60_000L;

    private record Entry(Style style, long time) {}

    private static final Map<Integer, Entry> cache = new HashMap<>();
    private static final Set<Integer> asked = new LinkedHashSet<>();
    private static final Set<Integer> want = new LinkedHashSet<>();

    /** Вид учётки; нет или устарел — попросить у сервера (уйдёт в {@link #flush}). */
    public static Style get(int id) {
        if (id == 0) return Style.DEFAULT;
        Entry e = cache.get(id);
        if ((e == null || System.currentTimeMillis() - e.time > LIFE_MS) && !asked.contains(id)) want.add(id);
        return e == null ? Style.DEFAULT : e.style;
    }

    /** Вид, пришедший вместе с учёткой (своя — в состоянии окна, контакты, люди рядом). */
    public static void put(CompoundTag account) {
        if (!account.contains("av")) return;
        int id = account.getInt("id");
        cache.put(id, new Entry(Style.of(account), System.currentTimeMillis()));
        want.remove(id);
    }

    /** Раз в тик окна: собранные ID — серверу одним пакетом. */
    static void flush() {
        if (want.isEmpty()) return;
        int[] ids = want.stream().limit(64).mapToInt(Integer::intValue).toArray();
        for (int id : ids) {
            want.remove(id);
            asked.add(id);
        }
        CompoundTag t = new CompoundTag();
        t.put("ids", new IntArrayTag(ids));
        ClientPda.send("avatar.get", t);
    }

    static void receive(CompoundTag d) {
        long now = System.currentTimeMillis();
        for (Tag t : d.getList("list", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            cache.put(c.getInt("id"), new Entry(Style.of(c), now));
            asked.remove(c.getInt("id"));
        }
    }

    /** Окно закрыли или вышли с сервера: недоспрошенное — забыть (ответа может не быть). */
    static void reset(boolean all) {
        asked.clear();
        want.clear();
        if (all) cache.clear();
    }

    /** Как сервер ({@code Actions.avatarMark}): 1–2 видимых знака без кодов форматирования. */
    public static String cleanMark(String raw) {
        StringBuilder b = new StringBuilder();
        raw.strip().codePoints().filter(cp -> cp != '\u00a7' && !Character.isISOControl(cp) && !Character.isWhitespace(cp)
                && Character.getType(cp) != Character.FORMAT).limit(2).forEach(b::appendCodePoint);
        return b.toString();
    }

    @Nullable
    static Integer color(Style s) {
        return s.color >= 0 && s.color < COLORS.length ? COLORS[s.color] : null;
    }
}
