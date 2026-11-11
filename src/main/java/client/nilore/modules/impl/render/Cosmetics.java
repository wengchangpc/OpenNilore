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
 * Cosmetics —— 程序化装饰系统: 猫耳 / 光环 / 皇冠 / 墨镜 / 围巾 / 尾巴。
 *
 * <h2>管线</h2>
 * <p>与 {@link Cape}/{@link Wings} 同一套: RenderEvent (renderLevel 尾部) 里用
 * 一个 BufferBuilder、一次 drawWithShader 画完所有玩家身上启用的全部部件,
 * POSITION_COLOR 面片, 不加载任何纹理。</p>
 *
 * <h2>坐标</h2>
 * <p>局部系约定与 Cape 相同: 先 translate(实体位置 - 相机), 再
 * mulPose(Y轴, -bodyYaw) 后 +Z 为实体正前方、-Z 为背后。头部件再做两步:
 * 绕头枢轴 (y=1.5) 附加 yaw 差 (yHeadRot - yBodyRot) 与俯仰 pitch —— 和原版
 * PlayerModel 的 head.yRot / head.xRot 同一数学, 所以转头/抬头时耳朵和墨镜
 * 跟着动。头方块占 y∈[1.5,2.0]、x/z∈[-0.25,0.25], 部件尺寸全部以此为基准。</p>
 *
 * <h2>反作弊与性能</h2>
 * <p>纯本地渲染, 零发包零实体数据改动, 别人看到的你依然素颜。隐身玩家不画
 * (和 Cape 同规矩: 不能拿装饰当标记); 超出 Cull Distance 跳过; 全程零对象分配
 * (几何直接写顶点缓冲)。围巾垂带与尾巴随时间正弦摆动, 相位对所有玩家共用,
 * 同服多人各自相位由位置差自然错开。</p>
 */
public class Cosmetics extends Module {
    private static final int RING_SEGMENTS = 16;
    private static final int NECK_SEGMENTS = 14;

    public final BooleanSetting catEars = new BooleanSetting("Cat Ears", true);
    public final BooleanSetting halo = new BooleanSetting("Halo", false);
    public final BooleanSetting crown = new BooleanSetting("Crown", false);
    public final BooleanSetting glasses = new BooleanSetting("Glasses", false);
    public final BooleanSetting scarf = new BooleanSetting("Scarf", false);
    public final BooleanSetting tail = new BooleanSetting("Tail", false);

    public final ModeSetting colorMode = new ModeSetting("Color", "Theme", "Custom", "Rainbow")
            .withDefault("Theme");
    public final NumberSetting red = new NumberSetting("Red", 255, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting green = new NumberSetting("Green", 216, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting blue = new NumberSetting("Blue", 74, 0, 255, 1, () -> this.colorMode.is("Custom"));
    public final NumberSetting rainbowSpeed = new NumberSetting("Rainbow Speed", 6, 2, 30, 1,
            () -> this.colorMode.is("Rainbow"));

    public final BooleanSetting others = new BooleanSetting("Others", true);
    public final NumberSetting cullDistance = new NumberSetting("Cull Distance", 64, 8, 128, 4);
    public final NumberSetting swayAmount = new NumberSetting("Sway Amount", 1.0, 0.0, 2.5, 0.05);
    public final NumberSetting waveSpeed = new NumberSetting("Wave Speed", 4.0, 0.5, 16.0, 0.5);

    public Cosmetics() {
        super("Cosmetics", Category.RENDER);
    }

    @Override
    public String getSuffix() {
        int count = this.enabledPartCount();
        return count == 0 ? null : count + (count == 1 ? " part" : " parts");
    }

    private int enabledPartCount() {
        return (this.catEars.getValue() ? 1 : 0) + (this.halo.getValue() ? 1 : 0)
                + (this.crown.getValue() ? 1 : 0) + (this.glasses.getValue() ? 1 : 0)
                + (this.scarf.getValue() ? 1 : 0) + (this.tail.getValue() ? 1 : 0);
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (mc.level == null || mc.player == null || mc.gameRenderer == null) {
            return;
        }
        if (this.enabledPartCount() == 0) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null) {
            return;
        }

        boolean renderOthers = this.others.getValue();
        double cullSq = Math.pow(this.cullDistance.getValue().doubleValue(), 2.0);
        boolean firstPerson = mc.options.getCameraType().isFirstPerson();
        boolean fpmActive = FirstPersonModel.INSTANCE != null && FirstPersonModel.INSTANCE.isEnabled();

        double phase = (System.currentTimeMillis() % 100_000L) / 1000.0
                * this.waveSpeed.getValue().doubleValue();
        float sway = this.swayAmount.getValue().floatValue();
        int themeTint = this.resolveThemeColor();
        PoseStack poseStack = event.poseStack();
        Vec3 cameraPos = camera.getPosition();
        float partialTick = event.partialTick();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (AbstractClientPlayer player : mc.level.players()) {
            boolean self = player == mc.player;
            if (self && firstPerson && !fpmActive) {
                // 第一人称看不到自己的头/身体 (FPM 关时), 部件悬空没有意义。
                continue;
            }
            if (!self && !renderOthers) {
                continue;
            }
            if (!self && player.distanceToSqr(mc.player) > cullSq) {
                continue;
            }
            if (player.isInvisible() || player.isDeadOrDying()) {
                continue;
            }
            // FPM 藏头模式下自己的头部件同样悬空 —— 只画身体部件 (围巾/尾巴)。
            boolean drawHeadParts = !(self && firstPerson && fpmActive && FirstPersonModel.INSTANCE.hideHead.getValue());

            double x = Mth.lerp(partialTick, player.xOld, player.getX());
            double y = Mth.lerp(partialTick, player.yOld, player.getY());
            double z = Mth.lerp(partialTick, player.zOld, player.getZ());
            float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
            float headYaw = Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
            float pitch = Mth.lerp(partialTick, player.xRotO, player.getXRot());
            if (player.isCrouching()) {
                y -= 0.25;  // 潜行时身体下沉, 头部枢轴同步下移。
            }

            poseStack.pushPose();
            poseStack.translate(x - cameraPos.x, y - cameraPos.y, z - cameraPos.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(-bodyYaw));
            Matrix4f body = poseStack.last().pose();

            poseStack.pushPose();
            poseStack.translate(0.0f, 1.5f, 0.0f);
            poseStack.mulPose(Axis.YP.rotationDegrees(-(headYaw - bodyYaw)));
            poseStack.mulPose(Axis.XP.rotationDegrees(pitch));
            poseStack.translate(0.0f, -1.5f, 0.0f);
            Matrix4f head = poseStack.last().pose();

            if (drawHeadParts) {
                if (this.catEars.getValue()) {
                    this.drawCatEars(buffer, head, themeTint);
                }
                if (this.halo.getValue()) {
                    this.drawHalo(buffer, head, themeTint, phase);
                }
                if (this.crown.getValue()) {
                    this.drawCrown(buffer, head, themeTint);
                }
                if (this.glasses.getValue()) {
                    this.drawGlasses(buffer, head);
                }
            }
            if (this.scarf.getValue()) {
                this.drawScarf(buffer, body, themeTint, phase, sway);
            }
            if (this.tail.getValue()) {
                this.drawTail(buffer, body, themeTint, phase, sway);
            }

            poseStack.popPose();
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

    // ================= 部件几何 =================

    /** 两只三角锥耳朵: 底面省略 (贴头皮看不见), 四个侧面, 朝脸的正面加亮当内耳。 */
    private void drawCatEars(BufferBuilder buffer, Matrix4f head, int themeTint) {
        int bright = this.colorAt(0.15f, 1.0f, themeTint);
        int shade = ColorUtil.withAlpha(shadeColor(this.colorAt(0.4f, 0.95f, themeTint), 0.78f), 0.95f);
        for (int side = -1; side <= 1; side += 2) {
            float cx = 0.16f * side;
            float cz = 0.01f;
            // 底面四角 (y = 1.97), 前方 = +Z: 前左/前右/后右/后左。
            float x0 = cx - 0.055f, z0 = cz + 0.065f;
            float x1 = cx + 0.055f, z1 = cz + 0.065f;
            float x2 = cx + 0.055f, z2 = cz - 0.065f;
            float x3 = cx - 0.055f, z3 = cz - 0.065f;
            float baseY = 1.97f;
            float apexX = cx + 0.03f * side;
            float apexY = 2.22f;
            float apexZ = cz + 0.01f;
            this.triEdge(buffer, head, x0, baseY, z0, x1, baseY, z1, apexX, apexY, apexZ, bright);
            this.triEdge(buffer, head, x1, baseY, z1, x2, baseY, z2, apexX, apexY, apexZ, shade);
            this.triEdge(buffer, head, x2, baseY, z2, x3, baseY, z3, apexX, apexY, apexZ, shade);
            this.triEdge(buffer, head, x3, baseY, z3, x0, baseY, z0, apexX, apexY, apexZ, shade);
        }
    }

    /** 光环: 水平圆环带 + 微浮动。 */
    private void drawHalo(BufferBuilder buffer, Matrix4f head, int themeTint, double phase) {
        int color = this.colorAt(0.0f, 0.8f, themeTint);
        int dark = ColorUtil.withAlpha(shadeColor(color, 0.75f), 0.8f);
        float cy = 2.08f + 0.02f * Mth.sin((float) (phase * 0.7));
        float outer = 0.30f;
        float inner = 0.245f;
        float height = 0.035f;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / RING_SEGMENTS);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / RING_SEGMENTS);
            float ox0 = Mth.cos(a0) * outer, oz0 = Mth.sin(a0) * outer;
            float ox1 = Mth.cos(a1) * outer, oz1 = Mth.sin(a1) * outer;
            float ix0 = Mth.cos(a0) * inner, iz0 = Mth.sin(a0) * inner;
            float ix1 = Mth.cos(a1) * inner, iz1 = Mth.sin(a1) * inner;
            // 顶面
            this.quad(buffer, head,
                    ox0, cy + height, oz0, ox1, cy + height, oz1,
                    ix1, cy + height, iz1, ix0, cy + height, iz0, color);
            // 底面
            this.quad(buffer, head,
                    ox0, cy, oz0, ix0, cy, iz0, ix1, cy, iz1, ox1, cy, oz1, dark);
            // 外壁
            this.quad(buffer, head,
                    ox0, cy, oz0, ox1, cy, oz1, ox1, cy + height, oz1, ox0, cy + height, oz0, color);
        }
    }

    /** 皇冠: 头顶一圈 8 尖刺 + 底环。 */
    private void drawCrown(BufferBuilder buffer, Matrix4f head, int themeTint) {
        int color = this.colorAt(0.1f, 0.95f, themeTint);
        int dark = ColorUtil.withAlpha(shadeColor(color, 0.8f), 0.95f);
        float baseR = 0.265f;
        float apexR = 0.275f;
        float baseY = 2.0f;
        float apexY = 2.23f;
        for (int i = 0; i < 8; i++) {
            float a = (float) (i * Math.PI / 4.0);
            float halfArc = 0.14f;
            float a0 = a - halfArc;
            float a1 = a + halfArc;
            // 尖刺两面 (关剔除后同一三角双向可见, 颜色一深一亮造立体感)
            this.triEdge(buffer, head,
                    Mth.cos(a0) * baseR, baseY, Mth.sin(a0) * baseR,
                    Mth.cos(a1) * baseR, baseY, Mth.sin(a1) * baseR,
                    Mth.cos(a) * apexR, apexY, Mth.sin(a) * apexR, color);
            this.triEdge(buffer, head,
                    Mth.cos(a1) * baseR, baseY, Mth.sin(a1) * baseR,
                    Mth.cos(a0) * baseR, baseY, Mth.sin(a0) * baseR,
                    Mth.cos(a) * apexR, apexY, Mth.sin(a) * apexR, dark);
        }
        // 底环外壁
        float r0 = 0.245f;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / RING_SEGMENTS);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / RING_SEGMENTS);
            this.quad(buffer, head,
                    Mth.cos(a0) * r0, baseY - 0.03f, Mth.sin(a0) * r0,
                    Mth.cos(a1) * r0, baseY - 0.03f, Mth.sin(a1) * r0,
                    Mth.cos(a1) * baseR, baseY, Mth.sin(a1) * baseR,
                    Mth.cos(a0) * baseR, baseY, Mth.sin(a0) * baseR, color);
        }
    }

    /** 墨镜: 前脸横带 + 两镜片 + 两侧镜腿。固定深色, 不吃主题色。 */
    private void drawGlasses(BufferBuilder buffer, Matrix4f head) {
        int frame = 0xEE232830;
        int lens = 0x66BFD9FF;
        // 镜框横带 (脸前 0.263)
        this.quad(buffer, head,
                -0.26f, 1.77f, 0.263f, 0.26f, 1.77f, 0.263f,
                0.26f, 1.88f, 0.263f, -0.26f, 1.88f, 0.263f, frame);
        // 两镜片 (略前 0.264, 半透蓝)
        this.quad(buffer, head,
                -0.235f, 1.775f, 0.264f, -0.05f, 1.775f, 0.264f,
                -0.05f, 1.875f, 0.264f, -0.235f, 1.875f, 0.264f, lens);
        this.quad(buffer, head,
                0.05f, 1.775f, 0.264f, 0.235f, 1.775f, 0.264f,
                0.235f, 1.875f, 0.264f, 0.05f, 1.875f, 0.264f, lens);
        // 镜腿 (两侧细条)
        this.quad(buffer, head,
                -0.255f, 1.80f, 0.26f, -0.255f, 1.80f, -0.22f,
                -0.255f, 1.84f, -0.22f, -0.255f, 1.84f, 0.26f, frame);
        this.quad(buffer, head,
                0.255f, 1.80f, 0.26f, 0.255f, 1.80f, -0.22f,
                0.255f, 1.84f, -0.22f, 0.255f, 1.84f, 0.26f, frame);
    }

    /** 围巾: 脖圈 + 正面垂带 (随相位摆动)。 */
    private void drawScarf(BufferBuilder buffer, Matrix4f body, int themeTint, double phase, float sway) {
        int color = this.colorAt(0.55f, 0.95f, themeTint);
        int dark = ColorUtil.withAlpha(shadeColor(color, 0.78f), 0.95f);
        // 脖圈外壁
        float r = 0.155f;
        for (int i = 0; i < NECK_SEGMENTS; i++) {
            float a0 = (float) (i * Math.PI * 2.0 / NECK_SEGMENTS);
            float a1 = (float) ((i + 1) * Math.PI * 2.0 / NECK_SEGMENTS);
            this.quad(buffer, body,
                    Mth.cos(a0) * r, 1.37f, Mth.sin(a0) * r,
                    Mth.cos(a1) * r, 1.37f, Mth.sin(a1) * r,
                    Mth.cos(a1) * r, 1.51f, Mth.sin(a1) * r,
                    Mth.cos(a0) * r, 1.51f, Mth.sin(a0) * r,
                    i < NECK_SEGMENTS / 2 ? color : dark);
        }
        // 正面垂带: 6 段, 左右轻摆
        float halfW = 0.055f;
        float z = 0.185f;
        for (int i = 0; i < 6; i++) {
            float t0 = i / 6.0f;
            float t1 = (i + 1) / 6.0f;
            float y0 = 1.47f - t0 * 0.68f;
            float y1 = 1.47f - t1 * 0.68f;
            float xOff = Mth.sin((float) phase + t1 * 3.5f) * 0.04f * sway * (t1 + 0.2f);
            float z0 = z + t0 * 0.06f;
            float z1 = z + t1 * 0.06f;
            int c = ColorUtil.withAlpha(shadeColor(color, 1.0f - 0.09f * i), 0.95f);
            this.quad(buffer, body,
                    0.10f - halfW + xOff * 0.3f, y0, z0,
                    0.10f + halfW + xOff * 0.3f, y0, z0,
                    0.10f + halfW + xOff, y1, z1,
                    0.10f - halfW + xOff, y1, z1, c);
        }
    }

    /** 尾巴: 腰后伸出, 向上卷, 每段十字交叉双面片 (任意角度可见)。 */
    private void drawTail(BufferBuilder buffer, Matrix4f body, int themeTint, double phase, float sway) {
        int segments = 8;
        float rootX = 0.0f, rootY = 0.75f, rootZ = -0.17f;
        float prevX = rootX, prevY = rootY, prevZ = rootZ;
        for (int i = 0; i < segments; i++) {
            float t = (i + 1) / (float) segments;
            float angleDeg = 14.0f + i * 9.0f;              // 逐段抬高
            float rad = (float) Math.toRadians(angleDeg);
            float length = 0.165f;
            float dx = 0.0f;
            float dy = Mth.sin(rad) * length;
            float dz = -Mth.cos(rad) * length;
            float lateral = Mth.sin((float) (phase * 1.15f) + i * 0.55f) * 0.055f * sway * (t + 0.15f);
            float x = rootX + dx + lateral;
            float y = rootY + dy;
            float z = rootZ + dz;
            float halfW = 0.085f * (1.0f - 0.75f * t) + 0.015f;
            int c = ColorUtil.withAlpha(shadeColor(this.colorAt(0.75f, 0.95f, themeTint), 1.0f - 0.07f * i), 0.95f);
            // 垂直面 (XY 平面) 片
            this.quad(buffer, body,
                    prevX, prevY - halfW, prevZ, x, y - halfW, z,
                    x, y + halfW, z, prevX, prevY + halfW, prevZ, c);
            // 水平面 (XZ 平面) 片
            this.quad(buffer, body,
                    prevX - halfW, prevY, prevZ, prevX + halfW, prevY, prevZ,
                    x + halfW, y, z, x - halfW, y, z, c);
            prevX = x;
            prevY = y;
            prevZ = z;
        }
    }

    // ================= 顶点辅助 =================

    private void quad(BufferBuilder buffer, Matrix4f matrix,
                      float x1, float y1, float z1, float x2, float y2, float z2,
                      float x3, float y3, float z3, float x4, float y4, float z4, int color) {
        buffer.vertex(matrix, x1, y1, z1).color(color).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(color).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(color).endVertex();
        buffer.vertex(matrix, x4, y4, z4).color(color).endVertex();
    }

    /** 单三角 (QUADS 模式下用退化第四点凑一个面元)。 */
    private void triEdge(BufferBuilder buffer, Matrix4f matrix,
                         float x1, float y1, float z1, float x2, float y2, float z2,
                         float x3, float y3, float z3, int color) {
        buffer.vertex(matrix, x1, y1, z1).color(color).endVertex();
        buffer.vertex(matrix, x2, y2, z2).color(color).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(color).endVertex();
        buffer.vertex(matrix, x3, y3, z3).color(color).endVertex();
    }

    // ================= 颜色 =================

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
        return ColorUtil.withAlpha(base, alphaScale);
    }

    private static int shadeColor(int color, float factor) {
        return ColorUtil.fromRGB(
                Math.round(ColorUtil.getRed(color) * factor),
                Math.round(ColorUtil.getGreen(color) * factor),
                Math.round(ColorUtil.getBlue(color) * factor));
    }

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
        }
        return 0xFFFFD24A;
    }
}
