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
    public static String nameFor(PdaStore store, @Nullable Account viewer, int id) {
        if (viewer != null) {
            String label = viewer.contacts.get(id);
            if (label != null && !label.isEmpty()) return label;
        }
        Account a = store.account(id);
        return a == null ? "?" : a.name;
    }

    static void sendState(ServerPlayer sp, PdaServer.Session s) {
        PdaStore store = PdaServer.store;
        Account a = store.account(s.acting);
        if (a == null) return;
        CompoundTag t = new CompoundTag();
        t.put("me", account(store, a, null));
        t.putBoolean("system", s.system);
        t.putBoolean("admin", PdaServer.isAdmin(sp));
        t.putBoolean("silent", a.silent);
        t.putBoolean("pw", a.hasPassword());
        t.putInt("maxLen", faygolover.rppda.config.PdaConfig.maxLength);

        List<Chat> list = new ArrayList<>();
        list.add(store.global());
        for (int id : store.chatsOf(a.id)) {
            Chat c = store.chat(id);
            if (c != null && (c.type != Chat.Type.DM || c.lastSeq > 0)) list.add(c);
        }
        list.sort(Comparator.comparingLong((Chat c) -> c.lastTime).reversed());
        ListTag rows = new ListTag();
        for (Chat c : list) rows.add(row(store, a, c, s));
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
        t.putInt("unread", Messaging.unreadTotal(store, a));
        Net.send(sp, "state", t);
    }

    static CompoundTag account(PdaStore store, Account a, @Nullable ServerPlayer online) {
        CompoundTag t = new CompoundTag();
        t.putInt("id", a.id);
        t.putString("name", a.name);
        t.putString("kind", a.kind.name());
        if (a.owner != null) t.putUUID("owner", a.owner);
        return t;
    }

    static CompoundTag row(PdaStore store, Account viewer, Chat c, PdaServer.Session s) {
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
        t.putString("mode", viewer.notify(c.id).name());
        t.putString("last", c.lastPreview);
        t.putLong("time", c.lastTime);
        if (c.lastSeq > 0 && c.lastSender != 0) t.putString("lastFrom", nameFor(store, viewer, c.lastSender));
        t.putBoolean("write", c.canWrite(viewer.id));
        t.putBoolean("isAdmin", c.admin == viewer.id && c.isGroup());
        t.putBoolean("member", c.has(viewer.id));
        t.putInt("count", c.type == Chat.Type.GLOBAL ? 0 : c.members.size());
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
        t.putLong("t", m.time);
        t.putString("x", m.deleted() && !s.system ? "" : m.text);
        t.putInt("fl", m.flags);
        if (m.gm != null && s.system) t.putString("g", m.gm);
        if (m.replyTo > 0) {
            t.putInt("r", m.replyTo);
            ChatLog log = store.log(c);
            Message r = log.find(m.replyTo);
            if (r != null) {
                t.putString("rn", r.sender == 0 ? store.system().name : nameFor(store, viewer, r.sender));
                t.putString("rx", r.deleted() && !s.system ? "" : cut(r.text));
            }
        }
        return t;
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
