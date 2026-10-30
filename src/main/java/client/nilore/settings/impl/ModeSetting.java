package client.nilore.settings.impl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import client.nilore.settings.Setting;
import client.nilore.settings.SettingVisibility;

public class ModeSetting
extends Setting<String> {
    @Getter
    private final String[] modes;

    public ModeSetting(String string, String ... stringArray) {
        super(string, stringArray.length > 0 ? stringArray[0] : null);
        this.modes = stringArray;
    }

    public ModeSetting withVisibility(SettingVisibility settingVisibility) {
        this.setVisibility(settingVisibility);
        return this;
    }

    public ModeSetting withDefault(String string) {
        this.setValue(string);
        return this;
    }

    public boolean is(String string) {
        return this.getValue() != null && this.getValue().equals(string);
    }

    @Override
    public void save(JsonObject jsonObject) {
        jsonObject.addProperty(this.getName(), this.getValue());
    }

    @Override
    public void load(JsonElement jsonElement) {
        String loaded = jsonElement.getAsString();
        for (String mode : this.modes) {
            if (mode.equals(loaded)) {
                this.setValue(loaded);
                return;
            }
        }
        // An option that no longer exists (renamed or removed) is dropped instead of adopted:
        // keeping it would leave the owner comparing against a branch that can never be taken.
    }

    }