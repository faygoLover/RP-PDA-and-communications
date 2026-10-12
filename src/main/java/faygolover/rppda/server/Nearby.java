package faygolover.rppda.server;

import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.network.Net;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Обмен контактами рядом (§7.6): игроки в {@code exchange_radius} с рабочим КПК, запрос — второму, принял — оба
 * в контактах друг у друга. Связь не нужна. Запросы живут {@link #LIFE_MS} и только в памяти.
 */
public final class Nearby {
    private Nearby() {}

    static final long LIFE_MS = 60_000L;

    /** Запрос: от какой учётки и какого игрока; отказ прячет его у получателя, но держит до конца срока (не спамить). */
    private static final class Offer {
        final int from;
        final UUID player;
        final long until;
        boolean declined;

        Offer(int from, UUID player, long until) {
            this.from = from;
            this.player = player;
            this.until = until;
        }
    }

    /** Кому (учётка) → от кого (учётка) → запрос. */
    private static final Map<Integer, LinkedHashMap<Integer, Offer>> offers = new HashMap<>();

    static void reset() {
        offers.clear();
    }

    /** Живые запросы к учётке (для HUD и списков). */
    public static int count(int acc) {
        Map<Integer, Offer> m = offers.get(acc);
        if (m == null) return 0;
        int n = 0;
        for (Offer o : m.values()) if (!o.declined) n++;
        return n;
    }

    // ---------------------------------------------------------------- действия окна

    static void handle(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, String op, CompoundTag d) {
        if (s.system) return;
        switch (op) {
            case "nearby" -> sendList(sp, store, me);
            case "nearby.offer" -> offer(sp, s, store, me, d.getInt("id"));
            case "nearby.accept" -> accept(sp, s, store, me, d.getInt("id"));
            case "nearby.decline" -> {
                Map<Integer, Offer> m = offers.get(me.id);
                Offer o = m == null ? null : m.get(d.getInt("id"));
                if (o == null || o.declined) return;
                o.declined = true;
                Views.sendState(sp, s);
                Messaging.sendUnread(sp);
            }
            default -> {}
        }
    }

    /** Кто рядом с рабочим КПК (кроме самого игрока). */
    private static List<ServerPlayer> around(ServerPlayer sp, double radius) {
        List<ServerPlayer> out = new ArrayList<>();
        if (PdaServer.server == null) return out;
        double r2 = radius * radius;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            if (p == sp || p.level() != sp.level() || p.isSpectator() || p.distanceToSqr(sp) > r2) continue;
            if (faygolover.rpcore.api.RpCoreAPI.unconscious(p) || PdaServer.active.get(p.getUUID()) == null) continue;
            out.add(p);
        }
        return out;
    }

    @Nullable
    private static Account usable(PdaStore store, ServerPlayer p) {
        Integer id = PdaServer.active.get(p.getUUID());
        Account a = id == null ? null : store.account(id);
        return a == null || a.deleted || a.kind == Account.Kind.SYSTEM ? null : a;
    }

    private static void sendList(ServerPlayer sp, PdaStore store, Account me) {
        ListTag l = new ListTag();
        Set<Integer> seen = new HashSet<>();
        long now = System.currentTimeMillis();
        for (ServerPlayer p : around(sp, PdaConfig.exchangeRadius)) {
            Account o = usable(store, p);
            if (o == null || o.id == me.id || !seen.add(o.id)) continue;
            CompoundTag e = Views.account(store, o, null);
            e.putString("name", Views.nameFor(store, me, o.id));
            e.putBoolean("known", me.contacts.containsKey(o.id) && o.contacts.containsKey(me.id));
            Map<Integer, Offer> m = offers.get(o.id);
            Offer sent = m == null ? null : m.get(me.id);
            e.putBoolean("sent", sent != null && sent.until > now);
            l.add(e);
        }
        CompoundTag t = new CompoundTag();
        t.put("list", l);
        Net.send(sp, "nearby", t);
    }

    private static void offer(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, int id) {
        ServerPlayer target = null;
        Account o = null;
        for (ServerPlayer p : around(sp, PdaConfig.exchangeRadius)) {
            Account a = usable(store, p);
            if (a != null && a.id == id) {
                target = p;
                o = a;
                break;
            }
        }
        if (target == null || o.id == me.id) {
            Actions.toast(sp, "rppda.err.not_near", true);
            sendList(sp, store, me);
            return;
        }
        if (me.contacts.containsKey(o.id) && o.contacts.containsKey(me.id)) {
            Actions.toast(sp, "rppda.err.already_contacts", true);
            return;
        }
        if (o.blocked.contains(me.id)) {
            Actions.toast(sp, "rppda.err.blocked_by", true);
            return;
        }
        // Встречный запрос — уже согласие.
        Map<Integer, Offer> mine = offers.get(me.id);
        Offer back = mine == null ? null : mine.get(o.id);
        if (back != null && !back.declined && back.until > System.currentTimeMillis()) {
            accept(sp, s, store, me, o.id);
            return;
        }
        long now = System.currentTimeMillis();
        Map<Integer, Offer> theirs = offers.computeIfAbsent(o.id, k -> new LinkedHashMap<>());
        Offer old = theirs.get(me.id);
        if (old != null && old.until > now) {
            Actions.toast(sp, "rppda.err.offer_pending", true);
            return;
        }
        theirs.put(me.id, new Offer(me.id, sp.getUUID(), now + LIFE_MS));
        Actions.toast(sp, "rppda.toast.offer_sent", false, Views.nameFor(store, me, o.id));
        sendList(sp, store, me);
        String from = Views.nameFor(store, o, me.id);
        PdaServer.actionbar(target, "rppda.offer.actionbar", from);
        Messaging.ping(target, o);
        refresh(target, o);
    }

    private static void accept(ServerPlayer sp, PdaServer.Session s, PdaStore store, Account me, int fromId) {
        Map<Integer, Offer> m = offers.get(me.id);
        Offer o = m == null ? null : m.get(fromId);
        if (o == null || o.declined || o.until <= System.currentTimeMillis()) {
            Actions.toast(sp, "rppda.err.offer_gone", true);
            Views.sendState(sp, s);
            return;
        }
        Account from = store.account(fromId);
        ServerPlayer fp = PdaServer.server == null ? null : PdaServer.server.getPlayerList().getPlayer(o.player);
        if (from == null || from.deleted) {
            m.remove(fromId);
            Actions.toast(sp, "rppda.err.offer_gone", true);
            Views.sendState(sp, s);
            Messaging.sendUnread(sp);
            return;
        }
        double far = PdaConfig.exchangeRadius * 2.0;
        if (fp == null || fp.level() != sp.level() || fp.distanceToSqr(sp) > far * far) {
            Actions.toast(sp, "rppda.err.not_near", true);
            return;
        }
        m.remove(fromId);
        Map<Integer, Offer> back = offers.get(fromId);
        if (back != null) back.remove(me.id);
        me.contacts.putIfAbsent(fromId, "");
        from.contacts.putIfAbsent(me.id, "");
        store.markAccounts();
        Actions.toast(sp, "rppda.toast.exchanged", false, Views.nameFor(store, me, fromId));
        Views.sendState(sp, s);
        Messaging.sendUnread(sp);
        PdaServer.actionbar(fp, "rppda.offer.done", Views.nameFor(store, from, me.id));
        refresh(fp, from);
    }

    /** Окно игрока на этой учётке — заново (строка запроса); значок HUD. */
    private static void refresh(ServerPlayer p, Account a) {
        PdaServer.Session o = PdaServer.sessions.get(p.getUUID());
        if (o != null && !o.system && o.device == a.id && o.mode == PdaServer.Session.Mode.OPEN) Views.sendState(p, o);
        Messaging.sendUnread(p);
    }

    /** Строки запросов для окна учётки. */
    static ListTag rows(PdaStore store, Account me) {
        ListTag l = new ListTag();
        Map<Integer, Offer> m = offers.get(me.id);
        if (m == null) return l;
        long now = System.currentTimeMillis();
        for (Offer o : m.values()) {
            Account a = store.account(o.from);
            if (o.declined || o.until <= now || a == null) continue;
            CompoundTag t = Views.account(store, a, null);
            t.putString("name", Views.nameFor(store, me, a.id));
            t.putBoolean("offer", true);
            l.add(t);
        }
        return l;
    }

    // ---------------------------------------------------------------- срок, выход

    /** Раз в секунду: просроченные — вон; у кого пропал живой запрос — обновить окно и HUD. */
    static void tick() {
        if (offers.isEmpty()) return;
        long now = System.currentTimeMillis();
        Set<Integer> changed = new HashSet<>();
        for (Iterator<Map.Entry<Integer, LinkedHashMap<Integer, Offer>>> it = offers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, LinkedHashMap<Integer, Offer>> e = it.next();
            for (Iterator<Offer> oi = e.getValue().values().iterator(); oi.hasNext(); ) {
                Offer o = oi.next();
                if (o.until > now) continue;
                oi.remove();
                if (!o.declined) changed.add(e.getKey());
            }
            if (e.getValue().isEmpty()) it.remove();
        }
        notifyAccounts(changed);
    }

    /** Игрок вышел: его запросы больше не принять. */
    static void forget(UUID player) {
        Set<Integer> changed = new HashSet<>();
        for (Iterator<Map.Entry<Integer, LinkedHashMap<Integer, Offer>>> it = offers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, LinkedHashMap<Integer, Offer>> e = it.next();
            if (e.getValue().values().removeIf(o -> o.player.equals(player) && !o.declined)) changed.add(e.getKey());
            e.getValue().values().removeIf(o -> o.player.equals(player));
            if (e.getValue().isEmpty()) it.remove();
        }
        notifyAccounts(changed);
    }

    private static void notifyAccounts(Set<Integer> accs) {
        if (accs.isEmpty() || PdaServer.server == null || PdaServer.store == null) return;
        for (ServerPlayer p : PdaServer.server.getPlayerList().getPlayers()) {
            PdaServer.Session s = PdaServer.sessions.get(p.getUUID());
            Integer act = s != null && !s.system ? Integer.valueOf(s.device) : PdaServer.active.get(p.getUUID());
            if (act == null || !accs.contains(act)) continue;
            Account a = PdaServer.store.account(act);
            if (a != null) refresh(p, a);
        }
    }
}
