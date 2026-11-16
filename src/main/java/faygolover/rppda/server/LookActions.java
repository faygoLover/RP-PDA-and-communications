package faygolover.rppda.server;

import static faygolover.rppda.server.Actions.*;
import static faygolover.rppda.server.AccountActions.*;
import static faygolover.rppda.server.GroupActions.*;
import static faygolover.rppda.server.SystemActions.*;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.ChatLog;
import faygolover.rppda.data.Message;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Операции ГМа над аккаунтом: внешний вид, сигнал, привязка к персонажу, удаление.
 * Выделено из {@link Actions}.
 */
public final class LookActions {
    private LookActions() {}

    // ---------------------------------------------------------------- внешний вид (ГМ)

    /** ГМ задаёт вид новому (не зарегистрированному) КПК у себя в руке. */
    static void lookHand(ServerPlayer sp, PdaStore store, CompoundTag d) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (!PdaServer.isGm(sp) || s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) return;
        PdaItem.setLook(stack, faygolover.rppda.data.Look.load(d.getCompound("look")));
        PdaServer.sendRegister(sp, stack);
    }

    /** Вид учётки (из системного КПК или командой): у всех, кто держит её КПК, окно обновится. */
    public static void setLook(PdaStore store, Account a, faygolover.rppda.data.Look look, String gm) {
        if (a.look.same(look)) return;
        a.look = look;
        store.markAccounts();
        store.gmLog(gm, "look", label(a));
        if (PdaServer.server == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
            if (o != null && !o.system && o.device == a.id && o.mode == PdaServer.Session.Mode.OPEN) Views.sendState(p, o);
            PdaServer.scan(p);
        }
    }

    /** Связь на КПК (учётке). */
    public static void setSignal(PdaStore store, Account a, boolean on, String gm) {
        if (a.noSignal == !on) return;
        a.noSignal = !on;
        a.noSignalSince = on ? 0 : System.currentTimeMillis();
        store.markAccounts();
        store.gmLog(gm, on ? "signal_on" : "signal_off", label(a));
        PdaServer.signalChanged(a.id, null);
    }

    /** Связь у игрока (любой КПК у него в руках). */
    public static void setPlayerSignal(PdaStore store, java.util.UUID player, String name, boolean on, String gm) {
        boolean has = !store.noSignalPlayers.containsKey(player);
        if (has == on) return;
        if (on) store.noSignalPlayers.remove(player);
        else store.noSignalPlayers.put(player, System.currentTimeMillis());
        store.markMisc();
        store.gmLog(gm, on ? "signal_on" : "signal_off", name);
        PdaServer.signalChanged(null, player);
    }

    /** Режим персонажа: админ — как обычный игрок (системный КПК и команды остаются). */
    public static void setCharacter(PdaStore store, ServerPlayer sp, boolean on) {
        if (on) store.characterMode.add(sp.getUUID());
        else store.characterMode.remove(sp.getUUID());
        store.markMisc();
        store.gmLog(sp.getGameProfile().getName(), on ? "character_on" : "character_off", "");
        sp.displayClientMessage(net.minecraft.network.chat.Component.translatable(on ? "rppda.msg.character_on" : "rppda.msg.character_off")
                .withStyle(net.minecraft.ChatFormatting.GOLD), true);
        PdaServer.scan(sp);
        Messaging.sendUnread(sp);
    }

    static void delete(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, CompoundTag d) {
        Chat c = store.chat(d.getInt("chat"));
        if (c == null || !canRead(s, me, c)) return;
        ChatLog log = store.log(c);
        Message m = log.find(d.getInt("seq"));
        if (m == null) {
            toast(sp, "rppda.err.too_old", true);
            return;
        }
        if (m.deleted() || m.has(Message.NOTICE)) return;
        boolean gm = s.system;
        if (m.sender != me.id && !gm) return;
        m.flags |= Message.DELETED;
        log.dirty = true;
        if (m.seq == c.lastSeq) {
            c.lastPreview = "";
            store.markChats();
        }
        if (gm && m.sender != me.id) {
            Account from = store.account(m.sender);
            store.gmLog(sp.getGameProfile().getName(), "delete", (from == null ? "?" : from.name) + ": " + m.text);
        }
        CompoundTag t = new CompoundTag();
        t.putInt("chat", c.id);
        t.putInt("seq", m.seq);
        for (ServerPlayer p : sp.server.getPlayerList().getPlayers()) {
            PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
            if (o != null && o.openChat == c.id) Net.send(p, "deleted", t);
        }
    }
}
