package client.nilore.modules.impl.render;

import net.minecraft.world.entity.Entity;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.ModeSetting;

/**
 * SkinGlow —— 皮肤自发光: 实体按满亮度渲染, 黑洞洞的矿洞/夜里也清清楚楚。
 *
 * <h2>实现</h2>
 * <p>挂在 {@code EntityRenderDispatcher.render} 内部对
 * {@code getPackedLightCoords(Entity, float)} 的调用上 (@WrapInvoke):
 * 命中目标时直接返回 0xF000F0 (天空光 15 + 方块光 15 = LightTexture.FULL_BRIGHT),
 * 否则原样放行。这是原版发光浆果/烈焰人光照计算的公共上游, 改这一处,
 * 实体贴图、盔甲、披风全链路一起亮。</p>
 *
 * <h2>反作弊</h2>
 * <p>光照是渲染管线的取色参数, 不进实体数据、不发包。服务端无从得知你把
 * 亮度拉满了 —— 和 FullBright 同一性质, 纯观感。</p>
 */
public class SkinGlow extends Module {
    public static SkinGlow INSTANCE;

    public final ModeSetting target = new ModeSetting("Target", "Off", "Self", "Everyone");

    public SkinGlow() {
        super("SkinGlow", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public String getSuffix() {
        return this.target.getValue();
    }

    /** patch 查询入口: 这个实体要不要满亮度渲染。 */
    public boolean appliesTo(Entity entity) {
        if (!this.isEnabled() || this.target.is("Off") || entity == null) {
            return false;
        }
        if (this.target.is("Self")) {
            return mc.player != null && entity.getUUID().equals(mc.player.getUUID());
        }
        return true;
    }
}
