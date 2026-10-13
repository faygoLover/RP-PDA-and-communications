package faygolover.rppda.mixin;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Строки загрузки файла в чате игры ({@code docs/20_retro_memory.md} §4.1): строка «всегда внизу» — раз в секунду
 * старая убирается и новая добавляется последней. Добавляем без записи в лог игры (иначе лог — по строке в секунду).
 */
@Mixin(ChatComponent.class)
public interface ChatComponentAccess {
    @Accessor("allMessages")
    List<GuiMessage> rppda$all();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> rppda$trimmed();

    @Invoker("addMessage")
    void rppda$add(Component text, @Nullable MessageSignature sig, int addedTime, @Nullable GuiMessageTag tag, boolean onlyTrim);
}
