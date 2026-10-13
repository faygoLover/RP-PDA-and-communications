package faygolover.rppda.item;

import faygolover.rppda.RpPda;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Предметы и звук мода. */
public final class ModItems {
    private ModItems() {}

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, RpPda.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, RpPda.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RpPda.MODID);

    public static final RegistryObject<Item> PDA = ITEMS.register("pda", () -> new PdaItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> SYSTEM_PDA = ITEMS.register("system_pda", () -> new SystemPdaItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> LOCKPICK = ITEMS.register("lockpick", () -> new LockpickItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> DECRYPTOR = ITEMS.register("decryptor", () -> new DecryptorItem(new Item.Properties().stacksTo(1)));
    /** Платы памяти для ретро-КПК: 2, 4, 8 и 16 КБ. */
    public static final RegistryObject<Item> MEMORY_2 = board(2), MEMORY_4 = board(4), MEMORY_8 = board(8), MEMORY_16 = board(16);
    public static final RegistryObject<SoundEvent> NOTIFY = SOUNDS.register("notify",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(RpPda.MODID, "notify")));

    /** Своя вкладка творческого меню — все предметы мода. */
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.rppda"))
            .icon(() -> PDA.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(PDA.get());
                out.accept(SYSTEM_PDA.get());
                out.accept(LOCKPICK.get());
                out.accept(DECRYPTOR.get());
                out.accept(MEMORY_2.get());
                out.accept(MEMORY_4.get());
                out.accept(MEMORY_8.get());
                out.accept(MEMORY_16.get());
            })
            .build());

    /** Плата памяти на {@code kb} КБ (воздух, если такой нет). */
    public static Item memory(int kb) {
        return switch (kb) {
            case 2 -> MEMORY_2.get();
            case 4 -> MEMORY_4.get();
            case 8 -> MEMORY_8.get();
            case 16 -> MEMORY_16.get();
            default -> net.minecraft.world.item.Items.AIR;
        };
    }

    private static RegistryObject<Item> board(int kb) {
        return ITEMS.register("memory_" + kb, () -> new MemoryBoardItem(new Item.Properties().stacksTo(16), kb));
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }
}
