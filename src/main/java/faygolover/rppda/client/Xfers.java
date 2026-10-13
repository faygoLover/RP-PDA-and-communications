package faygolover.rppda.client;

import faygolover.rppda.mixin.ChatComponentAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Передачи файлов этого игрока ({@code docs/20_retro_memory.md} §4): сервер раз в секунду присылает список. Строки
 * загрузки — под приглашением в чате КПК и внизу чата игры (всегда, даже если КПК в чат игры не выводится; строка
 * убирается и добавляется заново последней). Итог («ГОТОВО» / «ОБРЫВ») висит несколько секунд. Пока что-то идёт —
 * тихий звук модема.
 */
public final class Xfers {
    private Xfers() {}

    /** Сколько висит итог передачи. */
    private static final long END_MS = 4000;
    /** Без вестей от сервера дольше — передача «зависла» (палочка не крутится). */
    private static final long STALE_MS = 2500;
    private static final String SPIN = "|/-\\";

    public static final class Entry {
        CompoundTag e;
        long seen, endedAt;
        int spin;

        boolean running() {
            return e.getInt("state") == 0;
        }

        public CompoundTag tag() {
            return e;
        }
    }

    private static final Map<Integer, Entry> entries = new LinkedHashMap<>();
    /** Растёт, когда меняется набор передач (окну КПК — пересчитать высоту сообщений). */
    static int shape;
    @Nullable
    private static Sfx.Loop modem;

    static void receive(CompoundTag d) {
        long now = System.currentTimeMillis();
        List<Integer> got = new ArrayList<>();
        for (Tag t : d.getList("list", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            int id = e.getInt("id");
            got.add(id);
            Entry x = entries.get(id);
            if (x == null) {
                x = new Entry();
                entries.put(id, x);
                shape++;
            }
            boolean moved = x.e == null || x.e.getInt("done") != e.getInt("done");
            x.e = e;
            x.seen = now;
            if (x.running() && moved) x.spin++;
            if (!x.running() && x.endedAt == 0) x.endedAt = now;
        }
        // Идущие, о которых сервер больше не пишет, — закончились (итог уже показан).
        if (entries.entrySet().removeIf(en -> !got.contains(en.getKey()) && en.getValue().running())) shape++;
        refreshLines();
        sound();
    }

    /** Раз в тик клиента: итоги, отвисевшие своё, — убрать. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (entries.entrySet().removeIf(en -> en.getValue().endedAt > 0 && now - en.getValue().endedAt > END_MS)) {
            shape++;
            refreshLines();
        }
        sound();
    }

    static void reset() {
        entries.clear();
        shape++;
        if (modem != null) modem.fadeOut();
        modem = null;
    }

    /** Передачи по приглашению {@code chat}/{@code seq} (для строк под ним в чате КПК). */
    public static List<Entry> of(int chat, int seq) {
        List<Entry> out = new ArrayList<>();
        for (Entry x : entries.values()) if (x.e.getInt("chat") == chat && x.e.getInt("seq") == seq) out.add(x);
        return out;
    }

    /** Идущая загрузка этой заметки (своя, «качаю»), иначе null. */
    @Nullable
    public static Entry download(int note) {
        for (Entry x : entries.values()) if (x.e.getBoolean("down") && x.e.getInt("note") == note && x.running()) return x;
        return null;
    }

    private static void sound() {
        boolean busy = false;
        for (Entry x : entries.values()) if (x.running()) busy = true;
        if (busy && modem == null) modem = Sfx.Loop.start(Sfx.MODEM, 0.12f);
        if (!busy && modem != null) {
            modem.fadeOut();
            modem = null;
        }
    }

    // ---------------------------------------------------------------- вид строки

    /**
     * «/ [#####_____] 50%  10 Б/с», итог — «[##########] ГОТОВО» или «[#####XXXXX] ОБРЫВ: вне зоны». В окне КПК
     * палочка крутится сама ({@code live}), пока сервер пишет; в чате игры — шаг на каждое обновление.
     */
    public static String bar(Entry x, boolean live) {
        CompoundTag e = x.e;
        int total = Math.max(1, e.getInt("total")), done = Math.min(total, e.getInt("done"));
        int cells = 10, fill = e.getInt("total") == 0 ? cells : done * cells / total;
        int state = e.getInt("state");
        StringBuilder b = new StringBuilder();
        int spin = live && !stale(x) ? (int) (System.currentTimeMillis() / 200) : x.spin;
        if (state == 0) b.append(SPIN.charAt(Math.floorMod(spin, 4))).append(' ');
        b.append('[');
        for (int i = 0; i < cells; i++) b.append(i < fill ? '#' : state == 2 ? 'X' : '_');
        b.append("] ");
        if (state == 1) b.append(Component.translatable("rppda.xfer.done").getString());
        else if (state == 2) b.append(Component.translatable("rppda.xfer.failed",
                Component.translatable("rppda.xfer.why." + e.getString("reason")).getString()).getString());
        else {
            b.append(e.getInt("total") == 0 ? 100 : done * 100 / total).append("%  ");
            b.append(Component.translatable("rppda.xfer.rate", e.getInt("rate")).getString());
        }
        return b.toString();
    }

    /** Строка в чате игры: «⇩ / [##__] 50% · 10 Б/с · Петя: «Схема»» или «⇧ … → Вася». */
    private static Component line(Entry x) {
        CompoundTag e = x.e;
        boolean down = e.getBoolean("down");
        MutableComponent c = Component.literal(down ? "⇩ " : "⇧ ").withStyle(ChatFormatting.AQUA);
        int state = e.getInt("state");
        ChatFormatting col = state == 1 ? ChatFormatting.GREEN : state == 2 ? ChatFormatting.RED : ChatFormatting.WHITE;
        c.append(Component.literal(bar(x, false)).withStyle(col));
        if (down) c.append(Component.literal(" · " + e.getString("who") + ": «" + e.getString("title") + "»").withStyle(ChatFormatting.GRAY));
        else c.append(Component.literal(" → " + e.getString("who") + " · «" + e.getString("title") + "»").withStyle(ChatFormatting.GRAY));
        return c;
    }

    /** Убрать свои строки из чата игры и добавить заново последними (пока передача идёт — не гаснут). */
    private static void refreshLines() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponentAccess chat = (ChatComponentAccess) mc.gui.getChat();
        chat.rppda$all().removeIf(m -> m.tag() == ClientPda.XFER_TAG);
        chat.rppda$trimmed().removeIf(l -> l.tag() == ClientPda.XFER_TAG);
        int ticks = mc.gui.getGuiTicks();
        for (Entry x : entries.values()) chat.rppda$add(line(x), null, ticks, ClientPda.XFER_TAG, false);
    }

    /** Висит ли передача без вестей от сервера (палочка стоит). */
    public static boolean stale(Entry x) {
        return x.running() && System.currentTimeMillis() - x.seen > STALE_MS;
    }
}
