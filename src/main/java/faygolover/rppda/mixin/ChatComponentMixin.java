package faygolover.rppda.mixin;

import faygolover.rppda.client.ClientPda;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Голубой фон у строк КПК в чате игры. В {@code render} строка берётся из списка до заливки фона: запоминаем её
 * и красим первую заливку (фон строки), если у строки метка {@link ClientPda#TAG}. Обе вставки не заменяют
 * чужие — с модами чата (Chat Notify и т. п.) уживаются.
 */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Unique
    private GuiMessage.Line rppda$line;

    @ModifyVariable(method = "render", at = @At("STORE"), ordinal = 0)
    private GuiMessage.Line rppda$captureLine(GuiMessage.Line line) {
        rppda$line = line;
        return line;
    }

    @ModifyArg(method = "render", index = 4,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
    private int rppda$tint(int color) {
        GuiMessage.Line line = rppda$line;
        if (line == null || line.tag() != ClientPda.TAG && line.tag() != ClientPda.XFER_TAG) return color;
        int alpha = (color >>> 24) & 255;
        if (alpha == 0) return color;
        alpha = Math.min(255, alpha + 40);
        return (alpha << 24) | 0x1C4F80;
    }
}
