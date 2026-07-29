package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.PhoenixWorldAge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from Devonian (GPL-3.0-only): features/misc/WorldAge.kt
public final class WorldAgeHudWidget extends ThemedHudWidget {
    private final BooleanSupplier gate;
    private HudPosition position;
    private boolean enabled = true;

    public WorldAgeHudWidget(HudPosition position, BooleanSupplier gate) {
        this.position = position;
        this.gate = gate;
    }

    @Override public String id() { return "phoenix-world-age"; }
    @Override public HudPosition position() { return position; }
    @Override public void setPosition(HudPosition value) { position = value; }
    @Override public boolean isEnabled() { return enabled && gate.getAsBoolean(); }
    @Override public void setEnabled(boolean value) { enabled = value; }
    @Override public boolean visibleNow() { return isEnabled() && PhoenixWorldAge.visible() && !rows().isEmpty(); }
    @Override public String editorLabel() { return "World Age"; }
    @Override protected String title() { return "World Age"; }

    @Override
    protected List<Row> rows() {
        if (!PhoenixWorldAge.visible()) return List.of();
        var cfg = PhoenixWorldAge.config();
        var value = PhoenixWorldAge.snapshot();
        if (cfg == null || value == null) return List.of();
        ArrayList<Row> rows = new ArrayList<>();
        if (cfg.worldAgeShowDay) rows.add(new Row("", "Day", Long.toString(value.day()), cfg.worldAgeDayColor));
        if (cfg.worldAgeShowClock) rows.add(new Row("", "Time", value.clock(), cfg.worldAgeClockColor));
        int phaseColor = value.phase() == PhoenixWorldAge.Phase.NIGHT || value.phase() == PhoenixWorldAge.Phase.SUNRISE
            ? cfg.worldAgeNightPhaseColor : cfg.worldAgeDayPhaseColor;
        if (cfg.worldAgeShowPhase) rows.add(new Row("", "Phase", PhoenixWorldAge.phaseName(value.phase()), phaseColor));
        if (cfg.worldAgeShowTransition) rows.add(new Row("", value.transition(),
            PhoenixWorldAge.transitionTime(value.transitionTicks()), phaseColor));
        if (cfg.worldAgeShowRealAge) rows.add(new Row("", "Real age", value.realAge(), cfg.worldAgeDayColor));
        if (cfg.worldAgeShowTicks) rows.add(new Row("", "Ticks", Long.toString(value.ticks()), cfg.worldAgeClockColor));
        return rows;
    }

    @Override
    protected List<Row> previewRows() {
        return List.of(new Row("", "Day", "10", 0xFFFFAA00),
            new Row("", "Time", "14:32", 0xFF55FFFF),
            new Row("", "Sunset", "2:28", 0xFFFFFF55));
    }
}
