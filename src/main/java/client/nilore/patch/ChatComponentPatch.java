package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import client.nilore.ClientBase;
import client.nilore.modules.impl.render.BetterChat;

@Patch(ChatComponent.class)
public class ChatComponentPatch {
    @Inject(method = "render", desc = "(Lnet/minecraft/client/gui/GuiGraphics;III)V", at = @At(At.Type.HEAD))
    public static void onRender(ChatComponent chat, GuiGraphics graphics, int tickCount, int mouseX, int mouseY, CallbackInfo callbackInfo) {
        BetterChat betterChat = BetterChat.INSTANCE;
        if (betterChat == null || !betterChat.replacesChatText()) {
            return;
        }
        try {
            callbackInfo.cancel();
            betterChat.renderChat(graphics, chat, tickCount);
        } catch (Throwable throwable) {
            ClientBase.logger.error(throwable.getMessage(), throwable);
        }
    }

    /**
     * Keeps click hit-testing on the shifted lines.
     *
     * <p>Two shifts are applied when the chat is repainted:
     * <ul>
     *   <li>{@code Y Offset} — a screen-space shift that applies to <b>both</b> fonts;</li>
     *   <li>{@code LINE_DROP} — an extra per-line drop that only exists on the custom-font path.</li>
     * </ul>
     *
     * <p>The old implementation bailed out entirely for the Minecraft font, so with the default
     * {@code Y Offset = -60} every line was drawn 60 px higher while hit-testing still used the
     * vanilla coordinates — links were only clickable six rows below where they appeared. The
     * y-offset compensation now runs unconditionally; only the line drop is font-dependent.
     */
    @Inject(method = "screenToChatY", desc = "(D)D", at = @At(At.Type.HEAD))
    public static void onScreenToChatY(ChatComponent chat, double mouseY, CallbackInfo callbackInfo) {
        BetterChat betterChat = BetterChat.INSTANCE;
        if (betterChat == null || !betterChat.replacesChatText()) {
            return;
        }
        if (ClientBase.mc.options == null || ClientBase.mc.getWindow() == null) {
            return;
        }
        double scale = chat.getScale();
        int lineHeight = (int) (9.0D * (ClientBase.mc.options.chatLineSpacing().get() + 1.0D));
        if (scale <= 0.0D || lineHeight <= 0) {
            return;
        }
        int guiHeight = ClientBase.mc.getWindow().getGuiScaledHeight();
        double lineSpacing = ClientBase.mc.options.chatLineSpacing().get();
        double baselineOffset = Math.round(-8.0D * (lineSpacing + 1.0D) + 4.0D * lineSpacing);
        double adjusted = mouseY - betterChat.textYOffset() - baselineOffset * scale;
        if (!betterChat.usesMinecraftFont()) {
            adjusted -= BetterChat.LINE_DROP * scale;
        }
        callbackInfo.cancel();
        callbackInfo.result = ((double) guiHeight - adjusted - 40.0D) / (scale * (double) lineHeight);
    }
}
