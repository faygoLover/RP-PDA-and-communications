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
            })
            .build());

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        TABS.register(modBus);
    }
}
