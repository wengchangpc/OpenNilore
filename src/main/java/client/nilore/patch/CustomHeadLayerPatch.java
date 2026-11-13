package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.world.entity.LivingEntity;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.FirstPersonModel;

/**
 * FirstPersonModel 的配套: 第一人称补画自己时, 头戴物 (南瓜 / 头颅 / 头盔道具) 也不画。
 *
 * <p>和 {@link HumanoidArmorLayerPatch} 是同一类问题: {@code CustomHeadLayer} 挂在
 * {@code PlayerRenderer} 的 layer 链上, 位置正好是头部, 但用的是它自己的 skull model,
 * 与 {@code PlayerModel.head.visible} 无关。戴着南瓜开第一人称, 视野就是一个南瓜内壁。</p>
 */
@Patch(CustomHeadLayer.class)
public class CustomHeadLayerPatch {
    @Inject(
            method = "render",
            desc = "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(At.Type.HEAD)
    )
    public static void onRender(
            CustomHeadLayer<?, ?> layer,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            LivingEntity entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float partialTick,
            CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        if (FirstPersonModel.skipOwnHeadLayers(entity)) {
            callbackInfo.cancel();
        }
    }
}
