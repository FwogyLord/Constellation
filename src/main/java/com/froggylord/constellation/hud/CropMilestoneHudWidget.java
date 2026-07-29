package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesCropMilestones;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/GardenCropMilestoneDisplay.kt
public final class CropMilestoneHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;
    private HudPosition position;
    private boolean enabled = true;
    public CropMilestoneHudWidget(HudPosition position, BooleanSupplier configEnabled) { this.position = position; this.configEnabled = configEnabled; }
    @Override public String id() { return "garden-crop-milestone"; }
    @Override public HudPosition position() { return position; }
    @Override public void setPosition(HudPosition position) { this.position = position; }
    @Override public boolean isEnabled() { return enabled && configEnabled.getAsBoolean(); }
    @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
    @Override public boolean visibleNow() { return isEnabled() && HerculesCropMilestones.hudVisible(); }
    @Override public String editorLabel() { return "Crop Milestone"; }
    @Override protected String title() { return "Crop Milestone"; }
    @Override protected List<Row> rows() { return HerculesCropMilestones.hudRows().stream().map(row -> new Row("", row.label(), row.value(), row.color())).toList(); }
    @Override protected List<Row> previewRows() { return List.of(new Row("", "Wheat", "31 -> 32", 0xFF55FFFF), new Row("", "Progress", "824,300/2,600,000", 0xFFFFFF55), new Row("", "ETA", "1h 12m", 0xFF55FFFF), new Row("", "Crops/min", "12,480", 0xFFFFFF55)); }
}
