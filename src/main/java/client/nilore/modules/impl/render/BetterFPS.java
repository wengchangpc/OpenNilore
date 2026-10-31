package client.nilore.modules.impl.render;

import net.minecraft.client.renderer.RenderType;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.math.FastMath;

public class BetterFPS extends Module {
    public static BetterFPS INSTANCE;

    private final BooleanSetting fastMath = new BooleanSetting("Fast Math", true);
    private final BooleanSetting distanceLod = new BooleanSetting("Distance LOD", false);
    private final NumberSetting lodDistance = new NumberSetting("LOD Distance", 96, 32, 256, 8,
            () -> (Boolean) this.distanceLod.getValue());

    private static final double LEAVES_FACTOR = 1.6;

    public BetterFPS() {
        super("BetterFPS", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.syncFastMath();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.syncFastMath();
        super.onDisable();
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.syncFastMath();
    }

    private void syncFastMath() {
        FastMath.setEnabled(this.isEnabled() && (Boolean) this.fastMath.getValue());
    }

    public boolean shouldSkipLayer(RenderType renderType, double dx, double dz) {
        if (!this.isEnabled() || !(Boolean) this.distanceLod.getValue()) {
            return false;
        }
        boolean cutout = renderType == RenderType.cutout();
        boolean leaves = renderType == RenderType.cutoutMipped();
        if (!cutout && !leaves) {
            return false;
        }
        double limit = this.lodDistance.getValue().doubleValue();
        double distanceSq = dx * dx + dz * dz;
        if (distanceSq <= limit * limit) {
            return false;
        }
        if (cutout) {
            return true;
        }
        double leavesLimit = limit * LEAVES_FACTOR;
        return distanceSq > leavesLimit * leavesLimit;
    }

    @Override
    public String getSuffix() {
        if (!this.isEnabled() || !(Boolean) this.distanceLod.getValue()) {
            return null;
        }
        return Integer.toString(this.lodDistance.getValue().intValue());
    }
}
