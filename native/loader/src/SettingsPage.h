#pragma once

#include <QWidget>

class QLabel;

namespace loader {

class PillSwitch;

// Settings page (flat, shown when the left sidebar's "Settings" entry is
// active). Contains the HWID spoof pill switch plus the Native Bypass
// toggle (both default OFF), persisted via loader_settings.
//
// The Syscall Thread toggle is gone: injection now always goes through the
// normal CreateRemoteThread path in manual_map.cpp.
//
class SettingsPage : public QWidget {
    Q_OBJECT
public:
    explicit SettingsPage(QWidget* parent = nullptr);

    bool hwidSpoofEnabled() const;

private:
    void buildUi();
    QWidget* makeToggleRow(const QString& name, const QString& desc,
                           PillSwitch* sw, QWidget* parent);

    PillSwitch* hwidSwitch_         = nullptr;
    PillSwitch* nativeBypassSwitch_ = nullptr;
};

} // namespace loader