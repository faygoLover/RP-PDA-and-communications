package faygolover.rppda.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import faygolover.rppda.RpPda;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.LockpickItem;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Взлом КПК отмычкой — как терминал Fallout. Дамп памяти: мусор и спрятанные английские слова одной длины; клик по
 * слову или символу мусора — попытка («n/len» — сколько букв на своих местах); пара скобок {@code [] () <> {}} в одной
 * строке убирает пустышку или (раз на отмычку) возвращает попытку. Правильное слово знает только сервер. Попытки
 * хранит отмычка ({@link LockpickItem#left}): вышел — отмычка с оставшимися попытками у тебя; кончились во время
 * взлома — КПК мёртв до перевыпуска ГМом; успех тратит отмычку целиком.
 */
public final class Hack {
    private Hack() {}

    public static final int COLS = 2, ROWS = 18, PER_ROW = 16, SIZE = COLS * ROWS * PER_ROW;
    /** Мусор без точки: точка — след убранного слова или использованных скобок. */
    private static final String JUNK = "!@#$%^&*-+=/\\|;:'\",?_";
    private static final String OPEN = "[(<{", CLOSE = "])>}";
    /** Пар скобок на поле: не меньше и не больше. */
    private static final int MIN_PAIRS = 4, MAX_PAIRS = 7;

    static final Map<UUID, Game> games = new HashMap<>();
    private static Map<Integer, List<List<String>>> pools;

    static final class Game {
        int acc;
        int issue;
        InteractionHand pdaHand, pickHand;
        char[] grid;
        String answer;
        /** Слова: начало в сетке → слово. */
        final Map<Integer, String> words = new HashMap<>();
        final Set<Integer> removed = new HashSet<>();
        final Set<Integer> usedBrackets = new HashSet<>();
        final ListTag log = new ListTag();
        final Random rnd = new Random();
        /** Взлом закончен (успех или КПК сгорел) — ходы не принимаются, окно ждёт закрытия/«Войти». */
        boolean over;
    }

    // ---------------------------------------------------------------- начать

    /** ПКМ отмычкой: КПК — в другой руке. */
    public static void start(ServerPlayer sp, InteractionHand pickHand) {
        PdaStore store = PdaServer.store;
        if (store == null) return;
        if (faygolover.rpcore.api.RpCoreAPI.unconscious(sp)) return;
        InteractionHand other = pickHand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack pda = sp.getItemInHand(other);
        if (!Devices.isPda(pda)) {
            PdaServer.actionbar(sp, "rppda.hack.need_pda");
            return;
        }
        Account a = store.account(PdaItem.account(pda));
        switch (Devices.status(pda, store)) {
            case NEW -> {
                PdaServer.actionbar(sp, "rppda.hack.not_protected");
                return;
            }
            case OK -> {
                PdaServer.actionbar(sp, a != null && a.hasPassword() ? "rppda.hack.already_open" : "rppda.hack.not_protected");
                return;
            }
            case DISABLED, DELETED, BURNED -> {
                PdaServer.actionbar(sp, "rppda.hack.dead");
                return;
            }
            case LOCKED -> {}
        }
        if (a == null || !(sp.getItemInHand(pickHand).getItem() instanceof LockpickItem)) return;
        if (Illiterate.technophobe(sp)) {
            PdaServer.actionbar(sp, "rppda.hack.technophobe");
            return;
        }
        // «Взлом»: у новой (ещё не тронутой) отмычки попыток больше.
        ItemStack pick0 = sp.getItemInHand(pickHand);
        if (Illiterate.hacker(sp) && !LockpickItem.started(pick0)) LockpickItem.setLeft(pick0, PdaConfig.hackAttempts + PdaConfig.hackerAttempts);

        Game g = generate(a, new Random());
        g.acc = a.id;
        g.issue = a.issue;
        g.pdaHand = other;
        g.pickHand = pickHand;
        games.put(sp.getUUID(), g);
        PdaServer.Session s = new PdaServer.Session();
        s.mode = PdaServer.Session.Mode.HACK;
        s.device = a.id;
        s.hand = other;
        PdaServer.sessions.put(sp.getUUID(), s);
        store.gmLog(sp.getGameProfile().getName(), "hack_try", Actions.label(a));
        CompoundTag t = state(g, sp);
        t.putString("style", PdaConfig.style);
        t.put("look", a.look.save());
        t.putInt("id", a.id);
        t.putString("name", a.name);
        Net.send(sp, "hack", t);
    }

    /**
     * ПКМ КПК, а во второй руке отмычка — это взлом, а не открытие (иначе отмычка в левой руке не срабатывает:
     * основная рука с КПК обрабатывается первой). Только для запертого паролем КПК.
     */
    public static boolean wantsHack(ServerPlayer sp, InteractionHand pdaHand) {
        InteractionHand other = pdaHand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (!(sp.getItemInHand(other).getItem() instanceof LockpickItem) || PdaServer.store == null) return false;
        return Devices.status(sp.getItemInHand(pdaHand), PdaServer.store) == Devices.Status.LOCKED;
    }

    /** Длина слов — по длине пароля: короткий пароль — короткие слова (не короче 6). */
    static int wordLength(Account a) {
        int p = a.pwLen <= 0 ? 7 : a.pwLen;
        if (p <= 6) return 6;
        if (p <= 8) return 7;
        if (p <= 10) return 8;
        if (p <= 12) return 9;
        return 10;
    }

    static Game generate(Account a, Random rnd) {
        Game g = new Game();
        int len = wordLength(a);
        List<List<String>> ps = pools().get(len);
        List<String> pool = new ArrayList<>(ps.get(rnd.nextInt(ps.size())));
        Collections.shuffle(pool, rnd);
        int count = Math.min(pool.size(), len <= 7 ? 14 : 12);
        List<String> chosen = pool.subList(0, count);
        g.answer = chosen.get(rnd.nextInt(chosen.size()));
        g.grid = new char[SIZE];
        for (int i = 0; i < SIZE; i++) g.grid[i] = JUNK.charAt(rnd.nextInt(JUNK.length()));
        // Слова — равномерно по дампу, между ними мусор (слово может перейти на следующую строку).
        int slot = SIZE / count;
        for (int k = 0; k < count; k++) {
            int from = k * slot + 1, room = slot - len - 2;
            int at = from + (room > 0 ? rnd.nextInt(room) : 0);
            String w = chosen.get(k);
            for (int j = 0; j < len; j++) g.grid[at + j] = w.charAt(j);
            g.words.put(at, w);
        }
        // Пары скобок в строках без букв между ними: не меньше MIN_PAIRS.
        int pairs = 0;
        for (int tries = 0; tries < 2000 && (pairs < MIN_PAIRS || pairs < MAX_PAIRS && tries < 300); tries++) {
            int row = rnd.nextInt(COLS * ROWS);
            int open = row * PER_ROW + rnd.nextInt(PER_ROW - 2);
            int close = open + 2 + rnd.nextInt(4);
            if (close >= (row + 1) * PER_ROW) continue;
            boolean clean = true;
            for (int i = open; i <= close; i++) if (Character.isLetter(g.grid[i]) || OPEN.indexOf(g.grid[i]) >= 0 || CLOSE.indexOf(g.grid[i]) >= 0) clean = false;
            if (!clean) continue;
            int type = rnd.nextInt(OPEN.length());
            g.grid[open] = OPEN.charAt(type);
            g.grid[close] = CLOSE.charAt(type);
            pairs++;
        }
        return g;
    }

    static Map<Integer, List<List<String>>> pools() {
        if (pools != null) return pools;
        Map<Integer, List<List<String>>> out = new HashMap<>();
        try (InputStream in = Hack.class.getResourceAsStream("/data/rppda/pda/hack_words.json")) {
            JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String k : o.keySet()) {
                List<List<String>> ps = new ArrayList<>();
                for (JsonElement p : o.getAsJsonArray(k)) {
                    List<String> words = new ArrayList<>();
                    for (JsonElement w : (JsonArray) p) words.add(w.getAsString());
                    ps.add(words);
                }
                out.put(Integer.parseInt(k), ps);
            }
        } catch (Exception e) {
            RpPda.LOGGER.error("RP PDA: не прочитать слова для взлома", e);
            out.put(7, List.of(List.of("ACCOUNT", "ADDRESS", "CONTROL", "PROGRAM", "PROCESS", "PROTECT", "CONNECT", "CONTEXT")));
        }
        for (int len = 5; len <= 10; len++) out.putIfAbsent(len, out.values().iterator().next());
        pools = out;
        return out;
    }

    // ---------------------------------------------------------------- ходы

    static void handle(ServerPlayer sp, String op, CompoundTag d) {
        PdaStore store = PdaServer.store;
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        Game g = games.get(sp.getUUID());
        if (store == null || s == null || s.mode != PdaServer.Session.Mode.HACK || g == null) return;
        Account a = store.account(g.acc);
        if (a == null || !stillHolding(sp, g)) {
            end(sp);
            PdaServer.close(sp);
            return;
        }
        switch (op) {
            case "hack.pick" -> pick(sp, store, g, a, d.getInt("i"));
            case "hack.enter" -> {
                if (a.locked) return;
                games.remove(sp.getUUID());
                PdaServer.open(sp, a);
                PdaServer.scan(sp);
            }
            default -> {}
        }
    }

    /** КПК всё ещё в своей руке; отмычка — в своей (пока взлом идёт). */
    static boolean stillHolding(ServerPlayer sp, Game g) {
        ItemStack pda = sp.getItemInHand(g.pdaHand);
        if (!Devices.isPda(pda) || PdaItem.account(pda) != g.acc || PdaItem.issue(pda) != g.issue) return false;
        return g.over || sp.getItemInHand(g.pickHand).getItem() instanceof LockpickItem;
    }

    /** Окно взлома закрыто или КПК ушёл из руки: отмычка остаётся с теми попытками, что в ней. */
    static void end(@Nullable ServerPlayer sp) {
        if (sp != null) games.remove(sp.getUUID());
    }

    private static void pick(ServerPlayer sp, PdaStore store, Game g, Account a, int i) {
        if (g.over || i < 0 || i >= SIZE || !a.locked || a.burned) return;
        ItemStack pick = sp.getItemInHand(g.pickHand);
        Integer word = wordAt(g, i);
        String tried;
        if (word != null) {
            if (g.removed.contains(word)) return;
            tried = g.words.get(word);
        } else {
            int close = bracketPair(g.grid, i);
            if (close >= 0 && !g.usedBrackets.contains(i)) {
                bracket(sp, store, g, a, pick, i, close);
                return;
            }
            if (g.grid[i] == '.') return;
            // Символ мусора — тоже попытка (0 совпадений).
            tried = String.valueOf(g.grid[i]);
        }
        line(g, "w", tried);
        if (tried.equals(g.answer)) {
            line(g, "granted", "");
            g.over = true;
            a.locked = false;
            a.hackNotice = 2;
            a.hackTime = System.currentTimeMillis();
            store.markAccounts();
            PdaServer.holders.put(a.id, sp.getUUID());
            store.gmLog(sp.getGameProfile().getName(), "hack_ok", Actions.label(a));
            // Успех тратит отмычку целиком.
            pick.shrink(1);
            CompoundTag t = state(g, sp);
            t.putBoolean("ok", true);
            Net.send(sp, "hack.state", t);
            return;
        }
        line(g, "deny", "");
        line(g, "like", likeness(tried, g.answer) + "/" + g.answer.length());
        if (a.hackNotice < 1) {
            a.hackNotice = 1;
            a.hackTime = System.currentTimeMillis();
            store.markAccounts();
        }
        int left = LockpickItem.left(pick) - 1;
        if (left <= 0) {
            // Попытки отмычки кончились во время взлома — КПК сгорает, отмычка тоже.
            pick.shrink(1);
            burn(sp, store, a);
            g.over = true;
            CompoundTag t = state(g, sp);
            t.putInt("left", 0);
            t.putBoolean("burned", true);
            Net.send(sp, "hack.state", t);
            return;
        }
        LockpickItem.setLeft(pick, left);
        Net.send(sp, "hack.state", state(g, sp));
    }

    /** Пара скобок: убрать пустышку или (раз на отмычку, если попытки тратились) вернуть попытку. */
    private static void bracket(ServerPlayer sp, PdaStore store, Game g, Account a, ItemStack pick, int open, int close) {
        g.usedBrackets.add(open);
        line(g, "w", new String(g.grid, open, close - open + 1));
        g.grid[open] = '.';
        g.grid[close] = '.';
        List<Integer> duds = new ArrayList<>();
        for (var e : g.words.entrySet()) if (!e.getValue().equals(g.answer) && !g.removed.contains(e.getKey())) duds.add(e.getKey());
        boolean canRestore = !LockpickItem.restored(pick) && LockpickItem.left(pick) < total(sp);
        if (canRestore && (duds.isEmpty() || g.rnd.nextInt(4) == 0)) {
            LockpickItem.setRestored(pick);
            LockpickItem.setLeft(pick, LockpickItem.left(pick) + 1);
            line(g, "restored", "");
        } else if (!duds.isEmpty()) {
            int k = duds.get(g.rnd.nextInt(duds.size()));
            g.removed.add(k);
            String w = g.words.get(k);
            for (int j = 0; j < w.length(); j++) g.grid[k + j] = '.';
            line(g, "dud", "");
        } else line(g, "nothing", "");
        Net.send(sp, "hack.state", state(g, sp));
    }

    /** Попытки кончились: эта выдача КПК мертва до перевыпуска ГМом. */
    static void burn(ServerPlayer sp, PdaStore store, Account a) {
        a.burned = true;
        a.locked = true;
        store.markAccounts();
        PdaServer.holders.remove(a.id);
        store.gmLog(sp.getGameProfile().getName(), "hack_fail", Actions.label(a));
    }

    @Nullable
    private static Integer wordAt(Game g, int i) {
        for (var e : g.words.entrySet()) if (i >= e.getKey() && i < e.getKey() + e.getValue().length()) return e.getKey();
        return null;
    }

    /** Закрывающая скобка той же пары в той же строке, без букв между ними; −1 — пары нет. Так же считает окно. */
    public static int bracketPair(char[] grid, int i) {
        int type = OPEN.indexOf(grid[i]);
        if (type < 0) return -1;
        int rowEnd = (i / PER_ROW + 1) * PER_ROW;
        for (int j = i + 1; j < rowEnd; j++) {
            if (Character.isLetter(grid[j])) return -1;
            if (grid[j] == CLOSE.charAt(type)) return j;
        }
        return -1;
    }

    static int likeness(String a, String b) {
        int n = 0;
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) if (a.charAt(i) == b.charAt(i)) n++;
        return n;
    }

    private static void line(Game g, String kind, String text) {
        CompoundTag t = new CompoundTag();
        t.putString("k", kind);
        if (!text.isEmpty()) t.putString("x", text);
        g.log.add(t);
        while (g.log.size() > 40) g.log.remove(0);
    }

    /** Всего попыток у отмычки в руках этого игрока («Взлом» — больше). */
    private static int total(ServerPlayer sp) {
        return PdaConfig.hackAttempts + (Illiterate.hacker(sp) ? PdaConfig.hackerAttempts : 0);
    }

    static CompoundTag state(Game g, ServerPlayer sp) {
        CompoundTag t = new CompoundTag();
        t.putString("grid", new String(g.grid));
        ItemStack pick = sp.getItemInHand(g.pickHand);
        t.putInt("left", pick.getItem() instanceof LockpickItem ? LockpickItem.left(pick) : 0);
        t.putInt("total", Math.max(total(sp), t.getInt("left")));
        t.putInt("len", g.answer.length());
        t.put("log", g.log.copy());
        return t;
    }
}
