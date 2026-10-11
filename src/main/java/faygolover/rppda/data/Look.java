package faygolover.rppda.data;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * Внешний вид КПК, который задаёт ГМ: вид окна (свой у этого КПК или как у всех), корпус, цвет экрана (ретро),
 * акцент (современный), название модели на корпусе. До регистрации лежит в предмете, после — в учётке.
 * Списки — только готовые варианты; красный корпус и янтарный экран — у системного КПК, их здесь нет.
 */
public final class Look {
    public static final List<String> STYLES = List.of("", "retro", "modern");
    /** Корпуса. Аддоны добавляют свои через {@link #registerBody} (на сервере и клиенте, при загрузке мода). */
    public static final List<String> BODIES = new java.util.concurrent.CopyOnWriteArrayList<>(List.of(
            "beige", "graphite", "olive", "navy", "white", "khaki", "orange",
            "camo", "wood", "duo", "worn", "field", "rainbow", "sunset", "neon", "memphis", "cassette", "wave", "disco"));

    public static void registerBody(String id) {
        if (!BODIES.contains(id)) BODIES.add(id);
    }

    public static final List<String> SCREENS = List.of("green", "white", "blue", "lcd", "synth");
    public static final List<String> ACCENTS = List.of("blue", "green", "orange", "violet", "neon");
    public static final int MODEL_MAX = 24;

    /** "" — вид по умолчанию из конфига сервера; иначе вид этого КПК («ретро»/«современный»), заданный при выдаче. */
    public String style = "";
    public String body = "beige";
    public String screen = "green";
    public String accent = "blue";
    /** Своё название модели (пусто — стандартное для корпуса). */
    public String model = "";

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (!style.isEmpty()) t.putString("style", style);
        t.putString("body", body);
        t.putString("screen", screen);
        t.putString("accent", accent);
        if (!model.isEmpty()) t.putString("model", model);
        return t;
    }

    public static Look load(CompoundTag t) {
        Look l = new Look();
        l.style = pick(STYLES, t.getString("style"), "");
        l.body = pick(BODIES, t.getString("body"), "beige");
        l.screen = pick(SCREENS, t.getString("screen"), "green");
        l.accent = pick(ACCENTS, t.getString("accent"), "blue");
        String m = t.getString("model").strip();
        l.model = m.length() > MODEL_MAX ? m.substring(0, MODEL_MAX) : m;
        return l;
    }

    public Look copy() {
        return load(save());
    }

    public boolean same(Look o) {
        return save().equals(o.save());
    }

    private static String pick(List<String> allowed, String v, String def) {
        return allowed.contains(v) ? v : def;
    }
}
