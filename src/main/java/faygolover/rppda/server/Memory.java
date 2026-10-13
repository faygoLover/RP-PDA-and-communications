package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Note;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Память ретро-КПК ({@code docs/20_retro_memory.md}). Включается опцией {@code realistic_retro}. Считаем грубо:
 * 1 знак = 1 байт, контакт — 16 байт, аватарка — 128 байт, заметки — название и текст, плюс история чата,
 * подгруженная сверх первой страницы (до выхода из чата). Обычные сообщения место не занимают. Объём — в самом КПК:
 * своя память ({@code retro_memory_bytes}) и платы в ячейках расширения. Админ может превышать лимит.
 */
public final class Memory {
    private Memory() {}

    public static final int CONTACT = 16, AVATAR = 128;

    /** Есть ли у учётки ограничение памяти: опция включена и её КПК — ретро. */
    public static boolean limited(Account a) {
        return PdaConfig.realisticRetro && a.kind != Account.Kind.SYSTEM && retro(a);
    }

    /** Вид КПК учётки — ретро (свой вид или вид по умолчанию с сервера). */
    public static boolean retro(Account a) {
        String st = a.look.style.isEmpty() ? PdaConfig.style : a.look.style;
        return st.equals("retro");
    }

    /** Скорость передачи файла этим КПК, байт/с. */
    static int speed(Account a) {
        return retro(a) ? PdaConfig.retroSpeed : PdaConfig.modernSpeed;
    }

    public static int bytes(String s) {
        return s.codePointCount(0, s.length());
    }

    public static int noteBytes(Note n) {
        return bytes(n.title) + bytes(n.text);
    }

    static int notesBytes(Account a) {
        int sum = 0;
        for (Note n : a.notes) sum += noteBytes(n);
        return sum;
    }

    /** Занято без подгруженной истории. */
    static int stored(Account a) {
        return a.contacts.size() * CONTACT + AVATAR + notesBytes(a);
    }

    /** Подгруженная история — у окна этой учётки, если оно открыто. */
    static int history(Account a, @Nullable PdaServer.Session s) {
        return s != null && !s.system && s.device == a.id ? s.historyBytes : 0;
    }

    static int used(Account a, @Nullable PdaServer.Session s) {
        return stored(a) + history(a, s);
    }

    /** Объём КПК: своя память и платы в ячейках. */
    static int capacity(@Nullable ItemStack device) {
        int c = PdaConfig.retroMemory;
        if (device != null) for (int kb : PdaItem.slots(device)) c += kb * 1024;
        return c;
    }

    static int capacity(ServerPlayer sp, Account a) {
        return capacity(Devices.stackOf(sp, a));
    }

    /** Сколько ещё влезет; {@code Integer.MAX_VALUE} — без ограничения (не ретро, опция выключена, админ). */
    static int free(ServerPlayer sp, Account a, @Nullable PdaServer.Session s) {
        if (!limited(a) || PdaServer.isAdmin(sp)) return Integer.MAX_VALUE;
        return capacity(sp, a) - used(a, s);
    }

    /**
     * Можно ли сохранить изменение размером было {@code before} → станет {@code after} байт. Уменьшать можно всегда
     * (даже когда занято больше лимита); нельзя — окно ошибки у игрока.
     */
    static boolean allow(ServerPlayer sp, Account a, @Nullable PdaServer.Session s, int before, int after) {
        if (after <= before) return true;
        int free = free(sp, a, s);
        if (free == Integer.MAX_VALUE || after - before <= free) return true;
        full(sp);
        return false;
    }

    /** Окно «Память переполнена» у игрока. */
    static void full(ServerPlayer sp) {
        Net.send(sp, "mem.full", new CompoundTag());
    }

    /** Цифры памяти поменялись (заметки, история, загрузка) — обновить их в открытом окне этой учётки. */
    static void push(ServerPlayer sp, Account a) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s == null || s.system || s.mode != PdaServer.Session.Mode.OPEN || s.device != a.id || !limited(a)) return;
        Net.send(sp, "mem", view(sp, a, s));
    }

    /** Для окна: занято, объём, из чего занято, ячейки; {@code admin} — лимит не действует. */
    static CompoundTag view(ServerPlayer sp, Account a, @Nullable PdaServer.Session s) {
        ItemStack dev = Devices.stackOf(sp, a);
        CompoundTag t = new CompoundTag();
        t.putInt("contacts", a.contacts.size() * CONTACT);
        t.putInt("avatar", AVATAR);
        t.putInt("notes", notesBytes(a));
        t.putInt("history", history(a, s));
        t.putInt("used", used(a, s));
        t.putInt("cap", capacity(dev));
        t.putBoolean("admin", PdaServer.isAdmin(sp));
        t.put("slots", new IntArrayTag(dev == null ? new int[PdaItem.SLOTS] : PdaItem.slots(dev)));
        t.putInt("glitch", PdaConfig.glitchChance);
        return t;
    }
}
