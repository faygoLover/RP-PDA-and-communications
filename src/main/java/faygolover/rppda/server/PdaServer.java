package faygolover.rppda.server;

import faygolover.rppda.RpPda;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.Chat;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.item.SystemPdaItem;
import faygolover.rppda.network.Net;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Состояние КПК на сервере: хранилище, открытые окна (сессии), активный КПК каждого игрока, разблокированные КПК
 * с паролем и их держатели. Раз в {@code check_interval_ticks} у каждого игрока в сети смотрится, какой КПК
 * активный (рука → вторая рука → слот → ячейки), и не ушёл ли разблокированный КПК с паролем — это несколько
 * десятков сравнений на игрока.
 */
public final class PdaServer {
    private PdaServer() {}

    @Nullable
    static PdaStore store;
    @Nullable
    static MinecraftServer server;
    static final Map<UUID, Session> sessions = new HashMap<>();
    /** Игрок → активная учётка. */
    static final Map<UUID, Integer> active = new HashMap<>();
    /** Разблокированная учётка с паролем → кто её держит. */
    static final Map<Integer, UUID> holders = new HashMap<>();
    static final Map<UUID, int[]> failed = new HashMap<>();
    static final Map<UUID, Long> lockoutUntil = new HashMap<>();
    static ExecutorService io;
    private static long ticks;

    /** Открытое окно КПК. */
    public static final class Session {
        public enum Mode { REGISTER, LOCKED, OPEN, HACK }

        Mode mode;
        boolean system;
        /** Учётка устройства (у системного — 0). */
        int device;
        /** От какой учётки работает окно (у системного — выбранная «Сменить аккаунт»). */
        int acting;
        InteractionHand hand = InteractionHand.MAIN_HAND;
        int openChat = -1;

        boolean gmSwitched() {
            return system && acting != 0;
        }
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PdaServer::onStarting);
        bus.addListener(PdaServer::onStopping);
        bus.addListener(PdaServer::onStopped);
        bus.addListener(PdaServer::onSave);
        bus.addListener(PdaServer::onTick);
        bus.addListener(PdaServer::onLogin);
        bus.addListener(PdaServer::onLogout);
        bus.addListener(PdaServer::onDeath);
        bus.addListener(PdaServer::onToss);
    }

    @Nullable
    public static PdaStore store() {
        return store;
    }

    // ---------------------------------------------------------------- жизненный цикл

    private static void onStarting(ServerStartingEvent e) {
        server = e.getServer();
        store = new PdaStore(server.getWorldPath(LevelResource.ROOT).resolve("rppda"), RpPda.LOGGER);
        store.load();
        // После перезапуска никто не держит КПК — все с паролем заблокированы.
        for (Account a : store.accounts.values()) {
            if (a.hasPassword() && !a.locked) {
                a.locked = true;
                store.markAccounts();
            }
        }
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "RP PDA archive");
            t.setDaemon(true);
            return t;
        });
    }

    private static void onStopping(ServerStoppingEvent e) {
        if (store != null) {
            for (Integer acc : new ArrayList<>(holders.keySet())) lock(acc);
            store.archiveOld();
            store.save();
        }
    }

    private static void onStopped(ServerStoppedEvent e) {
        store = null;
        server = null;
        sessions.clear();
        Hack.games.clear();
        Cipher.games.clear();
        active.clear();
        holders.clear();
        failed.clear();
        lockoutUntil.clear();
        Messaging.reset();
        Nearby.reset();
        AvatarPics.reset();
        if (io != null) io.shutdown();
        io = null;
    }

    private static void onSave(LevelEvent.Save e) {
        if (store == null || !(e.getLevel() instanceof ServerLevel sl) || sl.dimension() != ServerLevel.OVERWORLD) return;
        store.archiveOld();
        store.save();
    }

    private static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || store == null || server == null) return;
        ticks++;
        if (ticks % PdaConfig.checkTicks == 0) for (ServerPlayer sp : server.getPlayerList().getPlayers()) scan(sp);
        if (ticks % PdaConfig.nameTicks == 0) refreshNames();
        if (ticks % 20 == 0) Nearby.tick();
        if (ticks % (PdaConfig.archiveMinutes * 1200L) == 0) {
            int moved = store.archiveOld();
            if (moved > 0) RpPda.LOGGER.info("RP PDA: в архив ушло кусков: {}", moved);
            store.unloadIdle(15 * 60_000L);
            store.save();
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (store != null && e.getEntity() instanceof ServerPlayer sp) {
            refreshNames(sp);
            scan(sp);
            Messaging.sendUnread(sp);
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        UUID id = e.getEntity().getUUID();
        lockHeldBy(id);
        sessions.remove(id);
        Hack.end(e.getEntity() instanceof ServerPlayer sp ? sp : null);
        Cipher.end(id);
        active.remove(id);
        failed.remove(id);
        Nearby.forget(id);
        AvatarPics.forget(id);
    }

    private static void onDeath(LivingDeathEvent e) {
        if (store != null && e.getEntity() instanceof ServerPlayer sp) {
            lockHeldBy(sp.getUUID());
            if (sessions.get(sp.getUUID()) != null && sessions.get(sp.getUUID()).mode == Session.Mode.HACK) close(sp);
        }
    }

    private static void onToss(ItemTossEvent e) {
        if (store == null) return;
        ItemStack s = e.getEntity().getItem();
        if (!Devices.isPda(s)) return;
        Account a = store.account(PdaItem.account(s));
        if (a != null && a.hasPassword() && !a.locked && PdaItem.issue(s) == a.issue) lock(a.id);
    }

    // ---------------------------------------------------------------- проверки

    /** Активный КПК игрока и уход разблокированных КПК с паролем. */
    public static void scan(ServerPlayer sp) {
        if (store == null) return;
        UUID id = sp.getUUID();
        Set<Integer> present = new HashSet<>();
        Account first = null;
        for (ItemStack s : Devices.all(sp)) {
            Devices.Status st = Devices.status(s, store);
            if (st == Devices.Status.NEW || st == Devices.Status.DISABLED || st == Devices.Status.DELETED || st == Devices.Status.BURNED) continue;
            Account a = store.account(PdaItem.account(s));
            if (a == null) continue;
            present.add(a.id);
            PdaItem.refreshName(s, a.name);
            PdaItem.refreshLook(s, a.look);
            if (st == Devices.Status.OK) {
                if (first == null) first = a;
                // Разблокированный КПК с паролем без держателя (например, ГМ сбросил блокировку) — держит этот игрок.
                if (a.hasPassword() && !holders.containsKey(a.id)) holders.put(a.id, id);
            }
        }
        for (Iterator<Map.Entry<Integer, UUID>> it = holders.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, UUID> h = it.next();
            if (h.getValue().equals(id) && !present.contains(h.getKey())) {
                it.remove();
                lock(h.getKey());
            }
        }
        Integer now = first == null ? null : first.id;
        Integer old = now == null ? active.remove(id) : active.put(id, now);
        if (now != null) store.setLastAccount(id, now);
        if (!java.util.Objects.equals(old, now)) Messaging.sendUnread(sp);

        Session s = sessions.get(id);
        if (s != null && s.mode == Session.Mode.HACK) {
            Hack.Game g = Hack.games.get(id);
            if (g == null || !Hack.stillHolding(sp, g) || faygolover.rpcore.api.RpCoreAPI.unconscious(sp)) {
                Hack.end(sp);
                close(sp);
            }
        }
        if (s != null && s.mode == Session.Mode.OPEN) {
            boolean ok = s.system ? isGm(sp) && Devices.hasSystem(sp) : present.contains(s.device) && !lockedAcc(s.device);
            // Без сознания КПК не держат (системный — инструмент ГМа, ему можно).
            if (!s.system && faygolover.rpcore.api.RpCoreAPI.unconscious(sp)) ok = false;
            if (!ok) close(sp);
        }
    }

    private static boolean lockedAcc(int acc) {
        Account a = store == null ? null : store.account(acc);
        return a == null || a.locked;
    }

    static void lockHeldBy(UUID player) {
        List<Integer> mine = new ArrayList<>();
        holders.forEach((acc, who) -> {
            if (who.equals(player)) mine.add(acc);
        });
        for (int acc : mine) lock(acc);
    }

    /** Заблокировать учётку: окна с ней закрываются (кроме ГМов через «Сменить аккаунт»). */
    static void lock(int acc) {
        holders.remove(acc);
        if (store == null) return;
        Account a = store.account(acc);
        if (a == null || !a.hasPassword()) return;
        a.locked = true;
        store.markAccounts();
        if (server == null) return;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Session s = sessions.get(sp.getUUID());
            if (s != null && !s.system && s.device == acc) close(sp);
            Integer act = active.get(sp.getUUID());
            if (act != null && act == acc) {
                active.remove(sp.getUUID());
                Messaging.sendUnread(sp);
            }
        }
    }

    static void close(ServerPlayer sp) {
        sessions.remove(sp.getUUID());
        Hack.end(sp);
        Cipher.end(sp.getUUID());
        Net.send(sp, "close", new CompoundTag());
    }

    private static void refreshNames() {
        if (server == null || store == null) return;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) refreshNames(sp);
    }

    /** Имя учёток игрока = его отображаемое имя (fakename и похожие моды подставляют своё). */
    static void refreshNames(ServerPlayer sp) {
        if (store == null) return;
        String name = displayName(sp);
        for (Account a : store.accounts.values()) {
            if (a.kind == Account.Kind.PLAYER && sp.getUUID().equals(a.owner) && !name.equals(a.name)) {
                a.name = name;
                store.markAccounts();
            }
        }
    }

    public static String displayName(ServerPlayer sp) {
        return ForgeEventFactory.getPlayerDisplayName(sp, sp.getName()).getString();
    }

    /**
     * Админ «в игре»: видит общий чат и чат игроков без КПК, пишет {@code /tell} без КПК. В «режиме персонажа»
     * мод считает его обычным игроком (права ГМа — {@link #isGm} — при этом остаются).
     */
    public static boolean isAdmin(ServerPlayer sp) {
        return isGm(sp) && (store == null || !store.characterMode.contains(sp.getUUID()));
    }

    /** Права ГМа: системный КПК, внешний вид, связь, команды {@code /rppda}. */
    public static boolean isGm(ServerPlayer sp) {
        return sp.hasPermissions(PdaConfig.adminLevel);
    }

    // ---------------------------------------------------------------- связь

    /** Есть ли связь у КПК этой учётки в руках у {@code holder} (null — держатель не важен). */
    public static boolean signal(@Nullable ServerPlayer holder, Account a) {
        if (a.noSignal) return false;
        return holder == null || store == null || !store.noSignalPlayers.containsKey(holder.getUUID());
    }

    /** С какого момента сообщения не видны (связь пропала); {@code Long.MAX_VALUE} — связь есть. */
    public static long signalCut(@Nullable ServerPlayer holder, Account a) {
        long cut = Long.MAX_VALUE;
        if (a.noSignal) cut = a.noSignalSince;
        if (holder != null && store != null) {
            Long p = store.noSignalPlayers.get(holder.getUUID());
            if (p != null) cut = Math.min(cut, p);
        }
        return cut;
    }

    /** Диалог с Системой работает и без связи — на экстренный случай. */
    public static boolean emergency(Chat c) {
        return c.type == Chat.Type.DM && c.has(0);
    }

    /** Связь вернулась или пропала — у кого в руках эта учётка (или у этого игрока): обновить окно и значок. */
    static void signalChanged(@Nullable Integer acc, @Nullable UUID player) {
        if (server == null || store == null) return;
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            Session s = sessions.get(sp.getUUID());
            Integer act = active.get(sp.getUUID());
            boolean mine = player != null && player.equals(sp.getUUID())
                    || acc != null && (act != null && act.equals(acc) || s != null && !s.system && s.device == acc);
            if (!mine) continue;
            if (s != null && s.mode == Session.Mode.OPEN && !s.system) Views.sendState(sp, s);
            Messaging.sendUnread(sp);
            Account a = act == null ? null : store.account(act);
            if (a != null && signal(sp, a) && Messaging.unreadTotal(store, a, Long.MAX_VALUE) > 0) Messaging.ping(sp, a);
        }
    }

    @Nullable
    public static Integer activeAccount(ServerPlayer sp) {
        return active.get(sp.getUUID());
    }

    /** Куда слать {@code /tell} игроку: активная учётка, иначе последняя, иначе первая зарегистрированная. */
    @Nullable
    public static Account accountFor(UUID player) {
        if (store == null) return null;
        Integer a = active.get(player);
        if (a != null && store.account(a) != null) return store.account(a);
        Integer last = store.lastAccount.get(player);
        if (last != null && store.account(last) != null) return store.account(last);
        List<Account> own = store.accountsOf(player);
        return own.isEmpty() ? null : own.get(0);
    }

    // ---------------------------------------------------------------- открыть КПК

    /** ПКМ с КПК в руке: именно этот КПК (заблокированный — спросит пароль, новый — регистрацию). */
    public static void openFromHand(ServerPlayer sp, InteractionHand hand) {
        if (store == null) return;
        ItemStack stack = sp.getItemInHand(hand);
        if (stack.getItem() instanceof SystemPdaItem) {
            openSystem(sp);
            return;
        }
        if (faygolover.rpcore.api.RpCoreAPI.unconscious(sp)) {
            actionbar(sp, "rppda.err.unconscious");
            return;
        }
        if (!Devices.isPda(stack)) return;
        switch (Devices.status(stack, store)) {
            case NEW -> {
                Session s = new Session();
                s.mode = Session.Mode.REGISTER;
                s.hand = hand;
                sessions.put(sp.getUUID(), s);
                sendRegister(sp, stack);
            }
            case DISABLED -> actionbar(sp, "rppda.msg.disabled");
            case DELETED -> actionbar(sp, "rppda.msg.deleted");
            case BURNED -> {
                Session s = new Session();
                s.mode = Session.Mode.LOCKED;
                s.device = PdaItem.account(stack);
                s.hand = hand;
                sessions.put(sp.getUUID(), s);
                Account a = store.account(s.device);
                CompoundTag t = new CompoundTag();
                t.putInt("id", s.device);
                t.putString("name", a == null ? "" : a.name);
                t.putBoolean("burned", true);
                t.putString("style", PdaConfig.style);
                if (a != null) t.put("look", a.look.save());
                Net.send(sp, "locked", t);
            }
            case LOCKED -> {
                Account a = store.account(PdaItem.account(stack));
                Session s = new Session();
                s.mode = Session.Mode.LOCKED;
                s.device = a.id;
                s.hand = hand;
                sessions.put(sp.getUUID(), s);
                CompoundTag t = new CompoundTag();
                t.putInt("id", a.id);
                t.putString("name", a.name);
                t.putLong("wait", lockoutLeft(sp));
                t.putString("style", PdaConfig.style);
                t.put("look", a.look.save());
                // Владельцу — был ли взлом, пока КПК был не у него.
                if (a.hackNotice > 0 && sp.getUUID().equals(a.owner)) {
                    t.putInt("hack", a.hackNotice);
                    t.putLong("hackTime", a.hackTime);
                }
                Net.send(sp, "locked", t);
            }
            case OK -> open(sp, store.account(PdaItem.account(stack)));
        }
    }

    /** Горячая клавиша: активный КПК; нет его — системный (у админа); иначе подсказка. */
    public static void openHotkey(ServerPlayer sp) {
        if (store == null) return;
        Account a = faygolover.rpcore.api.RpCoreAPI.unconscious(sp) ? null : Devices.active(sp, store);
        if (a != null) {
            open(sp, a);
            return;
        }
        if (isGm(sp) && Devices.hasSystem(sp)) {
            openSystem(sp);
            return;
        }
        boolean locked = false, fresh = false;
        for (ItemStack s : Devices.all(sp)) {
            Devices.Status st = Devices.status(s, store);
            if (st == Devices.Status.LOCKED) locked = true;
            if (st == Devices.Status.NEW) fresh = true;
        }
        actionbar(sp, locked ? "rppda.msg.locked_take" : fresh ? "rppda.msg.new_take" : "rppda.msg.no_pda");
    }

    static void open(ServerPlayer sp, Account a) {
        Session s = new Session();
        s.mode = Session.Mode.OPEN;
        s.device = a.id;
        s.acting = a.id;
        sessions.put(sp.getUUID(), s);
        if (a.hasPassword() && !holders.containsKey(a.id)) holders.put(a.id, sp.getUUID());
        Views.sendState(sp, s);
    }

    static void openSystem(ServerPlayer sp) {
        if (!isGm(sp)) {
            actionbar(sp, "rppda.msg.system_denied");
            return;
        }
        Session old = sessions.get(sp.getUUID());
        Session s = new Session();
        s.mode = Session.Mode.OPEN;
        s.system = true;
        s.acting = old != null && old.system ? old.acting : 0;
        if (store.account(s.acting) == null) s.acting = 0;
        sessions.put(sp.getUUID(), s);
        Views.sendState(sp, s);
    }

    /** Экран регистрации нового КПК: вид из предмета; ГМу — значок «Внешний вид». */
    static void sendRegister(ServerPlayer sp, ItemStack stack) {
        CompoundTag t = new CompoundTag();
        t.putString("style", PdaConfig.style);
        t.put("look", PdaItem.look(stack).save());
        t.putBoolean("gm", isGm(sp));
        Net.send(sp, "register", t);
    }

    static void actionbar(ServerPlayer sp, String key, Object... args) {
        sp.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GRAY), true);
    }

    static long lockoutLeft(ServerPlayer sp) {
        Long until = lockoutUntil.get(sp.getUUID());
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    /** Первый КПК игрока: сообщения админам, написанные без учётки, переезжают в его диалоги. */
    static void flushPending(ServerPlayer sp, Account acc) {
        if (store == null) return;
        var q = store.pending.remove(sp.getUUID());
        if (q == null || q.isEmpty()) return;
        store.markMisc();
        for (PdaStore.Pending p : q) {
            Account admin = accountFor(p.admin());
            Chat c = store.dm(acc.id, admin == null ? 0 : admin.id);
            store.append(c, acc.id, p.text(), 0, 0, null, p.time());
        }
    }
}
