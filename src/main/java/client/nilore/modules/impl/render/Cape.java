package client.nilore.modules.impl.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.RenderEvent;
import client.nilore.hud.ModuleListHud;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.utils.render.ColorUtil;

/**
 * Cape — 程序化披风。
 *
 * <h2>反作弊</h2>
 * <p>纯客户端渲染, 一个包都不发, 不改实体状态, 不动碰撞箱 —— 服务端唯一能观测到的差别是你截图。
 * 但有三处"看不见的规矩"必须守, 否则就成了变相透视:
 * <ul>
 *   <li><b>隐身玩家不画。</b>给隐身目标挂一条披风等于把他标出来, 位置信息本来就不该由装饰性模块提供。</li>
 *   <li><b>默认不穿墙。</b>穿墙渲染是 ESP 的形态, {@code Through Walls} 要手动开, 默认关。</li>
 *   <li><b>不和原版披风叠。</b>原版披风层没有可用的补丁钩子, 硬画会 z-fighting。所以默认跳过已经
 *       有披风的玩家; 打开 {@code Replace Vanilla} 时把自己往后推 0.045, 让自绘的盖在原版外面。</li>
 * </ul>
 *
 * <h2>性能</h2>
 * <p>所有玩家共用**一个** BufferBuilder、一次 {@code drawWithShader}, 不是每人一次 draw call。
 * 形状用一组预分配的 float 数组按行复用 ({@link #rowY} / {@link #rowZ} / {@link #rowHalf} /
 * {@link #rowColor}), 每帧零对象分配; 整条披风就是 {@code COLS × rows} 个四边形直接写进顶点缓冲,
 * 不走任何中间几何对象。距离超出 {@code Cull Distance} 直接跳过。
 *
 * <h2>坐标</h2>
 * <p>和 {@link Wings} 同一套约定: 传进来的 PoseStack 是已转好相机、但没做相机平移的视角矩阵,
 * 所以世界坐标要先减 {@code cameraPosition}; 之后 {@code mulPose(Axis.YP.rotationDegrees(-bodyYaw))}
 * 得到的局部坐标系里 {@code +Z} 是实体正前方, {@code -Z} 是背后, {@code +Y} 向上。披风挂在 -Z 侧。
 */
public class Cape extends Module {
    /** 网格列数 (横向格子数, 顶点数 = COLS + 1)。 */
    private static final int COLS = 5;
    /** 预分配的最大行数, 各款式取用其中一段。 */
    private static final int MAX_ROWS = 14;

    /** 挂点高度 (玩家身高 1.8, 肩线约 1.44)。 */
    private static final float TOP_Y = 1.44f;
    /** 挂点离身体背面的距离, 略大于身体半厚 0.125。 */
    private static final float TOP_Z = -0.135f;
    /** 原版披风就在 -0.125 附近, 覆盖它时再往后推这么多。 */
    private static final float VANILLA_OVERLAP_PUSH = 0.045f;

    public final ModeSetting style = new ModeSetting("Style", "Classic", "Long", "Flag", "Ribbon")
            .withDefault("Classic");
    public final ModeSetting colorMode = new ModeSetting("Color", "Theme", "Custom", "Rainbow")
            .withDefault("Theme");

    public final NumberSetting red = new NumberSetting("Red", 60, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting green = new NumberSetting("Green", 140, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting blue = new NumberSetting("Blue", 235, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting rainbowSpeed = new NumberSetting("Rainbow Speed", 6, 2, 30, 1,
            () -> this.colorMode.is("Rainbow"));

    public final BooleanSetting gradient = new BooleanSetting("Gradient", true);
    public final NumberSetting gradientShade = new NumberSetting("Gradient Shade", 0.55, 0.0, 1.0, 0.05,
            () -> this.gradient.getValue());

    public final NumberSetting width = new NumberSetting("Width", 1.0, 0.5, 1.8, 0.05);
    public final NumberSetting length = new NumberSetting("Length", 1.0, 0.4, 1.8, 0.05);
    public final NumberSetting alpha = new NumberSetting("Alpha", 0.92, 0.05, 1.0, 0.01);

    public final BooleanSetting sway = new BooleanSetting("Sway", true);
    public final NumberSetting swayAmount = new NumberSetting("Sway Amount", 1.0, 0.0, 2.5, 0.05,
            () -> this.sway.getValue());
    public final NumberSetting waveSpeed = new NumberSetting("Wave Speed", 4.0, 0.5, 16.0, 0.5,
            () -> this.sway.getValue());

    public final BooleanSetting others = new BooleanSetting("Others", false);
    public final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false);
    public final BooleanSetting skipVanilla = new BooleanSetting("Skip Vanilla Cape", true);
    public final NumberSetting cullDistance = new NumberSetting("Cull Distance", 48, 8, 128, 4);

    // 每帧复用, 零分配。索引 = 行号 (0 = 顶端)。
    private final float[] rowY = new float[MAX_ROWS + 1];
    private final float[] rowZ = new float[MAX_ROWS + 1];
    private final float[] rowHalf = new float[MAX_ROWS + 1];
    private final int[] rowColor = new int[MAX_ROWS + 1];
    /** 横向摆动的传递量 (buildRows 写、emitCape 读), 放字段上避免多造一个对象。 */
    private float lateralAmp;
    private float lateralPhase;

    public Cape() {
        super("Cape", Category.RENDER);
    }

    @Override
    public String getSuffix() {
        return this.style.getValue();
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }
        float alphaValue = this.alpha.getValue().floatValue();
        if (alphaValue <= 0.01f) {
            return;
        }

        // 一次取齐所有设置: 内层循环里反复 getValue() 会走 Number 装箱比较, 纯浪费。
        float widthValue = this.width.getValue().floatValue();
        float lengthValue = this.length.getValue().floatValue();
        boolean doSway = this.sway.getValue();
        float swayValue = this.swayAmount.getValue().floatValue();
        double waveSpeedValue = this.waveSpeed.getValue().doubleValue();
        boolean renderOthers = this.others.getValue();
        boolean keepVanilla = this.skipVanilla.getValue();
        double cullSq = Math.pow(this.cullDistance.getValue().doubleValue(), 2.0);
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();

        int rows = this.rowsForStyle();
        float halfTop = 0.26f * widthValue;
        float hangLength = 1.05f * lengthValue * this.lengthFactorForStyle();
        double phase = (System.currentTimeMillis() % 100_000L) / 1000.0 * waveSpeedValue;

        PoseStack poseStack = event.poseStack();
        Vec3 cameraPos = camera.getPosition();
        int themeTint = this.resolveThemeColor();
        float partialTick = event.partialTick();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 关剔除: 一块单面四边形从侧面看会被剔掉, 披风翻起来时会突然消失。关掉最省事也最稳。
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        if (this.throughWalls.getValue()) {
            RenderSystem.disableDepthTest();
        } else {
            RenderSystem.enableDepthTest();
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (AbstractClientPlayer player : mc.level.players()) {
            if (player != mc.player) {
                if (!renderOthers) {
                    continue;
                }
                if (player.distanceToSqr(mc.player) > cullSq) {
                    continue;
                }
            } else if (firstPerson) {
                // 第一人称看不见自己的背, 白画。
                continue;
            }
            if (player.isInvisible() || player.isDeadOrDying()) {
                continue;
            }

            boolean hasVanilla = player.isCapeLoaded();
            if (hasVanilla && keepVanilla) {
                continue;
            }

            this.buildRows(player, rows, halfTop, hangLength, doSway, swayValue, phase, alphaValue, themeTint);

            double x = Mth.lerp(partialTick, player.xOld, player.getX());
            double y = Mth.lerp(partialTick, player.yOld, player.getY());
            double z = Mth.lerp(partialTick, player.zOld, player.getZ());
            float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
            float push = hasVanilla ? VANILLA_OVERLAP_PUSH : 0.0f;

            poseStack.pushPose();
            poseStack.translate(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
            this.emitCape(buffer, poseStack.last().pose(), rows, push);
            poseStack.popPose();
        }

        BufferBuilder.RenderedBuffer rendered = buffer.end();
        if (rendered != null) {
            BufferUploader.drawWithShader(rendered);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** 按款式算出每一行的 y / z / 半宽 / 颜色, 写进复用数组。 */
    private void buildRows(AbstractClientPlayer player, int rows, float halfTop, float hangLength,
                           boolean doSway, float swayValue, double phase,
                           float alphaValue, int themeTint) {
        // 前倾角: 跑得越快披风掀得越开, 下落时再往上飘一点。
        float lean = 0.0f;
        float topY = TOP_Y;
        if (doSway) {
            Vec3 motion = player.getDeltaMovement();
            double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            lean = (float) Math.min(horizontal * 42.0 * swayValue, 62.0);
            lean += (float) Mth.clamp(-motion.y * 14.0 * swayValue, -18.0, 26.0);
            if (player.isCrouching()) {
                lean += 8.0f;
                topY -= 0.18f;
            }
            if (player.isFallFlying()) {
                lean = 78.0f;
            }
        } else if (player.isCrouching()) {
            topY -= 0.18f;
        }

        float leanRad = (float) Math.toRadians(Mth.clamp(lean, -30.0f, 88.0f));
        float cos = Mth.cos(leanRad);
        float sin = Mth.sin(leanRad);
        // 波浪强度只跟款式和参数有关, 不进循环反复算。
        float waveAmp = doSway ? 0.10f * swayValue : 0.0f;
        float lateral = this.style.is("Flag") || this.style.is("Ribbon") ? 0.05f * swayValue : 0.0f;

        for (int i = 0; i <= rows; i++) {
            float t = i / (float) rows;
            float dropped = hangLength * t;
            this.rowY[i] = topY - dropped * cos;
            this.rowZ[i] = TOP_Z - dropped * sin
                    - waveAmp * Mth.sin(t * 4.712389f + (float) phase) * t;
            this.rowHalf[i] = halfTop * (1.0f - 0.12f * t);
            // 横向摆动写进 rowHalf 会改变宽度, 这里单独用一个斜坡偏移并入 x。
            this.rowColor[i] = this.colorAt(t, alphaValue, themeTint);
        }
        // 横向摆动量放不下在数组里, 用一个成员变量传给 emitCape —— 只有 Flag/Ribbon 非零。
        this.lateralAmp = lateral * hangLength;
        this.lateralPhase = (float) phase;
    }

    /** 把已算好的行数据展开成四边形写进缓冲。 */
    private void emitCape(BufferBuilder buffer, Matrix4f matrix, int rows, float push) {
        float lateral = this.lateralAmp;
        float phase = this.lateralPhase;
        for (int i = 0; i < rows; i++) {
            float t0 = i / (float) rows;
            float t1 = (i + 1) / (float) rows;
            float y0 = this.rowY[i];
            float y1 = this.rowY[i + 1];
            float z0 = this.rowZ[i] - push;
            float z1 = this.rowZ[i + 1] - push;
            float h0 = this.rowHalf[i];
            float h1 = this.rowHalf[i + 1];
            int c0 = this.rowColor[i];
            int c1 = this.rowColor[i + 1];

            // 横向摆动: 顶端几乎不动, 底端摆幅最大, 看起来像被风扯着。
            float off0 = lateral * Mth.sin(t0 * 3.1415927f * 2.0f + phase) * t0;
            float off1 = lateral * Mth.sin(t1 * 3.1415927f * 2.0f + phase) * t1;

            for (int c = 0; c < COLS; c++) {
                float u0 = c / (float) COLS;
                float u1 = (c + 1) / (float) COLS;
                float x00 = -h0 + 2.0f * h0 * u0 + off0;
                float x01 = -h0 + 2.0f * h0 * u1 + off0;
                float x10 = -h1 + 2.0f * h1 * u0 + off1;
                float x11 = -h1 + 2.0f * h1 * u1 + off1;

                buffer.vertex(matrix, x00, y0, z0).color(c0).endVertex();
                buffer.vertex(matrix, x01, y0, z0).color(c0).endVertex();
                buffer.vertex(matrix, x11, y1, z1).color(c1).endVertex();
                buffer.vertex(matrix, x10, y1, z1).color(c1).endVertex();
            }
        }
    }

    private int rowsForStyle() {
        return switch (this.style.getValue()) {
            case "Long" -> 14;
            case "Flag" -> 10;
            case "Ribbon" -> 12;
            default -> 8;
        };
    }

    private float lengthFactorForStyle() {
        return switch (this.style.getValue()) {
            case "Long" -> 1.35f;
            case "Ribbon" -> 1.6f;
            default -> 1.0f;
        };
    }

    /** 上下渐变 + alpha。gradient 打开时底端按 Gradient Shade 压暗。 */
    private int colorAt(float progress, float alphaScale, int themeTint) {
        int base;
        if (this.colorMode.is("Rainbow")) {
            base = ColorUtil.getRainbowColor(this.rainbowSpeed.getValue().intValue(),
                    Math.round(progress * 90.0f)).getRGB();
        } else if (this.colorMode.is("Custom")) {
            base = ColorUtil.fromRGB(this.red.getValue().intValue(),
                    this.green.getValue().intValue(), this.blue.getValue().intValue());
        } else {
            base = themeTint;
        }
        if (this.gradient.getValue()) {
            float shade = 1.0f - this.gradientShade.getValue().floatValue() * progress;
            base = ColorUtil.fromRGB(
                    Math.round(ColorUtil.getRed(base) * shade),
                    Math.round(ColorUtil.getGreen(base) * shade),
                    Math.round(ColorUtil.getBlue(base) * shade));
        }
        return ColorUtil.withAlpha(base, alphaScale);
    }

    /** 跟随 HUD 主题色, 取不到就退回一个中性蓝。 */
    private int resolveThemeColor() {
        try {
            NiloreClient client = NiloreClient.getInstance();
            if (client != null && client.getHudManager() != null) {
                ModuleListHud hud = client.getHudManager().getHudElement(ModuleListHud.class);
                if (hud != null) {
                    return hud.getThemeColor(0, 0.0f, 3);
                }
            }
        } catch (RuntimeException ignored) {
            // HUD 未初始化 —— 用兜底色, 别让渲染线程炸在这里。
        }
        return 0xFF7CC8FF;
    }
}
