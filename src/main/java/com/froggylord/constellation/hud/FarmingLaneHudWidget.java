package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesFarmingLanes;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/lane/FarmingLaneFeatures.kt
public final class FarmingLaneHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;
    private HudPosition position;
    private boolean enabled = true;

    public FarmingLaneHudWidget(HudPosition position, BooleanSupplier configEnabled) {
        this.position = position;
        this.configEnabled = configEnabled;
    }

    @Override public String id() { return "garden-farming-lane"; }
    @Override public HudPosition position() { return position; }
    @Override public void setPosition(HudPosition position) { this.position = position; }
    @Override public boolean isEnabled() { return enabled && configEnabled.getAsBoolean(); }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @Override public boolean visibleNow() { return isEnabled() && HerculesFarmingLanes.hudVisible(); }
    @Override public String editorLabel() { return "Farming Lane"; }
    @Override protected String title() { return "Farming Lane"; }
    @Override protected List<Row> rows() {
        return HerculesFarmingLanes.hudRows().stream()
            .map(row -> new Row("", row.label(), row.value(), row.color())).toList();
    }
    @Override protected List<Row> previewRows() {
        return List.of(
            new Row("", "Distance", "48.2m", 0xFFFFFF55),
            new Row("", "Time", "7s", 0xFF55FFFF)
        );
    }
}
