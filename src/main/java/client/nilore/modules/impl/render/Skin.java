package client.nilore.modules.impl.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.Color;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import client.nilore.NiloreClient;
import client.nilore.event.EventTarget;
import client.nilore.event.impl.TickEvent;
import client.nilore.event.impl.WorldChangeEvent;
import client.nilore.modules.Category;
import client.nilore.modules.Module;
import client.nilore.settings.impl.BooleanSetting;
import client.nilore.settings.impl.ModeSetting;
import client.nilore.settings.impl.NumberSetting;
import client.nilore.settings.impl.StringSetting;

/**
 * Skin —— 自定义皮肤 / 贴图披风 / 模型与"增艳"美化。
 *
 * <h2>原理</h2>
 * <p>所有拦截点都在 {@link AbstractClientPlayer} 的三个贴图 getter 与
 * {@code getModelName()}/{@code isCapeLoaded()} (见 AbstractClientPlayerPatch):
 * <ul>
 *   <li>皮肤: getSkinTextureLocation() — PlayerRenderer 直接拿它绑纹理, 0 个额外绘制调用。</li>
 *   <li>披风: getCloakTextureLocation() + isCapeLoaded() — 原版 CapeLayer 以
 *       isCapeLoaded 为门、贴图为皮, 两个一起改才有真披风。</li>
 *   <li>鞘翅: getElytraTextureLocation() — 披风贴图直接上翅膀 (MinecraftCapes 同款观感)。</li>
 *   <li>模型: getModelName() — EntityRenderDispatcher 用这个字符串在 "default"/"slim"
 *       两个 PlayerRenderer 里选, 返回 "slim" 即细手臂。</li>
 * </ul>
 *
 * <h2>反作弊</h2>
 * <p>纯客户端渲染层, 不发包、不改实体数据、不碰协议。服务端与其他玩家看到的一切照旧
 * —— 别人要看到你的自定义皮肤/披风, 唯一途径是官服换肤 (skinchange 网页/API) 或双方
 * 都装 OptiFine/MinecraftCapes 这类纹理同步 mod。本模块的价值是"你看到的画面"。</p>
 *
 * <h2>纹理加载</h2>
 * <p>PNG 从 {@code ~/.nilore/skins/} 读取, NativeImage 解码后走
 * {@code TextureManager.register(String, DynamicTexture)} —— 原版动态纹理通道,
 * 自动分配不冲突的 ResourceLocation。64x32 老 1.8 皮肤在加载时自动补齐下半张
 * (镜像右腿/右臂到左腿/左臂), 否则现代 64x64 模型 UV 会采样到空白区。</p>
 *
 * <h2>增艳 (Vivid)</h2>
 * <p>对自加载皮肤做像素级 HSB 处理: 饱和度 ×Saturation、亮度 ×Brightness, 在贴图
 * 上传前一次完成, 渲染路径零开销。只影响自定义皮肤, 不碰世界/其他实体。</p>
 */
public class Skin extends Module {
    public static Skin INSTANCE;

    public final ModeSetting skinTarget = new ModeSetting("Skin Target", "Off", "Self", "Everyone");
    /** 文件名 (不含扩展名也行), 放在 ~/.nilore/skins/ 下; 由 .skin 命令管理。 */
    public final StringSetting skinFile = new StringSetting("Skin File", "");
    /** Auto = 按皮肤像素判宽窄 (右臂 54/55 列有没有不透明像素)。 */
    public final ModeSetting model = new ModeSetting("Model", "Auto", "Default", "Slim");

    public final ModeSetting capeTarget = new ModeSetting("Cape Target", "Off", "Self", "Everyone");
    /** 披风 PNG, 宽高比 2:1 (64x32 / 512x256 等均可)。 */
    public final StringSetting capeFile = new StringSetting("Cape File", "");
    /** 披风贴图同时用作鞘翅翅膀贴图 (MinecraftCapes 风格)。 */
    public final BooleanSetting elytraTexture = new BooleanSetting("Elytra Texture", true);
    /** 把别人的披风藏掉 (isCapeLoaded → false)。 */
    public final BooleanSetting hideOtherCapes = new BooleanSetting("Hide Others Capes", false);

    /** 增艳: 加载时对自定义皮肤做饱和度/亮度提升。 */
    public final BooleanSetting vivid = new BooleanSetting("Vivid", true);
    public final NumberSetting saturation = new NumberSetting("Saturation", 130, 50, 300, 5,
            () -> this.vivid.getValue());
    public final NumberSetting brightness = new NumberSetting("Brightness", 105, 50, 200, 5,
            () -> this.vivid.getValue());

    /** 偷皮肤: 填玩家名, 自己的皮肤/模型/披风/鞘翅全部照抄对方 (贴图已注册, 零下载)。 */
    public final StringSetting stealFrom = new StringSetting("Steal From", "");

    private ResourceLocation skinTexture;
    private ResourceLocation capeTexture;
    /** 从皮肤像素判定出的宽窄; 只在 skinTexture 加载成功后有义。 */
    private boolean skinSlim;
    private String attemptedSkinFile;
    private String attemptedCapeFile;
    private boolean skinLoadFailed;
    private boolean capeLoadFailed;
    private int loadDelay;

    public Skin() {
        super("Skin", Category.RENDER);
        INSTANCE = this;
    }

    @Override
    public String getSuffix() {
        if (!this.stealFrom.isBlank()) {
            return "Steal";
        }
        if (this.skinTexture != null) {
            return this.skinTarget.getValue();
        }
        return this.capeTexture != null ? this.capeTarget.getValue() : null;
    }

    @EventTarget
    public void onWorldChange(WorldChangeEvent event) {
        // 贴图与纹理注册跨世界有效, 只清偷皮肤的解析缓存即可; 无需卸载。
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (mc.player == null || mc.getTextureManager() == null) {
            return;
        }
        if (this.loadDelay > 0) {
            this.loadDelay--;
            return;
        }
        this.loadDelay = 10;
        this.tryLoadSkin();
        this.tryLoadCape();
    }

    /** .skin reload / set 时调用: 清状态, 下一拍重新懒加载。 */
    public void reloadTextures() {
        this.skinTexture = null;
        this.capeTexture = null;
        this.attemptedSkinFile = null;
        this.attemptedCapeFile = null;
        this.skinLoadFailed = false;
        this.capeLoadFailed = false;
        this.skinSlim = false;
        this.loadDelay = 0;
    }

    private void tryLoadSkin() {
        if (this.skinTexture != null || this.skinLoadFailed || this.skinTarget.is("Off")) {
            return;
        }
        String name = this.skinFile.getValue();
        if (name == null || name.isBlank() || name.equals(this.attemptedSkinFile)) {
            return;
        }
        this.attemptedSkinFile = name;
        try (InputStream in = Files.newInputStream(this.resolve(name).toPath())) {
            NativeImage image = NativeImage.read(in);
            image = upgradeLegacy(image);
            this.skinSlim = detectSlim(image);
            if (this.vivid.getValue()) {
                applyVivid(image, this.saturation.getValue().floatValue() / 100.0f,
                        this.brightness.getValue().floatValue() / 100.0f);
            }
            this.skinTexture = mc.getTextureManager().register("nilore_skin", new DynamicTexture(image));
            logger.info("Skin: loaded {} ({}x{}, model {})", name, image.getWidth(), image.getHeight(),
                    this.skinSlim ? "slim" : "default");
        } catch (Exception e) {
            this.skinLoadFailed = true;
            logger.warn("Skin: failed to load '{}' from ~/.nilore/skins/ — {}", name, e.toString());
        }
    }

    private void tryLoadCape() {
        if (this.capeTexture != null || this.capeLoadFailed || this.capeTarget.is("Off")) {
            return;
        }
        String name = this.capeFile.getValue();
        if (name == null || name.isBlank() || name.equals(this.attemptedCapeFile)) {
            return;
        }
        this.attemptedCapeFile = name;
        try (InputStream in = Files.newInputStream(this.resolve(name).toPath())) {
            NativeImage image = NativeImage.read(in);
            if (image.getWidth() != image.getHeight() * 2 || image.getHeight() < 32) {
                throw new IllegalArgumentException(
                        "cape must be 2:1 (e.g. 64x32, 512x256), got " + image.getWidth() + "x" + image.getHeight());
            }
            this.capeTexture = mc.getTextureManager().register("nilore_cape", new DynamicTexture(image));
            logger.info("Skin: loaded cape {} ({}x{})", name, image.getWidth(), image.getHeight());
        } catch (Exception e) {
            this.capeLoadFailed = true;
            logger.warn("Skin: failed to load cape '{}' from ~/.nilore/skins/ — {}", name, e.toString());
        }
    }

    /** ~/.nilore/skins/<name>[.png]; 名字带路径分隔符直接拒绝, 防目录穿越。 */
    private File resolve(String name) {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("file name must not contain path separators");
        }
        File dir = new File(NiloreClient.configDir, "skins");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        String fileName = name.toLowerCase(java.util.Locale.ROOT).endsWith(".png") ? name : name + ".png";
        return new File(dir, fileName);
    }

    /**
     * 1.8 老 64x32 (及等比的 128x64 HD) 皮肤升级到现代方图: 左腿/左臂在下半张是空的,
     * 把右腿 (0,16) / 右臂 (40,16) 水平镜像补到 (16,48) / (32,48)。现代方图原样返回。
     */
    private static NativeImage upgradeLegacy(NativeImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (w != h * 2 || h < 32) {
            return image;
        }
        int scale = h / 32;
        NativeImage upgraded = new NativeImage(w, h * 2, false);
        // 上半张整体拷贝
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                upgraded.setPixelRGBA(x, y, image.getPixelRGBA(x, y));
            }
        }
        copyMirrored(image, upgraded, 0, 16, 16, 48, 16, scale);
        copyMirrored(image, upgraded, 40, 16, 32, 48, 16, scale);
        return upgraded;
    }

    /** 源区域 (16x16 逻辑格 × scale) 水平镜像拷到目标区域。 */
    private static void copyMirrored(NativeImage src, NativeImage dst,
                                     int srcU, int srcV, int dstU, int dstV, int size, int scale) {
        int s = size * scale;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int mirrored = s - 1 - x;
                dst.setPixelRGBA(dstU * scale + x, dstV * scale + y,
                        src.getPixelRGBA(srcU * scale + mirrored, srcV * scale + y));
            }
        }
    }

    /**
     * 宽窄判定: 宽手臂贴图占 x[40,56), 细手臂只到 x[54) —— 54/55 两列 × 手臂区行
     * 有任何不透明像素即宽, 全透明即细。对已升级的方图 (≥64x64) 判。
     */
    private static boolean detectSlim(NativeImage image) {
        int scale = image.getWidth() / 64;
        for (int y = 20 * scale; y < Math.min(32 * scale, image.getHeight()); y++) {
            for (int x = 54 * scale; x < Math.min(56 * scale, image.getWidth()); x++) {
                if ((image.getPixelRGBA(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 增艳: 全像素 HSB 处理。NativeImage 的 int 是 ABGR 布局, 进出都要换序。
     * 一次性处理, 对渲染零持续成本。
     */
    private static void applyVivid(NativeImage image, float satScale, float brightScale) {
        if (satScale == 1.0f && brightScale == 1.0f) {
            return;
        }
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int abgr = image.getPixelRGBA(x, y);
                int alpha = abgr >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int r = (abgr >>> 16) & 0xFF;
                int g = (abgr >>> 8) & 0xFF;
                int b = abgr & 0xFF;
                Color.RGBtoHSB(r, g, b, hsb);
                hsb[1] = Math.min(1.0f, hsb[1] * satScale);
                hsb[2] = Math.min(1.0f, hsb[2] * brightScale);
                int rgb = Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
                // rgb = 0xFF r g b (大端) → ABGR 小端
                image.setPixelRGBA(x, y, (alpha << 24) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00)
                        | ((rgb >>> 16) & 0xFF));
            }
        }
    }

    // ================= 以下为 patch 的查询入口 (AbstractClientPlayerPatch 调用) =================

    private boolean isSelf(AbstractClientPlayer player) {
        return mc.player != null && player.getUUID().equals(mc.player.getUUID());
    }

    private PlayerInfo stolenInfo() {
        if (mc.getConnection() == null) {
            return null;
        }
        String name = this.stealFrom.getValue();
        return name == null || name.isBlank() ? null : mc.getConnection().getPlayerInfo(name);
    }

    public ResourceLocation skinOverrideFor(AbstractClientPlayer player) {
        if (!this.isEnabled() || this.skinTarget.is("Off")) {
            return null;
        }
        boolean self = this.isSelf(player);
        if (this.skinTarget.is("Self") && !self) {
            return null;
        }
        if (self && !this.stealFrom.isBlank()) {
            PlayerInfo info = this.stolenInfo();
            // null = 对方贴图还没注册好, 先退回原版, 下一帧自然接上。
            return info != null ? info.getSkinLocation() : null;
        }
        return this.skinTexture;
    }

    public String modelOverrideFor(AbstractClientPlayer player) {
        if (!this.isEnabled() || this.skinTarget.is("Off")) {
            return null;
        }
        boolean self = this.isSelf(player);
        if (this.skinTarget.is("Self") && !self) {
            return null;
        }
        if (self && !this.stealFrom.isBlank()) {
            PlayerInfo info = this.stolenInfo();
            return info != null ? info.getModelName() : null;
        }
        // 模型只在皮肤贴图真的被换掉时才改 —— 细手臂配宽皮肤贴图会拉伸穿模。
        if (this.skinTexture == null) {
            return null;
        }
        if (this.model.is("Default")) {
            return "default";
        }
        if (this.model.is("Slim")) {
            return "slim";
        }
        return this.skinSlim ? "slim" : "default";
    }

    public ResourceLocation capeOverrideFor(AbstractClientPlayer player) {
        if (!this.isEnabled()) {
            return null;
        }
        boolean self = this.isSelf(player);
        if (self && !this.stealFrom.isBlank()) {
            PlayerInfo info = this.stolenInfo();
            return info != null ? info.getCapeLocation() : null;
        }
        if (this.capeTarget.is("Off")) {
            return null;
        }
        if (this.capeTarget.is("Self") && !self) {
            return null;
        }
        return this.capeTexture;
    }

    public ResourceLocation elytraOverrideFor(AbstractClientPlayer player) {
        if (!this.isEnabled()) {
            return null;
        }
        boolean self = this.isSelf(player);
        if (self && !this.stealFrom.isBlank()) {
            PlayerInfo info = this.stolenInfo();
            return info != null ? info.getElytraLocation() : null;
        }
        if (!this.elytraTexture.getValue() || this.capeTexture == null || this.capeTarget.is("Off")) {
            return null;
        }
        if (this.capeTarget.is("Self") && !self) {
            return null;
        }
        return this.capeTexture;
    }

    /** null = 不干预; FALSE = 藏披风; TRUE = 自定义披风需要的开门信号。 */
    public Boolean capeLoadedOverrideFor(AbstractClientPlayer player) {
        if (!this.isEnabled()) {
            return null;
        }
        boolean self = this.isSelf(player);
        if (this.hideOtherCapes.getValue() && !self && player.isCapeLoaded()) {
            return Boolean.FALSE;
        }
        return this.capeOverrideFor(player) != null ? Boolean.TRUE : null;
    }
}
