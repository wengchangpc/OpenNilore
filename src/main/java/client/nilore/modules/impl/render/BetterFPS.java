package client.nilore.modules.impl.render;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.render.terrain.TerrainLodRenderer;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.math.FastMath;
import client.nilore.utils.misc.ReflectionUtil;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;

/**
 * 统一的客户端性能模块。
 *
 * <p>所有会掉帧的开关都收在这一个模块里, 分成四组: 实体剔除、名字牌剔除、环境渲染、粒子。
 * 每组的第一项是总开关, 后面的滑条只在总开关打开时显示。</p>
 *
 * <p>设计原则是**失败即放行**: 任何一项检测算不出来 (反射失败、射线预算耗尽、缓存未命中)
 * 都返回"照常渲染", 宁可少省一点, 也不让实体凭空消失。</p>
 */
public class BetterFPS extends Module {
    public static BetterFPS INSTANCE;

    /** 每帧允许的遮挡射线总数。超预算的实体当帧直接放行。 */
    private static final int OCCLUSION_BUDGET = 48;

    /** 遮挡判定的结果缓存多少帧后作废。相机一直在动, 复用太久会露出本该被挡的实体。 */
    private static final int OCCLUSION_CACHE_FRAMES = 4;

    /** 这个距离以内永远不走遮挡判定。近处剔除是最容易被眼睛抓到的破绽。 */
    private static final double OCCLUSION_MIN_DISTANCE = 12.0;

    private final BooleanSetting fastMath = new BooleanSetting("Fast Math", true);

    private final BooleanSetting terrainLod = new BooleanSetting("Terrain LOD", false);

    private final NumberSetting lodStart = new NumberSetting("LOD Start", 96, 32, 512, 8,
            () -> (Boolean) this.terrainLod.getValue());

    private final NumberSetting lodTier1 = new NumberSetting("LOD Tier 1 End", 144, 48, 768, 8,
            () -> (Boolean) this.terrainLod.getValue());

    private final NumberSetting lodTier2 = new NumberSetting("LOD Tier 2 End", 192, 64, 1024, 8,
            () -> (Boolean) this.terrainLod.getValue());

    private final BooleanSetting entityCull = new BooleanSetting("Entity Cull", true);

    private final NumberSetting entityDistance = new NumberSetting("Entity Distance", 64, 16, 256, 8,
            () -> (Boolean) this.entityCull.getValue());

    private final BooleanSetting entityOcclusion = new BooleanSetting("Entity Occlusion", false,
            () -> (Boolean) this.entityCull.getValue());

    private final BooleanSetting keepPlayers = new BooleanSetting("Keep Players", true,
            () -> (Boolean) this.entityCull.getValue());

    private final BooleanSetting nameTagCull = new BooleanSetting("Name Tag Cull", true);

    private final NumberSetting nameTagDistance = new NumberSetting("Name Tag Distance", 32, 8, 128, 4,
            () -> (Boolean) this.nameTagCull.getValue());

    private final BooleanSetting nameTagBehind = new BooleanSetting("Name Tag Behind", false,
            () -> (Boolean) this.nameTagCull.getValue());

    private final BooleanSetting noSky = new BooleanSetting("No Sky", false);

    private final BooleanSetting noClouds = new BooleanSetting("No Clouds", false);

    private final BooleanSetting noWeather = new BooleanSetting("No Weather", false);

    private final BooleanSetting noParticles = new BooleanSetting("No Particles", false);

    private final NumberSetting particleLimit = new NumberSetting("Particle Limit", 0, 0, 4000, 100,
            () -> !(Boolean) this.noParticles.getValue());

    /** 0 = 未缓存, 1 = 可见, 2 = 被挡。用 byte 省一次装箱。 */
    private final Int2ByteOpenHashMap occlusionCache = new Int2ByteOpenHashMap();

    private int occlusionBudget;

    private int occlusionCacheAge;

    /** 相机本身卡在方块里时遮挡判定会把所有东西都判掉, 这种情况整帧直接停用。 */
    private boolean cameraInBlock;

    private Field particleMapField;

    private boolean particleMapResolved;

    public BetterFPS() {
        super("BetterFPS", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public void onEnable() {
        this.syncFastMath();
        this.resetCaches();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        this.syncFastMath();
        TerrainLodRenderer.reset();
        this.resetCaches();
        super.onDisable();
    }

    private void resetCaches() {
        this.occlusionCache.clear();
        this.occlusionBudget = OCCLUSION_BUDGET;
        this.occlusionCacheAge = 0;
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.syncFastMath();
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        TerrainLodRenderer.reset();
        this.resetCaches();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        // RenderEvent 在 renderLevel 的 TAIL, 也就是本帧实体已经画完之后。
        // 这里补给预算, 供下一帧用。
        this.occlusionBudget = OCCLUSION_BUDGET;
        this.cameraInBlock = this.cameraInsideBlock();
        if (++this.occlusionCacheAge >= OCCLUSION_CACHE_FRAMES) {
            this.occlusionCache.clear();
            this.occlusionCacheAge = 0;
        }

        if (!this.terrainLodEnabled()) {
            return;
        }
        TerrainLodRenderer.render(event.poseStack(), startDistance(), tier1End(), tier2End());
    }

    private void syncFastMath() {
        FastMath.setEnabled(this.isEnabled() && (Boolean) this.fastMath.getValue());
    }

    public boolean terrainLodEnabled() {
        return this.isEnabled() && (Boolean) this.terrainLod.getValue();
    }

    public boolean covers(BlockPos origin, double distanceSq) {
        return this.terrainLodEnabled()
                && TerrainLodRenderer.shouldReplace(origin, distanceSq, startDistance());
    }

    public static double startDistance() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodStart.getValue().doubleValue();
    }

    public static double tier1End() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodTier1.getValue().doubleValue();
    }

    public static double tier2End() {
        return INSTANCE == null ? 0.0 : INSTANCE.lodTier2.getValue().doubleValue();
    }

    // ------------------------------------------------------------------
    // 实体剔除
    // ------------------------------------------------------------------

    /**
     * @param dx 实体插值后的位置减去相机位置 (就是 EntityRenderDispatcher.render 收到的 x)
     * @param dy 同上
     * @param dz 同上
     */
    public boolean shouldCullEntity(Entity entity, double dx, double dy, double dz) {
        if (!this.isEnabled() || entity == null) {
            return false;
        }
        if (!(Boolean) this.entityCull.getValue()) {
            return false;
        }
        if (mc.player == null || mc.level == null) {
            return false;
        }
        // 自己、相机挂载的实体和被标记为 noCulling 的实体一律放行。
        if (entity == mc.player || entity == mc.getCameraEntity() || entity.noCulling) {
            return false;
        }
        if (entity instanceof Player && (Boolean) this.keepPlayers.getValue()) {
            return false;
        }

        double limit = this.entityDistance.getValue().doubleValue();
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > limit * limit) {
            return true;
        }

        if (!(Boolean) this.entityOcclusion.getValue()) {
            return false;
        }
        if (distSq < OCCLUSION_MIN_DISTANCE * OCCLUSION_MIN_DISTANCE) {
            return false;
        }
        return this.isOccluded(entity);
    }

    private boolean isOccluded(Entity entity) {
        if (this.cameraInBlock) {
            return false;
        }
        int id = entity.getId();
        byte cached = this.occlusionCache.get(id);
        if (cached != 0) {
            return cached == 2;
        }
        if (this.occlusionBudget <= 0) {
            return false;
        }
        this.occlusionBudget--;
        boolean occluded = this.raycastOccluded(entity);
        this.occlusionCache.put(id, (byte) (occluded ? 2 : 1));
        return occluded;
    }

    private boolean cameraInsideBlock() {
        if (mc.level == null || mc.gameRenderer == null) {
            return false;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        BlockPos pos = BlockPos.containing(eye);
        return !mc.level.getBlockState(pos).getCollisionShape(mc.level, pos).isEmpty();
    }

    /**
     * 从相机往实体 AABB 上离相机最近的点打一条射线。
     * 打到方块就认为被挡。用最近点而不是中心, 是因为中心被挡但边角露出来的情况很常见。
     */
    private boolean raycastOccluded(Entity entity) {
        if (mc.level == null) {
            return false;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        AABB box = entity.getBoundingBox();
        Vec3 nearest = new Vec3(
                Mth.clamp(eye.x, box.minX, box.maxX),
                Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        if (eye.distanceToSqr(nearest) < 1.0E-4) {
            return false;
        }
        BlockHitResult hit = mc.level.clip(
                new ClipContext(eye, nearest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        return hit.getType() == HitResult.Type.BLOCK;
    }

    // ------------------------------------------------------------------
    // 名字牌剔除
    // ------------------------------------------------------------------

    public boolean shouldCullNameTag(Entity entity) {
        if (!this.isEnabled() || entity == null) {
            return false;
        }
        if (!(Boolean) this.nameTagCull.getValue()) {
            return false;
        }
        if (mc.player == null || mc.gameRenderer == null) {
            return false;
        }
        Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
        double dx = entity.getX() - camPos.x;
        double dy = entity.getY() + entity.getBbHeight() * 0.5 - camPos.y;
        double dz = entity.getZ() - camPos.z;

        double limit = this.nameTagDistance.getValue().doubleValue();
        if (dx * dx + dy * dy + dz * dz > limit * limit) {
            return true;
        }
        if (!(Boolean) this.nameTagBehind.getValue()) {
            return false;
        }
        // 水平面用相机朝向做点积: 负数 = 在背后。
        double yaw = Math.toRadians(mc.gameRenderer.getMainCamera().getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        return dx * forwardX + dz * forwardZ < 0.0;
    }

    // ------------------------------------------------------------------
    // 环境渲染
    // ------------------------------------------------------------------

    public boolean hidesSky() {
        return this.isEnabled() && (Boolean) this.noSky.getValue();
    }

    public boolean hidesClouds() {
        return this.isEnabled() && (Boolean) this.noClouds.getValue();
    }

    public boolean hidesWeather() {
        return this.isEnabled() && (Boolean) this.noWeather.getValue();
    }

    public boolean hidesParticles() {
        return this.isEnabled() && (Boolean) this.noParticles.getValue();
    }

    /**
     * 粒子数量上限。0 表示不限制。
     *
     * <p>计数器来自 ParticleEngine 的 particles 字段 (每个 RenderType 一个 Queue),
     * 求和一次大约十来次 size()。add() 每次生成粒子才调一次, 这个开销可以忽略。</p>
     */
    public boolean rejectsParticle(ParticleEngine engine) {
        if (!this.isEnabled() || engine == null) {
            return false;
        }
        int limit = this.particleLimit.getValue().intValue();
        if (limit <= 0) {
            return false;
        }
        return this.liveParticleCount(engine) >= limit;
    }

    private int liveParticleCount(ParticleEngine engine) {
        Field field = this.particleMapField();
        if (field == null) {
            // 拿不到字段就当没有粒子, 上限永远不生效 —— 放行而不是全部拦住。
            return 0;
        }
        try {
            Object value = field.get(engine);
            if (!(value instanceof Map<?, ?> map)) {
                return 0;
            }
            int total = 0;
            for (Object queue : map.values()) {
                if (queue instanceof Collection<?> collection) {
                    total += collection.size();
                }
            }
            return total;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Field particleMapField() {
        if (this.particleMapResolved) {
            return this.particleMapField;
        }
        this.particleMapResolved = true;
        try {
            String name = ReflectionUtil.getMappedFieldName(ParticleEngine.class, "particles");
            if (name == null) {
                return null;
            }
            Field field = ParticleEngine.class.getDeclaredField(name);
            field.setAccessible(true);
            this.particleMapField = field;
        } catch (Throwable ignored) {
            this.particleMapField = null;
        }
        return this.particleMapField;
    }

    @Override
    public String getSuffix() {
        if (!this.terrainLodEnabled()) {
            return null;
        }
        return Integer.toString(this.lodStart.getValue().intValue());
    }
}
