package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Note;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.DecryptorItem;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Зашифрованные заметки. Шифрует автор (1 заряд дешифратора, без мини-игры). Читают без расшифровки только владелец
 * учётки (своя заметка) и ГМ; остальные — расшифровка как «взлом» в Cyberpunk 2077: матрица 7×7, буфер 6, первый код
 * из верхней строки, дальше по очереди столбец → строка. Каждая последовательность открывает свою треть текста.
 * Матрицу, коды и текст закрытых частей знает только сервер.
 */
public final class Cipher {
    private Cipher() {}

    public static final int N = 7, BUFFER = 6;
    public static final String[] CODES = {"1C", "55", "BD", "E9", "7A", "FF"};

    static final Map<UUID, Game> games = new HashMap<>();

    static final class Game {
        int acc, note;
        String[] matrix = new String[N * N];
        final List<Integer> picks = new ArrayList<>();
        /** Последовательности: коды и номер части текста. */
        final List<String[]> seqs = new ArrayList<>();
        final List<Integer> parts = new ArrayList<>();
        long started;
        int charges;
    }

    // ---------------------------------------------------------------- кто читает

    /** Можно ли читать заметку без расшифровки. */
    public static boolean readable(ServerPlayer sp, Account me, Note n) {
        if (!n.cipher || n.fullyOpened()) return true;
        if (PdaServer.isGm(sp)) return true;
        return n.from == null && me.owner != null && me.owner.equals(sp.getUUID());
    }

    /** Заметка для окна: нечитаемая — замаскирована (закрытые части — случайные буквы и цифры). */
    static CompoundTag view(ServerPlayer sp, Account me, Note n) {
        CompoundTag t = n.save();
        if (n.from != null) t.putBoolean("ro", true);
        if (!readable(sp, me, n)) {
            t.putString("text", n.masked());
            t.putBoolean("locked", true);
        }
        if (n.cipher) t.put("bounds", new IntArrayTag(Note.bounds(n.text)));
        return t;
    }

    // ---------------------------------------------------------------- дешифратор

    /** Готовый дешифратор в инвентаре; null — нет (игроку сказано почему). */
    @Nullable
    static ItemStack decryptor(ServerPlayer sp) {
        Inventory inv = sp.getInventory();
        long now = System.currentTimeMillis();
        long soonest = Long.MAX_VALUE;
        List<ItemStack> all = new ArrayList<>(inv.items);
        all.addAll(inv.offhand);
        for (ItemStack s : all) {
            if (!(s.getItem() instanceof DecryptorItem)) continue;
            if (DecryptorItem.ready(s, now)) return s;
            soonest = Math.min(soonest, DecryptorItem.readyAt(s));
        }
        if (soonest == Long.MAX_VALUE) Actions.toast(sp, "rppda.err.no_decryptor", true);
        else Actions.toast(sp, "rppda.err.decryptor_cd", true, Long.toString(Math.max(1, (soonest - now + 59_999) / 60_000)));
        return null;
    }

    @Nullable
    private static Note note(Account me, int id) {
        for (Note n : me.notes) if (n.id == id) return n;
        return null;
    }

    // ---------------------------------------------------------------- шифрование

    static void encrypt(ServerPlayer sp, PdaStore store, Account me, int id) {
        Note n = note(me, id);
        if (n == null || n.cipher || n.from != null || n.text.isBlank()) return;
        if (!readable(sp, me, n)) return;
        ItemStack d = decryptor(sp);
        if (d == null) return;
        int left = DecryptorItem.use(d, System.currentTimeMillis());
        n.cipher = true;
        n.opened = 0;
        store.markAccounts();
        CompoundTag t = new CompoundTag();
        t.putInt("id", n.id);
        t.putInt("charges", left);
        t.putString("mask", n.masked());
        Net.send(sp, "notes.encrypting", t);
        Actions.sendNotes(sp, me);
    }

    // ---------------------------------------------------------------- расшифровка

    static void start(ServerPlayer sp, PdaStore store, Account me, int id) {
        Note n = note(me, id);
        if (n == null || readable(sp, me, n)) return;
        ItemStack d = decryptor(sp);
        if (d == null) return;
        Game g = generate(n, new Random());
        g.acc = me.id;
        g.note = n.id;
        g.charges = DecryptorItem.use(d, System.currentTimeMillis());
        games.put(sp.getUUID(), g);
        CompoundTag t = state(g);
        t.putBoolean("new", true);
        Net.send(sp, "cipher", t);
    }

    /** Матрица с заранее проложенным путём из {@link #BUFFER} ходов: все последовательности собираются за один проход. */
    static Game generate(Note n, Random rnd) {
        Game g = new Game();
        List<Integer> parts = new ArrayList<>();
        for (int p = 0; p < Note.PARTS; p++) if ((n.opened & (1 << p)) == 0) parts.add(p);
        int[] lens = lengths(parts.size(), rnd);
        while (true) {
            // Путь: строка 0 → столбец → строка …, клетки не повторяются.
            List<Integer> path = new ArrayList<>();
            boolean row = true;
            int line = 0;
            boolean ok = true;
            for (int k = 0; k < BUFFER && ok; k++) {
                List<Integer> free = new ArrayList<>();
                for (int j = 0; j < N; j++) {
                    int cell = row ? line * N + j : j * N + line;
                    if (!path.contains(cell)) free.add(cell);
                }
                if (free.isEmpty()) ok = false;
                else {
                    int cell = free.get(rnd.nextInt(free.size()));
                    path.add(cell);
                    line = row ? cell % N : cell / N;
                    row = !row;
                }
            }
            if (!ok) continue;
            String[] buf = new String[BUFFER];
            for (int k = 0; k < BUFFER; k++) buf[k] = CODES[rnd.nextInt(CODES.length)];
            g.seqs.clear();
            List<String> seen = new ArrayList<>();
            boolean distinct = true;
            for (int len : lens) {
                int at = rnd.nextInt(BUFFER - len + 1);
                String[] seq = java.util.Arrays.copyOfRange(buf, at, at + len);
                String key = String.join(" ", seq);
                for (String o : seen) if (o.contains(key) || key.contains(o)) distinct = false;
                seen.add(key);
                g.seqs.add(seq);
            }
            if (!distinct) continue;
            for (int i = 0; i < N * N; i++) g.matrix[i] = CODES[rnd.nextInt(CODES.length)];
            for (int k = 0; k < BUFFER; k++) g.matrix[path.get(k)] = buf[k];
            g.parts.clear();
            g.parts.addAll(parts);
            return g;
        }
    }

    /** Длины последовательностей: 2 (не больше одной), 3 (сколько угодно), 4 (не больше одной). */
    static int[] lengths(int count, Random rnd) {
        int[] out = new int[count];
        boolean two = false, four = false;
        for (int i = 0; i < count; i++) {
            int r = rnd.nextInt(4);
            int len = r == 0 && !two ? 2 : r == 1 && !four ? 4 : 3;
            if (len == 2) two = true;
            if (len == 4) four = true;
            out[i] = len;
        }
        return out;
    }

    static void handle(ServerPlayer sp, PdaStore store, Account me, String op, CompoundTag d) {
        Game g = games.get(sp.getUUID());
        if (g == null || g.acc != me.id) return;
        Note n = note(me, g.note);
        if (n == null) {
            games.remove(sp.getUUID());
            return;
        }
        long now = System.currentTimeMillis();
        boolean timeUp = g.started > 0 && now - g.started > PdaConfig.cipherSeconds * 1000L + 1500;
        switch (op) {
            case "cipher.pick" -> {
                if (timeUp) {
                    finish(sp, store, me, g, n);
                    return;
                }
                int cell = d.getInt("cell");
                if (cell < 0 || cell >= N * N || g.picks.contains(cell) || !allowed(g, cell)) return;
                if (g.started == 0) g.started = now;
                g.picks.add(cell);
                boolean opened = false;
                for (int k = 0; k < g.seqs.size(); k++) {
                    int part = g.parts.get(k);
                    if ((n.opened & (1 << part)) == 0 && done(g, g.seqs.get(k))) {
                        n.opened |= 1 << part;
                        opened = true;
                    }
                }
                if (opened) store.markAccounts();
                boolean all = true;
                for (int part : g.parts) if ((n.opened & (1 << part)) == 0) all = false;
                if (all) {
                    // Все ключи найдены: ходов больше нет, таймер стоит — окно покажет «Файл полностью расшифрован».
                    games.remove(sp.getUUID());
                    CompoundTag t = state(g);
                    t.putBoolean("complete", true);
                    Net.send(sp, "cipher", t);
                    Actions.sendNotes(sp, me);
                    return;
                }
                if (g.picks.size() >= BUFFER) {
                    finish(sp, store, me, g, n);
                    return;
                }
                Net.send(sp, "cipher", state(g));
                if (opened) Actions.sendNotes(sp, me);
            }
            case "cipher.timeout" -> {
                if (g.started > 0 && now - g.started >= PdaConfig.cipherSeconds * 1000L - 1500) finish(sp, store, me, g, n);
            }
            case "cipher.quit" -> finish(sp, store, me, g, n);
            default -> {}
        }
    }

    /** Ход по правилу: первый — верхняя строка, дальше по очереди столбец последнего кода, его строка… */
    static boolean allowed(Game g, int cell) {
        if (g.picks.isEmpty()) return cell / N == 0;
        int last = g.picks.get(g.picks.size() - 1);
        boolean column = g.picks.size() % 2 == 1;
        return column ? cell % N == last % N : cell / N == last / N;
    }

    static boolean done(Game g, String[] seq) {
        List<String> buf = new ArrayList<>();
        for (int c : g.picks) buf.add(g.matrix[c]);
        for (int i = 0; i + seq.length <= buf.size(); i++) {
            boolean m = true;
            for (int j = 0; j < seq.length; j++) if (!buf.get(i + j).equals(seq[j])) m = false;
            if (m) return true;
        }
        return false;
    }

    private static void finish(ServerPlayer sp, PdaStore store, Account me, Game g, Note n) {
        games.remove(sp.getUUID());
        CompoundTag t = state(g);
        t.putBoolean("end", true);
        Net.send(sp, "cipher", t);
        Actions.sendNotes(sp, me);
    }

    static void end(UUID player) {
        games.remove(player);
    }

    static CompoundTag state(Game g) {
        CompoundTag t = new CompoundTag();
        t.putInt("note", g.note);
        ListTag m = new ListTag();
        for (String c : g.matrix) m.add(StringTag.valueOf(c));
        t.put("matrix", m);
        t.put("picks", new IntArrayTag(g.picks.stream().mapToInt(Integer::intValue).toArray()));
        ListTag seqs = new ListTag();
        for (int k = 0; k < g.seqs.size(); k++) {
            CompoundTag s = new CompoundTag();
            s.putString("codes", String.join(" ", g.seqs.get(k)));
            s.putInt("part", g.parts.get(k));
            s.putBoolean("done", done(g, g.seqs.get(k)));
            seqs.add(s);
        }
        t.put("seqs", seqs);
        t.putInt("buffer", BUFFER);
        t.putInt("seconds", PdaConfig.cipherSeconds);
        t.putLong("elapsed", g.started == 0 ? -1 : System.currentTimeMillis() - g.started);
        t.putInt("charges", g.charges);
        return t;
    }
}
