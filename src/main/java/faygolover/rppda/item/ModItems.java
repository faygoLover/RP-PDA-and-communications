package faygolover.rppda.item;

import faygolover.rppda.RpPda;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Предметы и звук мода. */
public final class ModItems {
    private ModItems() {}

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, RpPda.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, RpPda.MODID);

    public static final RegistryObject<Item> PDA = ITEMS.register("pda", () -> new PdaItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> SYSTEM_PDA = ITEMS.register("system_pda", () -> new SystemPdaItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> LOCKPICK = ITEMS.register("lockpick", () -> new LockpickItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> DECRYPTOR = ITEMS.register("decryptor", () -> new DecryptorItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<SoundEvent> NOTIFY = SOUNDS.register("notify",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(RpPda.MODID, "notify")));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(ModItems::tabs);
    }

    private static void tabs(BuildCreativeModeTabContentsEvent e) {
        if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            e.accept(PDA);
            e.accept(SYSTEM_PDA);
            e.accept(LOCKPICK);
            e.accept(DECRYPTOR);
        }
        if (e.getTabKey() == CreativeModeTabs.OP_BLOCKS && e.hasPermissions()) e.accept(SYSTEM_PDA);
    }
}
