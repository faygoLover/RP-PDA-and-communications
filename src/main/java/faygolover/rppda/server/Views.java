package faygolover.rppda.server;

import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.ChatLog;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Что уходит клиенту: состояние окна, строки списка чатов, сообщения. Клиент только показывает. */
public final class Views {
    private Views() {}

    /** Имя учётки глазами {@code viewer}: его подпись контакта, иначе имя. */
    /** Удалённая учётка — клиент подписывает «Удалённая учётка». */
    public static final String DELETED = "#deleted";

    public static String nameFor(PdaStore store, @Nullable Account viewer, int id) {
        if (viewer != null) {
            String label = viewer.contacts.get(id);
            if (label != null && !label.isEmpty()) return label;
        }
        Account a = store.accountAny(id);
        return a == null ? "?" : a.deleted ? DELETED : a.name;
    }

    static void sendState(ServerPlayer sp, PdaServer.Session s) {
        PdaStore store = PdaServer.store;
        Account a = store.account(s.acting);
        if (a == null) return;
        CompoundTag t = new CompoundTag();
        t.put("me", account(store, a, null));
        t.putBoolean("system", s.system);
        t.putBoolean("admin", PdaServer.isGm(sp));
        t.putBoolean("character", PdaServer.store.characterMode.contains(sp.getUUID()));
        if (!s.system) {
            Account dev = store.account(s.device);
            if (dev != null) {
                t.put("look", dev.look.save());
                // Память ретро-КПК (реалистичные ретро-КПК): занято, объём, ячейки расширения.
                // Без реалистичных ретро-КПК платы в ячейки ставятся и снимаются, но пока ничего не дают (находка 107).
                if (Memory.limited(dev)) t.put("mem", Memory.view(sp, dev, s));
                else if (Memory.retro(dev) && dev.kind != faygolover.rppda.data.Account.Kind.SYSTEM) {
                    CompoundTag m = Memory.view(sp, dev, s);
                    m.putBoolean("slots_only", true);
                    t.put("mem", m);
                }
            }
        }
        // Заметками делятся приглашением с передачей рядом (у системного КПК — по-старому).
        t.putBoolean("xfer", faygolover.rppda.config.PdaConfig.realisticRetro && !s.system);
        long cut = cut(sp, s);
        t.putBoolean("signal", cut == Long.MAX_VALUE);
        t.putBoolean("silent", a.silent);
        t.putBoolean("pw", a.hasPassword());
        // Через сколько секунд можно сменить картинку-аватарку (страница «Аватарка» показывает ожидание).
        t.putInt("avWait", AvatarPics.waitSeconds(sp));
        t.putInt("maxLen", faygolover.rppda.config.PdaConfig.maxLength);
        t.putString("style", faygolover.rppda.config.PdaConfig.style);
        t.putBoolean("allowOut", faygolover.rppda.config.PdaConfig.allowChatOutput || PdaServer.isAdmin(sp));

        List<Chat> list = new ArrayList<>();
        list.add(store.global());
        for (int id : store.chatsOf(a.id)) {
            Chat c = store.chat(id);
            if (c != null && (c.type != Chat.Type.DM || c.lastSeq > 0)) list.add(c);
        }
        list.sort(Comparator.comparingLong((Chat c) -> c.lastTime).reversed());
        ListTag rows = new ListTag();
        for (Chat c : list) rows.add(row(store, a, c, s, cut));
        t.put("chats", rows);

        ListTag contacts = new ListTag();
        a.contacts.forEach((id, label) -> {
            Account o = store.account(id);
            if (o == null) return;
            CompoundTag ct = account(store, o, null);
            ct.putString("label", label);
            ct.putBoolean("blocked", a.blocked.contains(id));
            contacts.add(ct);
        });
        for (int id : a.blocked) {
            if (a.contacts.containsKey(id)) continue;
            Account o = store.account(id);
            if (o == null) continue;
            CompoundTag ct = account(store, o, null);
            ct.putString("label", "");
            ct.putBoolean("blocked", true);
            contacts.add(ct);
        }
        t.put("contacts", contacts);

        ListTag invites = new ListTag();
        for (int id : a.invites) {
            Chat c = store.chat(id);
            if (c == null) continue;
            CompoundTag ct = new CompoundTag();
            ct.putInt("chat", c.id);
            ct.putString("title", c.title);
            ct.putBoolean("channel", c.type == Chat.Type.CHANNEL);
            ct.putString("from", nameFor(store, a, c.admin));
            invites.add(ct);
        }
        t.put("invites", invites);
        if (!s.system) t.put("offers", Nearby.rows(store, a));
        t.putInt("unread", Messaging.unreadTotal(store, a, s.system ? Long.MAX_VALUE : cut));
        Net.send(sp, "state", t);
    }

    static CompoundTag account(PdaStore store, Account a, @Nullable ServerPlayer online) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", a.id);
        t.putString("name", a.name);
        t.putString("kind", a.kind.name());
        if (a.owner != null) t.putUUID("owner", a.owner);
        avatar(a, t);
        return t;
    }

    /** Вид аватарки учётки (клиент рисует сам; лицо со скина — по {@code owner}). */
    static CompoundTag avatar(Account a, CompoundTag t) {
        t.putInt("av", a.avatar);
        t.putInt("avColor", a.avatarColor);
        t.putString("avMark", a.avatarMark);
        t.putInt("avPic", a.avatarPic);
        return t;
    }

    /** С какого момента окну не видно сообщений (нет связи); у системного КПК и у ГМа в чужой учётке — всё видно. */
    static long cut(ServerPlayer sp, PdaServer.Session s) {
        if (s.system) return Long.MAX_VALUE;
        Account dev = PdaServer.store.account(s.device);
        return dev == null ? Long.MAX_VALUE : PdaServer.signalCut(sp, dev);
    }

    /** Последнее сообщение чата, видное при связи до {@code cut} (null — нет таких в «свежих»). */
    @Nullable
    static Message lastVisible(PdaStore store, Chat c, long cut) {
        List<Message> ms = store.log(c).messages;
        for (int i = ms.size() - 1; i >= 0; i--) if (ms.get(i).time <= cut) return ms.get(i);
        return null;
    }

    static CompoundTag row(PdaStore store, Account viewer, Chat c, PdaServer.Session s) {
        return row(store, viewer, c, s, Long.MAX_VALUE);
    }

    static CompoundTag row(PdaStore store, Account viewer, Chat c, PdaServer.Session s, long cut) {
        if (cut != Long.MAX_VALUE && !PdaServer.emergency(c) && c.lastTime > cut) {
            // Без связи: строка — как была на момент, когда связь пропала.
            CompoundTag t = row(store, viewer, c, s, Long.MAX_VALUE);
            Message m = lastVisible(store, c, cut);
            int seq = m == null ? 0 : m.seq;
            t.putInt("unread", c.has(viewer.id) ? Math.max(0, seq - viewer.lastRead.getOrDefault(c.id, 0)) : 0);
            String lt = m == null ? "" : m.has(Message.NOTE) ? "#note:" + noteTitle(m.text)
                    : m.has(Message.FILE) ? "#file:" + PdaStore.fileTitle(m.text) : m.text;
            if (m != null && m.sender != viewer.id && Illiterate.is(s)) lt = Illiterate.scribble(lt);
            t.putString("last", m == null || m.deleted() || m.has(Message.NOTICE) ? "" : lt.length() > 60 ? lt.substring(0, 60) + "…" : lt);
            t.putLong("time", m == null ? c.created : m.time);
            if (m != null && m.sender != 0) t.putString("lastFrom", nameFor(store, viewer, m.sender));
            else t.remove("lastFrom");
            return t;
        }
        CompoundTag t = new CompoundTag();
        t.putInt("id", c.id);
        t.putString("type", c.type.name());
        if (c.type == Chat.Type.DM) {
            if (c.has(viewer.id)) {
                int peer = c.peer(viewer.id);
                t.putInt("peer", peer);
                t.putString("title", nameFor(store, viewer, peer));
                Account p = store.account(peer);
                if (p != null && p.owner != null) t.putUUID("owner", p.owner);
            } else {
                List<String> names = new ArrayList<>();
                for (int m : c.members) names.add(nameFor(store, null, m));
                t.putString("title", String.join(" ↔ ", names));
            }
        } else t.putString("title", c.title);
        t.putInt("unread", c.has(viewer.id) ? Messaging.unread(viewer, c) : 0);
        t.putString("notify", viewer.notify(c.id).name());
        t.putBoolean("out", viewer.chatOut.contains(c.id));
        boolean before = c.type == Chat.Type.GLOBAL && viewer.id != 0 && c.lastTime < viewer.created;
        boolean scribble = c.lastSender != viewer.id && Illiterate.is(s);
        t.putString("last", before ? "" : scribble ? Illiterate.scribble(c.lastPreview) : c.lastPreview);
        t.putLong("time", before ? 0 : c.lastTime);
        if (c.lastSeq > 0 && c.lastSender != 0) t.putString("lastFrom", nameFor(store, viewer, c.lastSender));
        t.putBoolean("write", c.canWrite(viewer.id));
        t.putBoolean("isAdmin", c.admin == viewer.id && c.isGroup());
        t.putBoolean("member", c.has(viewer.id));
        t.putInt("count", c.type == Chat.Type.GLOBAL ? 0 : c.members.size());
        if (faygolover.rppda.config.PdaConfig.realisticRetro) t.putInt("size", store.chatBytes(c));
        return t;
    }

    static CompoundTag message(PdaStore store, PdaServer.Session s, Chat c, Message m) {
        Account viewer = store.account(s.acting);
        CompoundTag t = new CompoundTag();
        t.putInt("s", m.seq);
        t.putInt("f", m.sender);
        t.putString("n", m.sender == 0 ? store.system().name : nameFor(store, viewer, m.sender));
        Account from = store.account(m.sender);
        if (from != null && from.owner != null) t.putUUID("o", from.owner);
        t.putInt("fs", m.sender);
        t.putLong("t", m.time);
        // Заметка в чате — только название (прочитать — добавить себе); текст не уходит клиенту.
        String x = m.has(Message.NOTE) ? noteTitle(m.text) : m.has(Message.FILE) ? PdaStore.fileTitle(m.text) : m.text;
        if (m.has(Message.FILE)) {
            // Приглашение скачать файл: размер и сколько ещё открыто.
            t.putInt("fsz", Transfers.sizeOf(m));
            t.putLong("fleft", Transfers.left(c.id, m.seq));
        }
        // Неграмотный: чужое — каракулями (служебные строки и действия — как есть).
        boolean illiterate = Illiterate.is(s);
        if (illiterate && viewer != null && m.sender != viewer.id && !m.has(Message.NOTICE) && !m.has(Message.ACTION)) x = Illiterate.scribble(x);
        t.putString("x", m.deleted() && !s.system ? "" : x);
        t.putInt("fl", m.flags);
        if (m.gm != null && s.system) t.putString("g", m.gm);
        if (m.replyTo > 0) {
            t.putInt("r", m.replyTo);
            ChatLog log = store.log(c);
            Message r = log.find(m.replyTo);
            if (r != null) {
                t.putString("rn", r.sender == 0 ? store.system().name : nameFor(store, viewer, r.sender));
                String rx = r.deleted() && !s.system ? "" : cut(r.has(Message.NOTE) ? noteTitle(r.text)
                        : r.has(Message.FILE) ? PdaStore.fileTitle(r.text) : r.text);
                if (illiterate && viewer != null && r.sender != viewer.id) rx = Illiterate.scribble(rx);
                t.putString("rx", rx);
            }
        }
        return t;
    }

    /** Заметка в сообщении хранится «название\nтекст». */
    public static String noteTitle(String raw) {
        int nl = raw.indexOf('\n');
        return nl < 0 ? raw : raw.substring(0, nl);
    }

    private static String cut(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    /** Страница сообщений (по возрастанию номера); удалённые видит только системный КПК. */
    static ListTag page(PdaStore store, PdaServer.Session s, Chat c, List<Message> msgs) {
        ListTag l = new ListTag();
        for (Message m : msgs) {
            if (m.deleted() && !s.system) continue;
            l.add(message(store, s, c, m));
        }
        return l;
    }
}
