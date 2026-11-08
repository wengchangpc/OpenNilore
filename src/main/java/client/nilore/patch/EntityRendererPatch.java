package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.BetterFPS;
import client.nilore.modules.impl.render.NameTags;

@Patch(EntityRenderer.class)
public class EntityRendererPatch {
    @Inject(
            method = "renderNameTag",
            desc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRenderNameTag(EntityRenderer<?> renderer, Entity entity, Component component, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        // NameTags 自己接管名字牌时, 原版这一个整个不画。
        if (entity instanceof LivingEntity && NameTags.INSTANCE != null && NameTags.INSTANCE.isEnabled()) {
            callbackInfo.cancel();
            return;
        }
        // 否则只做距离/背面剔除。名字牌比实体本身还贵: 每个都是一次批量文字构建加一次
        // batch flush, 人多的服务器上几十个名字牌能吃掉可观的一截帧时间。
        BetterFPS betterFPS = BetterFPS.INSTANCE;
        if (betterFPS != null && betterFPS.shouldCullNameTag(entity)) {
            callbackInfo.cancel();
        }
    }
}
