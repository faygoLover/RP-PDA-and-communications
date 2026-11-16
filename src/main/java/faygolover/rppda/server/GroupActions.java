package faygolover.rppda.server;

import static faygolover.rppda.server.Actions.*;
import static faygolover.rppda.server.AccountActions.*;
import static faygolover.rppda.server.LookActions.*;
import static faygolover.rppda.server.SystemActions.*;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Операции групповых чатов: создание, участники, приглашения, права, выход.
 * Выделено из {@link Actions}.
 */
public final class GroupActions {
    private GroupActions() {}

    // ---------------------------------------------------------------- группы

    static void group(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, String op, CompoundTag d) {
        Chat c = store.chat(d.getInt("chat"));
        if (c == null || !c.isGroup()) return;
        switch (op) {
            case "group.accept" -> {
                if (!me.invites.contains(c.id)) return;
                store.addMember(c, me.id);
                store.markAccounts();
                Messaging.notice(c, "rppda.notice.joined", me.name);
                Views.sendState(sp, s);
                if (!s.system) Messaging.sendUnread(sp);
                return;
            }
            case "group.decline" -> {
                me.invites.remove(c.id);
                c.invited.remove(me.id);
                store.markAccounts();
                store.markChats();
                Views.sendState(sp, s);
                if (!s.system) Messaging.sendUnread(sp);
                return;
            }
            case "group.members" -> {
                if (!canRead(s, me, c)) return;
                sendMembers(sp, store, me, c);
                return;
            }
            default -> {}
        }
        if (!c.has(me.id)) return;
        boolean admin = c.admin == me.id;
        switch (op) {
            case "group.invite" -> {
                if (!admin) return;
                Account o = store.account(d.getInt("id"));
                if (o == null || o.kind == Account.Kind.SYSTEM) {
                    toast(sp, "rppda.err.no_account", true);
                    return;
                }
                if (c.has(o.id) || o.invites.contains(c.id)) {
                    toast(sp, "rppda.err.already", true);
                    return;
                }
                if (o.blocked.contains(me.id)) {
                    toast(sp, "rppda.err.blocked_by", true);
                    return;
                }
                o.invites.add(c.id);
                c.invited.add(o.id);
                store.markAccounts();
                store.markChats();
                toast(sp, "rppda.toast.invited", false, Views.nameFor(store, me, o.id));
                notifyInvite(o);
                sendMembers(sp, store, me, c);
            }
            case "group.kick" -> {
                int id = d.getInt("id");
                if (!admin || id == me.id) return;
                if (c.invited.remove(id)) {
                    Account o = store.account(id);
                    if (o != null) o.invites.remove(c.id);
                    store.markAccounts();
                    store.markChats();
                } else if (c.has(id)) {
                    store.removeMember(c, id);
                    Account o = store.account(id);
                    Messaging.notice(c, "rppda.notice.kicked", me.name, o == null ? "?" : o.name);
                }
                sendMembers(sp, store, me, c);
            }
            case "group.admin" -> {
                int id = d.getInt("id");
                if (!admin || !c.members.contains(id) || id == me.id) return;
                c.admin = id;
                store.markChats();
                Account o = store.account(id);
                Messaging.notice(c, "rppda.notice.admin", o == null ? "?" : o.name);
                sendMembers(sp, store, me, c);
            }
            case "group.rename" -> {
                String title = d.getString("title").strip();
                if (!admin) return;
                if (title.isEmpty() || title.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                c.title = title;
                store.markChats();
                Messaging.notice(c, "rppda.notice.renamed", me.name, title);
                sendMembers(sp, store, me, c);
            }
            case "group.leave" -> {
                store.removeMember(c, me.id);
                Messaging.notice(c, "rppda.notice.left", me.name);
                if (admin && c.admin != 0) {
                    Account o = store.account(c.admin);
                    Messaging.notice(c, "rppda.notice.admin", o == null ? "?" : o.name);
                }
                if (s.openChat == c.id) s.openChat = -1;
                Views.sendState(sp, s);
            }
            default -> {}
        }
    }

    static void notifyInvite(Account o) {
        if (PdaServer.server == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            Integer act = PdaServer.active.get(p.getUUID());
            if (act != null && act == o.id) Messaging.sendUnread(p);
        }
    }

    static void sendMembers(ServerPlayer sp, PdaStore store, Account me, Chat c) {
        CompoundTag t = new CompoundTag();
        t.putInt("chat", c.id);
        t.putString("title", c.title);
        t.putInt("admin", c.admin);
        t.putBoolean("channel", c.type == Chat.Type.CHANNEL);
        ListTag l = new ListTag();
        for (int id : c.members) {
            Account a = store.account(id);
            if (a == null) continue;
            CompoundTag e = Views.account(store, a, null);
            e.putString("name", Views.nameFor(store, me, id));
            l.add(e);
        }
        t.put("members", l);
        ListTag inv = new ListTag();
        for (int id : c.invited) {
            Account a = store.account(id);
            if (a == null) continue;
            CompoundTag e = Views.account(store, a, null);
            e.putString("name", Views.nameFor(store, me, id));
            inv.add(e);
        }
        t.put("invited", inv);
        Net.send(sp, "members", t);
    }
}
