package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesHoeLevel;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/HoeLevelDisplay.kt
public final class HoeLevelHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;
    private HudPosition position;
    private boolean enabled = true;
    public HoeLevelHudWidget(HudPosition position, BooleanSupplier configEnabled) { this.position = position; this.configEnabled = configEnabled; }
    @Override public String id() { return "garden-hoe-level"; }
    @Override public HudPosition position() { return position; }
    @Override public void setPosition(HudPosition position) { this.position = position; }
    @Override public boolean isEnabled() { return enabled && configEnabled.getAsBoolean(); }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @Override public boolean visibleNow() { return isEnabled() && HerculesHoeLevel.hudVisible(); }
    @Override public String editorLabel() { return "Hoe Level"; }
    @Override protected String title() { return "Hoe Level"; }
    @Override protected List<Row> rows() { return HerculesHoeLevel.hudRows().stream().map(row -> new Row("", row.label(), row.value(), row.color())).toList(); }
    @Override protected List<Row> previewRows() { return List.of(new Row("", "Level", "37 -> 38", 0xFF55FFFF), new Row("", "Tool XP", "842,190/1,200,000", 0xFFFFFF55), new Row("", "ETA", "18m 42s", 0xFF55FFFF)); }
}
