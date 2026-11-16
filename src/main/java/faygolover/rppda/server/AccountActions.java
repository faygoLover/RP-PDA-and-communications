package faygolover.rppda.server;

import static faygolover.rppda.server.Actions.*;
import static faygolover.rppda.server.LookActions.*;
import static faygolover.rppda.server.GroupActions.*;
import static faygolover.rppda.server.SystemActions.*;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.util.Passwords;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Операции входа: регистрация аккаунта (игрока и NPC), разблокировка и проверка пароля.
 * Выделено из {@link Actions}, который остаётся диспетчером по имени операции.
 */
public final class AccountActions {
    private AccountActions() {}

    // ---------------------------------------------------------------- регистрация и пароль

    static void register(ServerPlayer sp, PdaStore store) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) {
            PdaServer.close(sp);
            return;
        }
        String name = PdaServer.displayName(sp);
        Account a = store.createAccount(Account.Kind.PLAYER, name, sp.getUUID());
        // Вид, что ГМ задал новому КПК, переходит в учётку.
        a.look = PdaItem.look(stack);
        PdaItem.bind(stack, a.id, a.issue, a.name);
        PdaItem.setLook(stack, a.look);
        PdaServer.flushPending(sp, a);
        store.setLastAccount(sp.getUUID(), a.id);
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
        toast(sp, "rppda.toast.registered", false, PdaItem.formatId(a.id));
    }

    /** ГМ регистрирует новый КПК сразу на NPC-учётку (владельца нет). */
    static void registerNpc(ServerPlayer sp, PdaStore store, String name) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (!PdaServer.isGm(sp) || s == null || s.mode != PdaServer.Session.Mode.REGISTER) return;
        if (name.isEmpty() || name.length() > TITLE_MAX) {
            toast(sp, "rppda.err.title", true);
            return;
        }
        ItemStack stack = sp.getItemInHand(s.hand);
        if (!Devices.isPda(stack) || PdaItem.account(stack) != 0) {
            PdaServer.close(sp);
            return;
        }
        Account a = store.createAccount(Account.Kind.NPC, name, null);
        a.look = PdaItem.look(stack);
        PdaItem.bind(stack, a.id, a.issue, a.name);
        PdaItem.setLook(stack, a.look);
        store.gmLog(sp.getGameProfile().getName(), "npc", label(a));
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
        toast(sp, "rppda.toast.registered", false, PdaItem.formatId(a.id));
    }

    static void unlock(ServerPlayer sp, PdaStore store, String pw) {
        PdaServer.Session s = PdaServer.sessions.get(sp.getUUID());
        if (s == null || s.mode != PdaServer.Session.Mode.LOCKED) return;
        Account a = store.account(s.device);
        if (a == null || !Devices.holds(sp, a)) {
            PdaServer.close(sp);
            return;
        }
        if (a.burned) return;
        if (!a.locked || !a.hasPassword()) {
            a.locked = false;
            PdaServer.open(sp, a);
            return;
        }
        if (!checkPassword(sp, a, pw)) return;
        a.locked = false;
        a.pwLen = pw.length();
        if (sp.getUUID().equals(a.owner)) a.hackNotice = 0;
        store.markAccounts();
        PdaServer.holders.put(a.id, sp.getUUID());
        PdaServer.open(sp, a);
        PdaServer.scan(sp);
    }

    /** Проверить пароль с учётом пауз после неверных попыток. */
    static boolean checkPassword(ServerPlayer sp, Account a, String pw) {
        long wait = PdaServer.lockoutLeft(sp);
        if (wait > 0) {
            toast(sp, "rppda.err.wait", true, Long.toString((wait + 999) / 1000));
            return false;
        }
        if (Passwords.check(pw, a.pwSalt, a.pwHash)) {
            PdaServer.failed.remove(sp.getUUID());
            return true;
        }
        int[] f = PdaServer.failed.computeIfAbsent(sp.getUUID(), k -> new int[1]);
        f[0]++;
        if (f[0] >= PdaConfig.passwordAttempts) {
            f[0] = 0;
            PdaServer.lockoutUntil.put(sp.getUUID(), System.currentTimeMillis() + PdaConfig.lockoutSeconds * 1000L);
            toast(sp, "rppda.err.wait", true, Integer.toString(PdaConfig.lockoutSeconds));
        } else toast(sp, "rppda.err.password", true);
        return false;
    }
}
