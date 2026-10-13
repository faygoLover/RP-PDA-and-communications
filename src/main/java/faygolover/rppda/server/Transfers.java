package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.Note;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Передача заметок при реалистичных ретро-КПК ({@code docs/20_retro_memory.md} §4). Отправитель (сид) выкладывает в
 * чат приглашение; оно живёт {@code invite_minutes} или пока сид его не отменит. Кто подтвердил (пир) — качает
 * медленно, по самому медленному звену; скорость сида делится между пирами (не больше {@code transfer_max_peers}).
 * Всё время пиры — не дальше {@code transfer_radius} от сида, в том же измерении, оба КПК — при них и не заблокированы;
 * иначе обрыв. Кончилось место у пира — обрыв. Недокачанная заметка остаётся у пира битой (место занимает, не
 * открывается); название приходит сразу целиком. Докачать нельзя. Один файл за раз: нельзя качать два, отправлять
 * два, качать и отправлять сразу. Всё в памяти сервера: после перезапуска приглашения кончаются.
 */
public final class Transfers {
    private Transfers() {}

    /** Приглашение в чате (сообщение {@code chat}/{@code seq}). */
    static final class Invite {
        int chat, seq, seed, note;
        UUID seedPlayer;
        long noteUpdated, expires;
        String title = "";
        int size;
        boolean cipher, canceled, closedSent;
        final List<Xfer> peers = new ArrayList<>();

        boolean open(long now) {
            return !canceled && now < expires;
        }

        int running() {
            int n = 0;
            for (Xfer x : peers) if (x.state == RUN) n++;
            return n;
        }
    }

    /** Передача одному пиру. */
    static final class Xfer {
        final int id;
        final Invite inv;
        final int peer, peerNote, total;
        final UUID peerPlayer;
        int done, rate, state;
        String reason = "";

        Xfer(int id, Invite inv, int peer, UUID peerPlayer, int peerNote, int total) {
            this.id = id;
            this.inv = inv;
            this.peer = peer;
            this.peerPlayer = peerPlayer;
            this.peerNote = peerNote;
            this.total = total;
        }
    }

    static final int RUN = 0, DONE = 1, FAIL = 2;

    private static final Map<Long, Invite> invites = new HashMap<>();
    /** Кому в прошлый раз ушли строки загрузки (им — пустой список, когда передач не осталось). */
    private static final Set<UUID> lastSent = new HashSet<>();
    private static int nextId = 1;

    static void reset() {
        invites.clear();
        lastSent.clear();
    }

    private static long key(int chat, int seq) {
        return ((long) chat << 32) | (seq & 0xFFFFFFFFL);
    }

    /** Приглашение ещё можно принять (для окна: показать «скачать» или «истекло»). */
    static boolean isOpen(int chat, int seq) {
        Invite inv = invites.get(key(chat, seq));
        return inv != null && inv.open(System.currentTimeMillis());
    }

    /** Сколько миллисекунд приглашению осталось (0 — закрыто). */
    static long left(int chat, int seq) {
        Invite inv = invites.get(key(chat, seq));
        long now = System.currentTimeMillis();
        return inv == null || !inv.open(now) ? 0 : inv.expires - now;
    }

    /** Отправляет файл: есть открытое приглашение или идёт передача от него. */
    static boolean seeding(int acc) {
        long now = System.currentTimeMillis();
        for (Invite inv : invites.values()) if (inv.seed == acc && (inv.open(now) || inv.running() > 0)) return true;
        return false;
    }

    static boolean downloading(int acc) {
        for (Invite inv : invites.values()) for (Xfer x : inv.peers) if (x.peer == acc && x.state == RUN) return true;
        return false;
    }

    // ---------------------------------------------------------------- приглашение

    /** «Отправить» заметку в чат: приглашение на загрузку. */
    static void offer(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, int noteId, int chatId) {
        Note n = null;
        for (Note o : me.notes) if (o.id == noteId) n = o;
        Chat c = store.chat(chatId);
        if (n == null || c == null || n.from != null || n.partial || !Cipher.readable(sp, me, n)) return;
        if (seeding(me.id)) {
            Actions.toast(sp, "rppda.err.xfer_sending", true);
            return;
        }
        if (downloading(me.id)) {
            Actions.toast(sp, "rppda.err.xfer_busy", true);
            return;
        }
        String err = Messaging.check(me, c, "file", sp);
        if (err != null) {
            Actions.toast(sp, err, true);
            return;
        }
        int size = Memory.noteBytes(n);
        boolean cipher = n.cipher && !n.fullyOpened();
        Message m = Messaging.post(c, me, n.id + "\n" + size + "\n" + n.title, 0, Message.FILE | (cipher ? Message.CIPHER : 0), null, null);
        Invite inv = new Invite();
        inv.chat = c.id;
        inv.seq = m.seq;
        inv.seed = me.id;
        inv.seedPlayer = sp.getUUID();
        inv.note = n.id;
        inv.noteUpdated = n.updated;
        inv.title = n.title;
        inv.size = size;
        inv.cipher = cipher;
        inv.expires = System.currentTimeMillis() + PdaConfig.inviteMinutes * 60_000L;
        invites.put(key(c.id, m.seq), inv);
        Actions.toast(sp, "rppda.toast.xfer_offered", false);
    }

    /** Сид отменяет своё приглашение: новые не присоединятся, идущие передачи обрываются. */
    static void cancel(ServerPlayer sp, Account me, int chat, int seq) {
        Invite inv = invites.get(key(chat, seq));
        if (inv == null || inv.seed != me.id || inv.canceled) return;
        inv.canceled = true;
        for (Xfer x : inv.peers) if (x.state == RUN) fail(x, "canceled");
        refreshMessage(inv);
        inv.closedSent = true;
        sendAll();
    }

    // ---------------------------------------------------------------- скачать

    /** Пир подтверждает приглашение: соединение с сидом, название приходит сразу, текст — по ходу передачи. */
    static void join(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, int chatId, int seq) {
        Chat c = store.chat(chatId);
        Invite inv = invites.get(key(chatId, seq));
        long now = System.currentTimeMillis();
        if (c == null || !c.has(me.id) || s.system) return;
        if (inv == null || !inv.open(now)) {
            Actions.toast(sp, "rppda.err.xfer_closed", true);
            return;
        }
        if (inv.seed == me.id) return;
        if (downloading(me.id)) {
            Actions.toast(sp, "rppda.err.xfer_busy", true);
            return;
        }
        if (seeding(me.id)) {
            Actions.toast(sp, "rppda.err.xfer_sending", true);
            return;
        }
        if (inv.running() >= PdaConfig.transferPeers) {
            Actions.toast(sp, "rppda.err.xfer_peers", true, Integer.toString(PdaConfig.transferPeers));
            return;
        }
        if (me.notes.size() >= Note.LIMIT) {
            Actions.toast(sp, "rppda.err.notes_full", true);
            return;
        }
        Account seedAcc = store.account(inv.seed);
        ServerPlayer seedP = PdaServer.server == null ? null : PdaServer.server.getPlayerList().getPlayer(inv.seedPlayer);
        String why = seedAcc == null ? "offline" : seedProblem(store, inv, seedAcc, seedP);
        if (why == null) why = distance(seedP, sp);
        if (why != null) {
            // Причина — ключом перевода: переведёт клиент (у сервера нет языков модов).
            Actions.toast(sp, "rppda.err.xfer_cant", true, "rppda.xfer.why." + why);
            return;
        }
        // Название — сразу целиком: на него место нужно уже сейчас.
        if (!Memory.allow(sp, me, s, 0, Memory.bytes(inv.title))) return;
        Note src = note(seedAcc, inv.note);
        Note n = new Note(me.nextNote++, inv.title, "");
        n.created = n.updated = now;
        n.from = Views.nameFor(store, me, inv.seed);
        n.cipher = inv.cipher;
        n.partial = true;
        n.expect = src == null ? 0 : Memory.bytes(src.text);
        me.notes.add(n);
        store.markAccounts();
        Xfer x = new Xfer(nextId++, inv, me.id, sp.getUUID(), n.id, n.expect);
        inv.peers.add(x);
        if (x.total == 0) complete(store, x, me, n);
        Actions.sendNotes(sp, me);
        sendAll();
    }

    // ---------------------------------------------------------------- раз в секунду

    static void tick() {
        PdaStore store = PdaServer.store;
        if (store == null || PdaServer.server == null || invites.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Invite inv : invites.values()) {
            Account seedAcc = store.account(inv.seed);
            ServerPlayer seedP = PdaServer.server.getPlayerList().getPlayer(inv.seedPlayer);
            String seedWhy = seedAcc == null ? "offline" : inv.canceled ? "canceled" : seedProblem(store, inv, seedAcc, seedP);
            int n = inv.running();
            for (Xfer x : inv.peers) {
                if (x.state != RUN) continue;
                Account peer = store.account(x.peer);
                ServerPlayer peerP = PdaServer.server.getPlayerList().getPlayer(x.peerPlayer);
                Note pn = peer == null ? null : note(peer, x.peerNote);
                String why = seedWhy;
                if (why == null) why = peer == null || peerP == null ? "offline" : device(peerP, peer);
                if (why == null && pn == null) why = "deleted";
                if (why == null) why = distance(seedP, peerP);
                if (why != null) {
                    fail(x, why);
                    continue;
                }
                // Скорость: доля сида (делится между пирами) и не быстрее КПК пира.
                x.rate = Math.max(1, Math.min(Memory.speed(seedAcc) / Math.max(1, n), Memory.speed(peer)));
                int step = Math.min(x.rate, x.total - x.done);
                int free = Memory.free(peerP, peer, PdaServer.sessions.get(x.peerPlayer));
                boolean noSpace = step > free;
                if (noSpace) step = Math.max(0, free);
                Note src = note(seedAcc, inv.note);
                x.done += step;
                pn.text = prefix(src == null ? "" : src.text, x.done);
                store.markAccounts();
                Memory.push(peerP, peer);
                if (noSpace) {
                    fail(x, "space");
                    Memory.full(peerP);
                } else if (x.done >= x.total) complete(store, x, peer, pn);
            }
            // Приглашение закрылось (вышло время) — у всех в чате кнопка «скачать» гаснет.
            if (!inv.open(now) && !inv.closedSent) {
                inv.closedSent = true;
                refreshMessage(inv);
            }
        }
        sendAll();
        // Законченные передачи уже показаны (итог ушёл игрокам) — забыть; закрытые приглашения без передач — тоже.
        for (Iterator<Invite> it = invites.values().iterator(); it.hasNext(); ) {
            Invite inv = it.next();
            inv.peers.removeIf(x -> x.state != RUN);
            if (!inv.open(now) && inv.peers.isEmpty()) it.remove();
        }
    }

    /** Почему у сида передача не идёт (null — всё в порядке): не в сети, нет КПК, заблокирован, заметку меняли. */
    @Nullable
    private static String seedProblem(PdaStore store, Invite inv, Account seedAcc, @Nullable ServerPlayer seedP) {
        if (seedP == null) return "offline";
        String d = device(seedP, seedAcc);
        if (d != null) return d;
        Note src = note(seedAcc, inv.note);
        if (src == null || src.updated != inv.noteUpdated) return "changed";
        return null;
    }

    /** КПК этой учётки при игроке и не заблокирован (null — да), иначе причина. */
    @Nullable
    private static String device(ServerPlayer sp, Account a) {
        ItemStack st = Devices.stackOf(sp, a);
        if (st == null) return "device";
        Devices.Status status = Devices.status(st, PdaServer.store);
        return status == Devices.Status.OK ? null : "locked";
    }

    @Nullable
    private static String distance(@Nullable ServerPlayer seed, @Nullable ServerPlayer peer) {
        if (seed == null || peer == null) return "offline";
        if (seed.level() != peer.level()) return "dim";
        double r = PdaConfig.transferRadius;
        return seed.distanceToSqr(peer) > r * r ? "range" : null;
    }

    private static void complete(PdaStore store, Xfer x, Account peer, Note n) {
        x.state = DONE;
        n.partial = false;
        n.expect = 0;
        n.updated = System.currentTimeMillis();
        store.markAccounts();
        notesChanged(x);
    }

    private static void fail(Xfer x, String reason) {
        x.state = FAIL;
        x.reason = reason;
        notesChanged(x);
    }

    /** У пира с открытым окном — обновить список заметок (готово или битая). */
    private static void notesChanged(Xfer x) {
        PdaStore store = PdaServer.store;
        if (store == null || PdaServer.server == null) return;
        ServerPlayer p = PdaServer.server.getPlayerList().getPlayer(x.peerPlayer);
        PdaServer.Session s = p == null ? null : PdaServer.sessions.get(p.getUUID());
        Account peer = store.account(x.peer);
        if (p != null && peer != null && s != null && s.mode == PdaServer.Session.Mode.OPEN && s.acting == peer.id)
            Actions.sendNotes(p, peer);
    }

    @Nullable
    private static Note note(@Nullable Account a, int id) {
        if (a == null) return null;
        for (Note n : a.notes) if (n.id == id) return n;
        return null;
    }

    /** Первые {@code count} знаков текста. */
    static String prefix(String text, int count) {
        int cps = text.codePointCount(0, text.length());
        return count >= cps ? text : text.substring(0, text.offsetByCodePoints(0, Math.max(0, count)));
    }

    /** Приглашение открылось/закрылось — у тех, кто смотрит этот чат, сообщение перерисуется. */
    private static void refreshMessage(Invite inv) {
        PdaStore store = PdaServer.store;
        if (store == null || PdaServer.server == null) return;
        Chat c = store.chat(inv.chat);
        Message m = c == null ? null : store.log(c).find(inv.seq);
        if (m == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            PdaServer.Session s = PdaServer.sessions.get(p.getUUID());
            if (s == null || s.mode != PdaServer.Session.Mode.OPEN || s.openChat != c.id) continue;
            CompoundTag t = new CompoundTag();
            t.putInt("chat", c.id);
            t.put("m", Views.message(store, s, c, m));
            Net.send(p, "msgupd", t);
        }
    }

    // ---------------------------------------------------------------- строки загрузки у игроков

    /** Каждому участнику — его передачи: строки в чате КПК и внизу чата игры. Законченные уходят один раз. */
    private static void sendAll() {
        PdaStore store = PdaServer.store;
        if (store == null || PdaServer.server == null) return;
        Map<UUID, ListTag> out = new HashMap<>();
        Set<UUID> involved = new HashSet<>();
        for (Invite inv : invites.values()) {
            for (Xfer x : inv.peers) {
                involved.add(x.peerPlayer);
                involved.add(inv.seedPlayer);
                Account peer = store.account(x.peer), seed = store.account(inv.seed);
                out.computeIfAbsent(x.peerPlayer, k -> new ListTag()).add(entry(x, true, Views.nameFor(store, peer, inv.seed)));
                out.computeIfAbsent(inv.seedPlayer, k -> new ListTag()).add(entry(x, false, Views.nameFor(store, seed, x.peer)));
            }
        }
        Set<UUID> targets = new HashSet<>(involved);
        targets.addAll(lastSent);
        lastSent.clear();
        lastSent.addAll(involved);
        for (UUID id : targets) {
            ServerPlayer p = PdaServer.server.getPlayerList().getPlayer(id);
            if (p == null) continue;
            CompoundTag t = new CompoundTag();
            t.put("list", out.getOrDefault(id, new ListTag()));
            Net.send(p, "xfer", t);
        }
    }

    private static CompoundTag entry(Xfer x, boolean down, String who) {
        CompoundTag e = new CompoundTag();
        e.putInt("id", x.id);
        e.putBoolean("down", down);
        e.putInt("chat", x.inv.chat);
        e.putInt("seq", x.inv.seq);
        e.putString("title", x.inv.title);
        e.putString("who", who);
        e.putInt("done", x.done);
        e.putInt("total", x.total);
        e.putInt("rate", x.state == RUN ? x.rate : 0);
        e.putInt("state", x.state);
        e.putString("reason", x.reason);
        if (down) e.putInt("note", x.peerNote);
        return e;
    }

    /** Размер файла в приглашении. */
    static int sizeOf(Message m) {
        String[] p = m.text.split("\n", 3);
        try {
            return p.length < 3 ? 0 : Integer.parseInt(p[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
