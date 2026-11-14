package client.nilore.patch;

import asm.patchify.annotation.At;
import asm.patchify.annotation.Inject;
import asm.patchify.annotation.Patch;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import client.nilore.NiloreClient;
import client.nilore.modules.impl.render.Skin;

/**
 * Skin 模块的全部渲染侧注入点 —— {@code AbstractClientPlayer} 的贴图/模型/披风门。
 *
 * <p>所有方法名与描述符都对着官方 1.20.1 映射核对过:
 * <pre>
 * 86:87   ResourceLocation getSkinTextureLocation() → d
 * 92:93   ResourceLocation getCloakTextureLocation() → e
 * 97:97   boolean isElytraLoaded()                 (未注入, 见下)
 * 102:103 ResourceLocation getElytraTextureLocation() → i
 * 121:122 String getModelName()                    → j
 * 59:59   boolean isCapeLoaded()                   → a
 * </pre>
 * desc 不匹配时 patchify 会静默不生效, 这些字符串必须与真实签名逐字一致。</p>
 *
 * <p>注意 isCapeLoaded(): 原版 CapeLayer 以它为渲染门。自定义披风必须同时把它顶成
 * true, 否则贴图改了也永远不画; 反过来 Hide Others Capes 把它压成 false 即可整层隐身。</p>
 */
@Patch(AbstractClientPlayer.class)
public class AbstractClientPlayerPatch {

    @Inject(
            method = "getSkinTextureLocation",
            desc = "()Lnet/minecraft/resources/ResourceLocation;",
            at = @At(At.Type.HEAD)
    )
    public static void onGetSkinTextureLocation(AbstractClientPlayer player, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            return;
        }
        ResourceLocation override = skin.skinOverrideFor(player);
        if (override != null) {
            callbackInfo.result = override;
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "getCloakTextureLocation",
            desc = "()Lnet/minecraft/resources/ResourceLocation;",
            at = @At(At.Type.HEAD)
    )
    public static void onGetCloakTextureLocation(AbstractClientPlayer player, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            return;
        }
        ResourceLocation override = skin.capeOverrideFor(player);
        if (override != null) {
            callbackInfo.result = override;
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "getElytraTextureLocation",
            desc = "()Lnet/minecraft/resources/ResourceLocation;",
            at = @At(At.Type.HEAD)
    )
    public static void onGetElytraTextureLocation(AbstractClientPlayer player, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            return;
        }
        ResourceLocation override = skin.elytraOverrideFor(player);
        if (override != null) {
            callbackInfo.result = override;
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "getModelName",
            desc = "()Ljava/lang/String;",
            at = @At(At.Type.HEAD)
    )
    public static void onGetModelName(AbstractClientPlayer player, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            return;
        }
        String override = skin.modelOverrideFor(player);
        if (override != null) {
            callbackInfo.result = override;
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "isCapeLoaded",
            desc = "()Z",
            at = @At(At.Type.HEAD)
    )
    public static void onIsCapeLoaded(AbstractClientPlayer player, CallbackInfo callbackInfo) {
        if (!NiloreClient.isReady()) {
            return;
        }
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            return;
        }
        Boolean override = skin.capeLoadedOverrideFor(player);
        if (override != null) {
            callbackInfo.result = override;
            callbackInfo.cancel();
        }
    }
}
