package faygolover.rppda.compat;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Curios (по желанию): КПК в слотах. Классы Curios трогаются только через {@link #stacks}, когда мод стоит. */
public final class CuriosCompat {
    private CuriosCompat() {}

    private static final boolean LOADED = ModList.get().isLoaded("curios");

    public static boolean loaded() {
        return LOADED;
    }

    /** Предметы только в своём слоте «pda». */
    public static List<ItemStack> slotStacks(ServerPlayer sp) {
        if (!LOADED) return List.of();
        return Impl.slot(sp);
    }

    /** КПК в слотах Curios: сначала свой слот «pda», потом остальные. */
    public static List<ItemStack> stacks(ServerPlayer sp, Predicate<ItemStack> filter) {
        if (!LOADED) return List.of();
        return Impl.stacks(sp, filter);
    }

    private static final class Impl {
        static List<ItemStack> slot(ServerPlayer sp) {
            List<ItemStack> out = new ArrayList<>();
            CuriosApi.getCuriosInventory(sp).ifPresent(inv -> inv.getStacksHandler("pda").ifPresent(h -> {
                for (int i = 0; i < h.getSlots(); i++) {
                    ItemStack s = h.getStacks().getStackInSlot(i);
                    if (!s.isEmpty()) out.add(s);
                }
            }));
            return out;
        }

        static List<ItemStack> stacks(ServerPlayer sp, Predicate<ItemStack> filter) {
            List<ItemStack> own = new ArrayList<>(), other = new ArrayList<>();
            CuriosApi.getCuriosInventory(sp).ifPresent(inv -> {
                for (SlotResult r : inv.findCurios(filter)) {
                    if ("pda".equals(r.slotContext().identifier())) own.add(r.stack());
                    else other.add(r.stack());
                }
            });
            own.addAll(other);
            return own;
        }
    }
}
