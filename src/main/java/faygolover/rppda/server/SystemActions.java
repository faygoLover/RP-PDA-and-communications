package faygolover.rppda.server;

import static faygolover.rppda.server.Actions.*;
import static faygolover.rppda.server.AccountActions.*;
import static faygolover.rppda.server.LookActions.*;
import static faygolover.rppda.server.GroupActions.*;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Операции системного КПК (ГМ): список аккаунтов, чужая переписка, журнал, правки аккаунтов.
 * Выделено из {@link Actions}.
 */
public final class SystemActions {
    private SystemActions() {}

    // ---------------------------------------------------------------- системный КПК

    static void system(ServerPlayer sp, PdaServer.Session s, PdaStore store, String op, CompoundTag d) {
        String gm = sp.getGameProfile().getName();
        switch (op) {
            case "sys.accounts" -> {
                String q = d.getString("q").strip().toLowerCase(Locale.ROOT);
                List<Account> list = new ArrayList<>();
                for (Account a : store.accounts.values()) {
                    // Удалённые учётки остаются в хранилище (подпись «Удалённая учётка» в группах), но не в списке.
                    if (a.deleted) continue;
                    if (q.isEmpty() || a.name.toLowerCase(Locale.ROOT).contains(q)
                            || String.format("%08d", a.id).contains(q.replace(" ", ""))) list.add(a);
                }
                list.sort(Comparator.comparing((Account a) -> a.kind != Account.Kind.SYSTEM)
                        .thenComparing(a -> a.name.toLowerCase(Locale.ROOT)));
                ListTag l = new ListTag();
                for (Account a : list) {
                    if (l.size() >= 300) break;
                    l.add(sysAccount(store, a));
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.accounts", t);
            }
            case "sys.chats" -> {
                List<Chat> list = new ArrayList<>();
                for (Chat c : store.allChats()) if (c.type != Chat.Type.DM || c.lastSeq > 0) list.add(c);
                list.sort(Comparator.comparingLong((Chat c) -> c.lastTime).reversed());
                Account viewer = store.account(s.acting);
                ListTag l = new ListTag();
                for (Chat c : list) {
                    if (l.size() >= 500) break;
                    l.add(Views.row(store, viewer, c, s));
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.chats", t);
            }
            case "sys.log" -> {
                ListTag l = new ListTag();
                Iterator<PdaStore.GmEntry> it = store.gmLog.descendingIterator();
                while (it.hasNext() && l.size() < 300) {
                    PdaStore.GmEntry e = it.next();
                    CompoundTag ct = new CompoundTag();
                    ct.putLong("time", e.time());
                    ct.putString("gm", e.gm());
                    ct.putString("action", e.action());
                    ct.putString("target", e.target());
                    l.add(ct);
                }
                CompoundTag t = new CompoundTag();
                t.put("list", l);
                Net.send(sp, "sys.log", t);
            }
            case "sys.switch" -> {
                Account a = store.account(d.getInt("id"));
                if (a == null) return;
                s.acting = a.id;
                s.openChat = -1;
                if (a.id != 0) store.gmLog(gm, "switch", label(a));
                Views.sendState(sp, s);
            }
            case "sys.give" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.issue++;
                a.burned = false;
                if (a.hasPassword()) a.locked = true;
                PdaServer.holders.remove(a.id);
                store.markAccounts();
                ItemStack stack = new ItemStack(ModItems.PDA.get());
                PdaItem.bind(stack, a.id, a.issue, a.name);
                PdaItem.setLook(stack, a.look);
                if (!sp.getInventory().add(stack)) sp.drop(stack, false);
                store.gmLog(gm, "give", label(a));
                toast(sp, "rppda.toast.given", false, a.name);
                PdaServer.lock(a.id);
                refreshAccount(sp, store, a);
            }
            case "sys.unlock" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.locked = false;
                store.markAccounts();
                store.gmLog(gm, "unlock", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.avatar_reset" -> {
                Account a = target(store, d);
                if (a == null || a.avatarPic == 0) return;
                AvatarPics.remove(a);
                store.markAccounts();
                store.gmLog(gm, "avatar_reset", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.resetpw" -> {
                Account a = target(store, d);
                if (a == null) return;
                a.pwHash = a.pwSalt = null;
                a.locked = false;
                PdaServer.holders.remove(a.id);
                store.markAccounts();
                store.gmLog(gm, "resetpw", label(a));
                refreshAccount(sp, store, a);
            }
            case "sys.tp" -> {
                Account a = target(store, d);
                if (a == null || a.owner == null) return;
                ServerPlayer p = sp.server.getPlayerList().getPlayer(a.owner);
                if (p == null) {
                    toast(sp, "rppda.err.offline", true);
                    return;
                }
                sp.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
                store.gmLog(gm, "tp", label(a));
            }
            case "sys.npc" -> {
                String name = d.getString("name").strip();
                if (name.isEmpty() || name.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                Account a = store.createAccount(Account.Kind.NPC, name, null);
                ItemStack stack = new ItemStack(ModItems.PDA.get());
                PdaItem.bind(stack, a.id, a.issue, a.name);
                PdaItem.setLook(stack, a.look);
                if (!sp.getInventory().add(stack)) sp.drop(stack, false);
                store.gmLog(gm, "npc", label(a));
                toast(sp, "rppda.toast.npc", false, a.name, PdaItem.formatId(a.id));
                refreshAccount(sp, store, a);
            }
            case "sys.look" -> {
                Account a = target(store, d);
                if (a == null) return;
                setLook(store, a, faygolover.rppda.data.Look.load(d.getCompound("look")), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.signal" -> {
                Account a = target(store, d);
                if (a == null) return;
                setSignal(store, a, d.getBoolean("on"), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.signalOwner" -> {
                Account a = target(store, d);
                if (a == null || a.owner == null) return;
                setPlayerSignal(store, a.owner, a.name, d.getBoolean("on"), gm);
                refreshAccount(sp, store, a);
            }
            case "sys.delete" -> {
                Account a = target(store, d);
                if (a == null) return;
                String who = label(a);
                // Окна с этой учёткой закрываются, КПК с ней больше не работают.
                for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
                    PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
                    if (o != null && !o.system && o.device == a.id) PdaServer.close(p);
                    if (o != null && o.system && o.acting == a.id) o.acting = 0;
                }
                PdaServer.holders.remove(a.id);
                store.deleteAccount(a);
                store.gmLog(gm, "delete_account", who);
                toast(sp, "rppda.toast.deleted", false, who);
                for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) PdaServer.scan(p);
                Views.sendState(sp, s);
            }
            case "sys.character" -> {
                setCharacter(store, sp, d.getBoolean("on"));
                Views.sendState(sp, s);
            }
            case "sys.rename" -> {
                Account a = target(store, d);
                String name = d.getString("name").strip();
                if (a == null || a.kind != Account.Kind.NPC) return;
                if (name.isEmpty() || name.length() > TITLE_MAX) {
                    toast(sp, "rppda.err.title", true);
                    return;
                }
                store.gmLog(gm, "rename", label(a) + " → " + name);
                a.name = name;
                store.markAccounts();
                refreshAccount(sp, store, a);
            }
            default -> {}
        }
    }

    @Nullable
    static Account target(PdaStore store, CompoundTag d) {
        Account a = store.account(d.getInt("id"));
        return a == null || a.kind == Account.Kind.SYSTEM ? null : a;
    }

    static String label(Account a) {
        return a.name + " (" + PdaItem.formatId(a.id) + ")";
    }

    static CompoundTag sysAccount(PdaStore store, Account a) {
        CompoundTag t = Views.account(store, a, null);
        t.putBoolean("locked", a.locked);
        t.putBoolean("pw", a.hasPassword());
        t.putInt("issue", a.issue);
        t.put("look", a.look.save());
        t.putBoolean("signal", !a.noSignal);
        if (a.owner != null) t.putBoolean("ownerSignal", !store.noSignalPlayers.containsKey(a.owner));
        t.putLong("created", a.created);
        boolean online = false;
        if (a.owner != null && PdaServer.server != null) online = PdaServer.server.getPlayerList().getPlayer(a.owner) != null;
        t.putBoolean("online", online);
        if (a.owner != null && PdaServer.server != null) {
            var profile = PdaServer.server.getProfileCache() == null ? null
                    : PdaServer.server.getProfileCache().get(a.owner).orElse(null);
            if (profile != null) t.putString("ownerName", profile.getName());
        }
        return t;
    }

    static void refreshAccount(ServerPlayer sp, PdaStore store, Account a) {
        CompoundTag t = new CompoundTag();
        t.put("acc", sysAccount(store, a));
        Net.send(sp, "sys.account", t);
    }
}
