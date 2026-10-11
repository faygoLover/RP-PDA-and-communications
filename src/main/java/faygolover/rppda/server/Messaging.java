package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.network.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Отправка сообщений и доставка: окна КПК, строки в чате игры (с голубым фоном — рисует клиент), звук, счётчик
 * непрочитанного. Всё в потоке сервера.
 */
public final class Messaging {
    private Messaging() {}

    private static final Map<Integer, Deque<Long>> recent = new HashMap<>();
    private static final Map<UUID, Long> lastSound = new HashMap<>();

    static void reset() {
        recent.clear();
        lastSound.clear();
    }

    /** Почему не отправлено (ключ перевода) или {@code null}. */
    @Nullable
    public static String check(Account from, Chat c, String text) {
        return check(from, c, text, null);
    }

    /** То же, плюс связь у КПК в руках у {@code holder} (без связи пишется только в диалог с Системой). */
    @Nullable
    public static String check(Account from, Chat c, String text, @Nullable ServerPlayer holder) {
        if (holder != null && !PdaServer.emergency(c) && !PdaServer.signal(holder, from)) return "rppda.err.no_signal";
        if (text.isBlank()) return "rppda.err.empty";
        if (text.length() > PdaConfig.maxLength) return "rppda.err.too_long";
        if (!c.canWrite(from.id)) return c.type == Chat.Type.CHANNEL ? "rppda.err.channel" : "rppda.err.not_member";
        if (c.type == Chat.Type.DM) {
            PdaStore store = PdaServer.store;
            Account peer = store == null ? null : store.account(c.peer(from.id));
            if (peer != null && peer.blocked.contains(from.id)) return "rppda.err.blocked_by";
        }
        long now = System.currentTimeMillis();
        Deque<Long> q = recent.computeIfAbsent(from.id, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 10_000) q.removeFirst();
        if (q.size() >= PdaConfig.rateLimit) return "rppda.err.rate";
        q.addLast(now);
        return null;
    }

    /**
     * Отправить от учётки. {@code gm} — ник ГМа, если пишет он; {@code forceLine} — этот игрок увидит строку в
     * чате в любом случае ({@code /tell} админу).
     */
    public static Message post(Chat c, Account from, String text, int replyTo, int flags, @Nullable String gm,
                               @Nullable ServerPlayer forceLine) {
        PdaStore store = PdaServer.store;
        Message m = store.append(c, from.id, text, replyTo, flags, gm, System.currentTimeMillis());
        if (gm == null) {
            from.lastRead.put(c.id, m.seq);
            store.markAccounts();
        }
        deliver(c, m, from, forceLine);
        return m;
    }

    /** Служебная строка группы (без звука и строк в чате). */
    public static void notice(Chat c, String key, String... args) {
        PdaStore store = PdaServer.store;
        String text = key + (args.length == 0 ? "" : "\n" + String.join("\n", args));
        Message m = store.append(c, 0, text, 0, Message.NOTICE, null, System.currentTimeMillis());
        deliver(c, m, store.system(), null);
    }

    static void deliver(Chat c, Message m, Account from, @Nullable ServerPlayer forceLine) {
        PdaStore store = PdaServer.store;
        if (PdaServer.server == null || store == null) return;
        boolean notice = m.has(Message.NOTICE);
        boolean emergency = PdaServer.emergency(c);
        for (ServerPlayer sp : PdaServer.server.getPlayerList().getPlayers()) {
            UUID id = sp.getUUID();
            PdaServer.Session s = PdaServer.sessions.get(id);
            boolean admin = PdaServer.isAdmin(sp);
            boolean open = s != null && s.mode == PdaServer.Session.Mode.OPEN;
            // Окно КПК без связи новое не получает (кроме диалога с Системой): придёт, когда связь вернут.
            if (open && !s.system && !emergency) {
                Account dev = store.account(s.device);
                if (dev != null && !PdaServer.signal(sp, dev)) open = false;
            }
            boolean viewing = open && s.openChat == c.id;
            if (viewing) {
                CompoundTag t = new CompoundTag();
                t.putInt("chat", c.id);
                t.put("m", Views.message(store, s, c, m));
                Net.send(sp, "msg", t);
                if (!s.gmSwitched() && !s.system && c.has(s.acting)) {
                    Account a = store.account(s.acting);
                    if (a != null) a.lastRead.put(c.id, m.seq);
                }
            }
            if (open && !viewing && (c.has(s.acting) || s.system && s.acting == 0 && c.type != Chat.Type.DM)) {
                Account viewer = store.account(s.acting);
                if (viewer != null) {
                    CompoundTag t = new CompoundTag();
                    t.put("row", Views.row(store, viewer, c, s));
                    Net.send(sp, "row", t);
                }
            }
            if (notice) continue;

            Integer actId = PdaServer.active.get(id);
            Account acc = actId == null ? null : store.account(actId);
            if (acc != null && !emergency && !PdaServer.signal(sp, acc)) acc = null;
            boolean member = acc != null && c.has(acc.id) && !(c.type == Chat.Type.DM && acc.blocked.contains(from.id));
            Account.Notify mode = member ? acc.notify(c.id) : null;
            boolean line = false;
            // В чат игры — только если игрок включил это для чата (и сервер разрешает), админам — всегда.
            if (member && acc.chatOut.contains(c.id) && (PdaConfig.allowChatOutput || admin)) line = true;
            if (admin && c.type == Chat.Type.GLOBAL) line = true;
            // Диалог с Системой — это переписка с администрацией: её видят все админы в сети.
            if (admin && c.type == Chat.Type.DM && c.has(0) && from.id != 0) line = true;
            if (sp == forceLine) line = true;
            if (line) sendLine(sp, store, acc, c, m, from, admin, false);

            boolean self = acc != null && acc.id == from.id;
            if (member && !self && !viewing && mode == Account.Notify.SOUND) ping(sp, acc);
            if (!self && (member || hudHas(sp, c))) sendUnread(sp);
        }
    }

    /** Строка в чате игры. Подписи контактов — свои у каждого получателя. */
    static void sendLine(ServerPlayer sp, PdaStore store, @Nullable Account viewer, Chat c, Message m, Account from,
                         boolean admin, boolean plain) {
        MutableComponent line = Component.empty();
        if (admin && m.gm != null)
            line.append(Component.translatable("rppda.chat.gm", m.gm).withStyle(ChatFormatting.DARK_RED)).append(" ");
        String name = Views.nameFor(store, viewer, from.id);
        switch (c.type) {
            case DM -> {
                line.append(Component.literal("✉ ").withStyle(ChatFormatting.AQUA));
                if (viewer != null && viewer.id == from.id) {
                    String to = Views.nameFor(store, viewer, c.peer(from.id));
                    line.append(Component.translatable("rppda.chat.to", to).withStyle(ChatFormatting.AQUA));
                } else if (viewer == null || !c.has(viewer.id)) {
                    String to = Views.nameFor(store, viewer, c.peer(from.id));
                    line.append(Component.literal(name + " → " + to).withStyle(ChatFormatting.AQUA));
                } else line.append(Component.literal(name).withStyle(ChatFormatting.AQUA));
            }
            case GROUP, CHANNEL -> line.append(Component.literal("[" + c.title + "] ").withStyle(ChatFormatting.DARK_AQUA))
                    .append(Component.literal(name).withStyle(ChatFormatting.AQUA));
            default -> line.append(Component.literal(name).withStyle(ChatFormatting.AQUA));
        }
        if (m.has(Message.NOTE)) line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatable("rppda.note.line", m.text.split("\n", 2)[0]).withStyle(ChatFormatting.GOLD));
        else if (m.has(Message.ACTION)) line.append(Component.literal(" " + m.text).withStyle(ChatFormatting.ITALIC));
        else line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY)).append(Component.literal(m.text));
        if (plain) {
            sp.sendSystemMessage(line);
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putString("c", Component.Serializer.toJson(line));
        t.putInt("chat", c.id);
        Net.send(sp, "line", t);
    }

    static void ping(ServerPlayer sp, Account a) {
        if (!a.silent) ping(sp);
    }

    private static void ping(ServerPlayer sp) {
        if (PdaConfig.soundVolume <= 0) return;
        long now = sp.level().getGameTime();
        Long last = lastSound.get(sp.getUUID());
        if (last != null && now - last < 20) return;
        lastSound.put(sp.getUUID(), now);
        sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), ModItems.NOTIFY.get(), SoundSource.PLAYERS,
                (float) PdaConfig.soundVolume, 1f);
    }

    /** Значок непрочитанного в HUD: сумма по чатам активной учётки (без выключенных) и приглашения. */
    public static void sendUnread(ServerPlayer sp) {
        PdaStore store = PdaServer.store;
        if (store == null) return;
        Account a = hudAccount(sp);
        CompoundTag t = new CompoundTag();
        t.putBoolean("has", a != null);
        t.putInt("n", a == null ? 0 : unreadTotal(store, a, PdaServer.signalCut(sp, a)));
        Net.send(sp, "unread", t);
    }

    /**
     * Чей счётчик на HUD: КПК в слоте Curios «КПК» (если слот есть), иначе активный КПК.
     */
    @Nullable
    static Account hudAccount(ServerPlayer sp) {
        PdaStore store = PdaServer.store;
        if (store == null) return null;
        Integer id = Devices.slotMode() ? Devices.slotAccount(sp, store) : PdaServer.active.get(sp.getUUID());
        return id == null ? null : store.account(id);
    }

    private static boolean hudHas(ServerPlayer sp, Chat c) {
        Account h = hudAccount(sp);
        return h != null && c.has(h.id);
    }

    /** Непрочитанное: сообщения позже {@code cut} (связь пропала) не считаются, кроме диалога с Системой. */
    public static int unreadTotal(PdaStore store, Account a, long cut) {
        int n = a.notify(Chat.GLOBAL_ID) == Account.Notify.NONE ? 0 : unread(store, a, store.global(), cut);
        for (int id : store.chatsOf(a.id)) {
            Chat c = store.chat(id);
            if (c != null && a.notify(id) != Account.Notify.NONE) n += unread(store, a, c, cut);
        }
        return n + a.invites.size();
    }

    private static int unread(PdaStore store, Account a, Chat c, long cut) {
        if (cut == Long.MAX_VALUE || PdaServer.emergency(c) || c.lastTime <= cut) return unread(a, c);
        Message m = Views.lastVisible(store, c, cut);
        return m == null ? 0 : Math.max(0, m.seq - a.lastRead.getOrDefault(c.id, 0));
    }

    public static int unread(Account a, Chat c) {
        return Math.max(0, c.lastSeq - a.lastRead.getOrDefault(c.id, 0));
    }
}
