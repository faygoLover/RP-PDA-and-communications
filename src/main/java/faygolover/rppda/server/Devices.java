package faygolover.rppda.server;

import faygolover.rppda.compat.CuriosCompat;
import faygolover.rppda.data.Account;
import faygolover.rppda.data.PdaStore;
import faygolover.rppda.item.PdaItem;
import faygolover.rppda.item.SystemPdaItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Где у игрока КПК и какой из них активный. */
public final class Devices {
    private Devices() {}

    public enum Status { NEW, OK, LOCKED, DISABLED, DELETED, BURNED }

    /**
     * Все КПК игрока в порядке выбора активного: основная рука → вторая рука → слот Curios «КПК» (и другие слоты
     * Curios) → ячейки инвентаря по возрастанию номера.
     */
    public static List<ItemStack> all(ServerPlayer sp) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = sp.getInventory();
        ItemStack main = inv.getSelected();
        if (isPda(main)) out.add(main);
        ItemStack off = inv.offhand.get(0);
        if (isPda(off)) out.add(off);
        out.addAll(CuriosCompat.stacks(sp, Devices::isPda));
        for (int i = 0; i < inv.items.size(); i++) {
            if (i == inv.selected) continue;
            ItemStack s = inv.items.get(i);
            if (isPda(s)) out.add(s);
        }
        return out;
    }

    /** Счётчик на HUD — по КПК в слоте Curios «КПК» (если Curios стоит и слот включён). */
    public static boolean slotMode() {
        return CuriosCompat.loaded() && faygolover.rppda.config.PdaCommonConfig.curiosSlot();
    }

    /** Учётка рабочего КПК в слоте Curios «КПК» или null. */
    @Nullable
    public static Integer slotAccount(ServerPlayer sp, PdaStore store) {
        for (ItemStack s : CuriosCompat.slotStacks(sp)) {
            if (isPda(s) && status(s, store) == Status.OK) return PdaItem.account(s);
        }
        return null;
    }

    public static boolean isPda(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof PdaItem;
    }

    public static boolean hasSystem(ServerPlayer sp) {
        Inventory inv = sp.getInventory();
        for (ItemStack s : inv.items) if (s.getItem() instanceof SystemPdaItem) return true;
        if (inv.offhand.get(0).getItem() instanceof SystemPdaItem) return true;
        return !CuriosCompat.stacks(sp, s -> s.getItem() instanceof SystemPdaItem).isEmpty();
    }

    public static Status status(ItemStack s, PdaStore store) {
        int acc = PdaItem.account(s);
        if (acc <= 0) return Status.NEW;
        Account any = store.accountAny(acc);
        if (any != null && any.deleted) return Status.DELETED;
        Account a = store.account(acc);
        if (a == null || a.kind == Account.Kind.SYSTEM || PdaItem.issue(s) != a.issue) return Status.DISABLED;
        if (a.burned) return Status.BURNED;
        return a.locked ? Status.LOCKED : Status.OK;
    }

    /** Активный КПК: первый рабочий (не заблокированный, не отключённый) по порядку {@link #all}. */
    @Nullable
    public static Account active(ServerPlayer sp, PdaStore store) {
        for (ItemStack s : all(sp)) if (status(s, store) == Status.OK) return store.account(PdaItem.account(s));
        return null;
    }

    /** Есть ли у игрока рабочий экземпляр этой учётки (заблокирован или нет). */
    public static boolean holds(ServerPlayer sp, Account a) {
        for (ItemStack s : all(sp)) if (PdaItem.account(s) == a.id && PdaItem.issue(s) == a.issue) return true;
        return false;
    }
}
