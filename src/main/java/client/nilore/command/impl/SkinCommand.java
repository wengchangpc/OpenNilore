package client.nilore.command.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import client.nilore.NiloreClient;
import client.nilore.command.Command;
import client.nilore.modules.impl.render.Skin;
import client.nilore.utils.misc.ChatUtil;

/**
 * {@code .skin} —— Skin 模块的文本入口 (GUI 不支持文本输入, 文件名/偷皮肤只能走命令)。
 *
 * <pre>
 * .skin                          查看当前状态
 * .skin set &lt;文件&gt;              设置皮肤文件 (放 ~/.nilore/skins/, 可省略 .png) 并切到 Self
 * .skin cape &lt;文件&gt;              设置披风贴图 (2:1 宽高比) 并切到 Self
 * .skin target &lt;self|everyone|off&gt;   皮肤作用范围
 * .skin capetarget &lt;self|everyone|off&gt;
 * .skin model &lt;auto|default|slim&gt;
 * .skin steal &lt;玩家&gt;             照抄指定玩家的皮肤/模型/披风/鞘翅 (清空 = 停止)
 * .skin vivid &lt;饱和度&gt; &lt;亮度&gt;     增艳参数 (百分比, 100 = 原样)
 * .skin list                     列出 skins 文件夹里的 PNG
 * .skin reload                   重新加载贴图
 * .skin off                      全部关闭
 * </pre>
 */
public class SkinCommand
extends Command {

    private static final String[] TARGETS = {"self", "everyone", "off"};
    private static final String[] MODELS = {"auto", "default", "slim"};

    public SkinCommand() {
        super("skin", new String[0]);
    }

    @Override
    public void onCommand(String[] args) {
        Skin skin = Skin.INSTANCE;
        if (skin == null) {
            ChatUtil.print("Skin is not loaded.");
            return;
        }

        if (args.length == 0) {
            this.report(skin);
            return;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("set") && args.length >= 2) {
            String name = sanitize(args[1]);
            if (name.isEmpty()) {
                ChatUtil.print("Usage: .skin set <file>");
                return;
            }
            skin.skinFile.setValue(name);
            if (skin.skinTarget.is("Off")) {
                skin.skinTarget.setValue("Self");
            }
            skin.reloadTextures();
            this.save();
            ChatUtil.print("Skin set: " + name + ".png (put it in ~/.nilore/skins/)");
            return;
        }

        if (sub.equals("cape") && args.length >= 2) {
            String name = sanitize(args[1]);
            if (name.isEmpty()) {
                ChatUtil.print("Usage: .skin cape <file>");
                return;
            }
            skin.capeFile.setValue(name);
            if (skin.capeTarget.is("Off")) {
                skin.capeTarget.setValue("Self");
            }
            skin.reloadTextures();
            this.save();
            ChatUtil.print("Cape set: " + name + ".png (2:1 ratio, e.g. 512x256)");
            return;
        }

        if (sub.equals("target") && args.length >= 2) {
            if (this.applyMode(skin.skinTarget, args[1], TARGETS)) {
                this.save();
                ChatUtil.print("Skin target: " + skin.skinTarget.getValue());
            }
            return;
        }

        if (sub.equals("capetarget") && args.length >= 2) {
            if (this.applyMode(skin.capeTarget, args[1], TARGETS)) {
                this.save();
                ChatUtil.print("Cape target: " + skin.capeTarget.getValue());
            }
            return;
        }

        if (sub.equals("model") && args.length >= 2) {
            if (this.applyMode(skin.model, args[1], MODELS)) {
                this.save();
                ChatUtil.print("Skin model: " + skin.model.getValue());
            }
            return;
        }

        if (sub.equals("steal") && args.length >= 2) {
            // join 后剥掉子命令本身, 保留玩家名里可能的空格。
            String raw = String.join(" ", args).substring(args[0].length()).trim();
            String name = sanitize(raw);
            if (name.isEmpty()) {
                ChatUtil.print("Usage: .skin steal <player>");
                return;
            }
            skin.stealFrom.setValue(name);
            this.save();
            ChatUtil.print("Stealing skin from: " + name + " (self only, client-side)");
            return;
        }

        if (sub.equals("stealclear") || (sub.equals("steal") && args.length == 1)) {
            skin.stealFrom.setValue("");
            this.save();
            ChatUtil.print("Skin steal off.");
            return;
        }

        if (sub.equals("vivid") && args.length >= 3) {
            Integer sat = parsePercent(args[1]);
            Integer bri = parsePercent(args[2]);
            if (sat == null || bri == null) {
                ChatUtil.print("Usage: .skin vivid <saturation> <brightness> (percent, 100 = original)");
                return;
            }
            skin.vivid.setValue(true);
            skin.saturation.setValue(sat);
            skin.brightness.setValue(bri);
            skin.reloadTextures();
            this.save();
            ChatUtil.print("Vivid: saturation " + sat + "%, brightness " + bri + "%");
            return;
        }

        if (sub.equals("list")) {
            this.listFiles();
            return;
        }

        if (sub.equals("reload")) {
            skin.reloadTextures();
            ChatUtil.print("Skin textures reloading...");
            return;
        }

        if (sub.equals("off")) {
            skin.skinTarget.setValue("Off");
            skin.capeTarget.setValue("Off");
            skin.stealFrom.setValue("");
            this.save();
            ChatUtil.print("Skin module overrides off.");
            return;
        }

        ChatUtil.print("Unknown subcommand. Try: set / cape / target / capetarget / model / steal / vivid / list / reload / off");
    }

    private void report(Skin skin) {
        ChatUtil.print("Skin: " + skin.skinTarget.getValue()
                + " | file: " + (skin.skinFile.isBlank() ? "-" : skin.skinFile.getValue())
                + " | model: " + skin.model.getValue()
                + " | cape: " + skin.capeTarget.getValue()
                + " | cape file: " + (skin.capeFile.isBlank() ? "-" : skin.capeFile.getValue())
                + " | steal: " + (skin.stealFrom.isBlank() ? "-" : skin.stealFrom.getValue())
                + " | vivid: " + (skin.vivid.getValue()
                        ? skin.saturation.getValue().intValue() + "%/" + skin.brightness.getValue().intValue() + "%"
                        : "off"));
    }

    private void listFiles() {
        File dir = new File(NiloreClient.configDir, "skins");
        File[] files = dir == null ? null : dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null || files.length == 0) {
            ChatUtil.print("No PNG files in " + dir + " — put skins there first.");
            return;
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        ChatUtil.print("Skins (" + names.size() + "): " + String.join(", ", names));
    }

    private boolean applyMode(client.nilore.settings.impl.ModeSetting setting, String value, String[] options) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.equals(lower)) {
                String canonical = Character.toUpperCase(option.charAt(0)) + option.substring(1);
                setting.setValue(canonical);
                return true;
            }
        }
        ChatUtil.print("Unknown value. Use: " + String.join(" / ", options));
        return false;
    }

    private static Integer parsePercent(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 剥掉文件名里的路径分隔符与格式符, 防目录穿越/渲染破坏。 */
    private static String sanitize(String text) {
        return text.replaceAll("[/\\\\:\n\r\u00a7*?\"<>|]", "").trim();
    }

    private void save() {
        NiloreClient.getInstance().getConfigManager().saveAll();
    }

    @Override
    public String[] onTab(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> subs = new ArrayList<>();
            for (String option : new String[]{"set", "cape", "target", "capetarget", "model",
                    "steal", "vivid", "list", "reload", "off"}) {
                if (option.startsWith(prefix)) {
                    subs.add(option);
                }
            }
            return subs.toArray(new String[0]);
        }
        if (args.length == 2) {
            String head = args[0].toLowerCase(Locale.ROOT);
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> subs = new ArrayList<>();
            String[] options = switch (head) {
                case "target", "capetarget" -> TARGETS;
                case "model" -> MODELS;
                default -> new String[0];
            };
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    subs.add(option);
                }
            }
            return subs.toArray(new String[0]);
        }
        return new String[0];
    }
}
