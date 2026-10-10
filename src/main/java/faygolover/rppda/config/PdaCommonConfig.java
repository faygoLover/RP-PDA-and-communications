package faygolover.rppda.config;

import com.google.gson.JsonObject;
import faygolover.rppda.RpPda;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.common.crafting.conditions.IConditionSerializer;

/**
 * Общий конфиг ({@code config/rppda-common.toml}): то, что нужно до загрузки мира. Слот Curios описан в датапаке
 * мода с условием {@code rppda:curios_slot} — Curios читает его при загрузке данных, серверный конфиг к этому
 * времени ещё не загружен.
 */
public final class PdaCommonConfig {
    private PdaCommonConfig() {}

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue CURIOS_SLOT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        CURIOS_SLOT = b.comment("Свой слот Curios «КПК» (нужен Curios). Выключить — слота не будет, КПК носят в инвентаре.",
                        "Own Curios slot for the PDA (needs Curios).")
                .define("curios_slot", true);
        SPEC = b.build();
    }

    public static boolean curiosSlot() {
        try {
            return CURIOS_SLOT.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Условие датапака: слот Curios включён в конфиге. */
    public record CuriosSlotCondition() implements ICondition {
        public static final ResourceLocation ID = new ResourceLocation(RpPda.MODID, "curios_slot");
        public static final IConditionSerializer<CuriosSlotCondition> SERIALIZER = new IConditionSerializer<>() {
            @Override
            public void write(JsonObject json, CuriosSlotCondition value) {}

            @Override
            public CuriosSlotCondition read(JsonObject json) {
                return new CuriosSlotCondition();
            }

            @Override
            public ResourceLocation getID() {
                return ID;
            }
        };

        @Override
        public ResourceLocation getID() {
            return ID;
        }

        @Override
        public boolean test(IContext context) {
            return curiosSlot();
        }
    }
}
