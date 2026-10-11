package faygolover.rppda;

import com.mojang.logging.LogUtils;
import faygolover.rpcore.api.RpCoreAPI;
import faygolover.rppda.config.PdaCommonConfig;
import faygolover.rppda.config.PdaConfig;
import faygolover.rppda.item.ModItems;
import faygolover.rppda.network.Net;
import faygolover.rppda.server.ChatIntercept;
import faygolover.rppda.server.PdaServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.crafting.CraftingHelper;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * RP PDA — КПК для семейства RP-модов (план — {@code docs/00_plan.md}): игровой мессенджер вместо чата и
 * {@code /tell}. Учётки, личные диалоги, группы и каналы хранятся на сервере в {@code <мир>/rppda/}; в предмете —
 * только ID учётки и номер выдачи. Требует только RP Core; Curios — по желанию (свой слот).
 */
@Mod(RpPda.MODID)
public class RpPda {
    public static final String MODID = "rppda";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RpPda() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PdaConfig.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PdaCommonConfig.SPEC);
        ModItems.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::onConfig);
        modBus.addListener(this::onConfigReload);
        CraftingHelper.register(PdaCommonConfig.CuriosSlotCondition.SERIALIZER);

        var bus = MinecraftForge.EVENT_BUS;
        PdaServer.registerEvents(bus);
        ChatIntercept.registerEvents(bus);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> faygolover.rppda.client.ClientSetup.init(modBus));
    }

    private void commonSetup(FMLCommonSetupEvent e) {
        Net.register();
        e.enqueueWork(() -> RpCoreAPI.registerModule(MODID, "КПК: мессенджер вместо чата и /tell, группы, каналы"));
    }

    private void onConfig(ModConfigEvent.Loading e) {
        if (e.getConfig().getSpec() == PdaConfig.SPEC) PdaConfig.bake();
    }

    private void onConfigReload(ModConfigEvent.Reloading e) {
        if (e.getConfig().getSpec() == PdaConfig.SPEC) PdaConfig.bake();
    }
}
