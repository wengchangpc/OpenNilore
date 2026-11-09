package client.nilore.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import client.nilore.settings.Setting;
import client.nilore.settings.SettingVisibility;

/**
 * 纯文本设置项。
 *
 * <p>两个 ClickGUI 的设置渲染都按具体类型分发 (instanceof Boolean/Number/Mode/MultiSelect),
 * 没见过的类型会被直接跳过 —— 所以这个类型在 GUI 里不显示, 但在配置里正常保存/加载,
 * 设计上配合命令修改 (如 {@code .np <新名字>})。</p>
 */
public class StringSetting
extends Setting<String> {
    public StringSetting(String string, String string2) {
        super(string, string2);
    }

    public StringSetting(String string, String string2, SettingVisibility settingVisibility) {
        super(string, string2, settingVisibility);
    }

    public boolean isBlank() {
        String string = this.getValue();
        return string == null || string.isBlank();
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue() == null ? "" : this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        if (jsonElement.isJsonPrimitive() && jsonElement.getAsJsonPrimitive().isString()) {
            this.setValue(jsonElement.getAsString());
        }
    }
}
