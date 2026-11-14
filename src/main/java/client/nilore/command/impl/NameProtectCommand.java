package client.nilore.command.impl;

import client.nilore.NiloreClient;
import client.nilore.command.Command;
import client.nilore.modules.impl.render.NameProtect;
import client.nilore.utils.misc.ChatUtil;

/**
 * {@code .np} —— NameProtect 的自定义名字和颜色入口。
 *
 * <p>GUI 不支持文本输入 (两个 ClickGUI 都只渲染 Bool/Number/Mode/MultiSelect),
 * 所以自定义名字走命令。设置本身是 StringSetting, 改完立即落盘, 重启后仍在。</p>
 *
 * <pre>
 * .np                    查看当前配置
 * .np &lt;名字&gt;             设置自定义名并切到 Custom 模式 (名字可含空格)
 * .np mode &lt;模式&gt;        Fixed / Random / Hidden / Custom
 * .np color &lt;r&gt; &lt;g&gt; &lt;b&gt;  设置颜色并开启染色 (0-255)
 * .np off                 关闭染色
 * </pre>
 */
public class NameProtectCommand
extends Command {

    private static final String[] MODES = {"Fixed", "Random", "Hidden", "Custom"};

    public NameProtectCommand() {
        super("nameprotect", new String[]{"np"});
    }

    @Override
    public void onCommand(String[] args) {
        NameProtect np = NameProtect.INSTANCE;
        if (np == null) {
            ChatUtil.print("NameProtect is not loaded.");
            return;
        }

        if (args.length == 0) {
            this.report(np);
            return;
        }

        String sub = args[0].toLowerCase(java.util.Locale.ROOT);

        if (sub.equals("mode") && args.length >= 2) {
            String target = capitalize(args[1]);
            for (String mode : MODES) {
                if (mode.equals(target)) {
                    np.mode().setValue(mode);
                    this.save();
                    ChatUtil.print("NameProtect mode: " + mode);
                    return;
                }
            }
            ChatUtil.print("Unknown mode. Use Fixed / Random / Hidden / Custom.");
            return;
        }

        if (sub.equals("color") && args.length >= 4) {
            Integer r = parse(args[1]);
            Integer g = parse(args[2]);
            Integer b = parse(args[3]);
            if (r == null || g == null || b == null) {
                ChatUtil.print("Usage: .np color <r> <g> <b> (0-255)");
                return;
            }
            np.colorRedSetting().setValue(r);
            np.colorGreenSetting().setValue(g);
            np.colorBlueSetting().setValue(b);
            np.colorEnabledSetting().setValue(true);
            this.save();
            ChatUtil.print("NameProtect color: " + r + ", " + g + ", " + b + " (enabled)");
            return;
        }

        if (sub.equals("off")) {
            np.colorEnabledSetting().setValue(false);
            this.save();
            ChatUtil.print("NameProtect color off.");
            return;
        }

        // 剩下的全部当名字用 —— 名字可以带空格, 直接 join。
        String name = String.join(" ", args).trim();
        if (name.isEmpty() || name.length() > 32) {
            ChatUtil.print("Name must be 1-32 characters.");
            return;
        }
        // 名字里塞换行或格式符会破坏聊天渲染, 顺手剥掉。
        name = name.replaceAll("[\n\r\u00a7]", "");
        if (name.isEmpty()) {
            ChatUtil.print("Name must be 1-32 characters.");
            return;
        }
        np.customNameSetting().setValue(name);
        np.mode().setValue("Custom");
        this.save();
        ChatUtil.print("NameProtect custom name: " + name);
    }

    private void report(NameProtect np) {
        ChatUtil.print("Mode: " + np.mode().getValue()
                + " | Custom name: " + np.customNameSetting().getValue()
                + " | Color: " + (np.colorEnabledSetting().getValue()
                        ? np.colorRedSetting().getValue().intValue() + ", "
                                + np.colorGreenSetting().getValue().intValue() + ", "
                                + np.colorBlueSetting().getValue().intValue()
                        : "off"));
    }

    private void save() {
        NiloreClient.getInstance().getConfigManager().saveAll();
    }

    private static Integer parse(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            if (value < 0 || value > 255) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String capitalize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String[] onTab(String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> subs = new java.util.ArrayList<>();
            for (String option : new String[]{"mode", "color", "off"}) {
                if (option.startsWith(prefix)) {
                    subs.add(option);
                }
            }
            return subs.toArray(new String[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            java.util.List<String> subs = new java.util.ArrayList<>();
            for (String mode : MODES) {
                if (mode.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                    subs.add(mode);
                }
            }
            return subs.toArray(new String[0]);
        }
        return new String[0];
    }
}
