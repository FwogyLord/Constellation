package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.PhoenixCenturyCake;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from Devonian (GPL-3.0-only): features/misc/CenturyCakeTimer.kt
public final class CenturyCakeHudWidget extends ThemedHudWidget {
    private final BooleanSupplier gate;
    private HudPosition position;
    private boolean enabled = true;

    public CenturyCakeHudWidget(HudPosition position, BooleanSupplier gate) {
        this.position = position;
        this.gate = gate;
    }

    @Override public String id() { return "phoenix-century-cakes"; }
    @Override public HudPosition position() { return position; }
    @Override public void setPosition(HudPosition value) { position = value; }
    @Override public boolean isEnabled() { return enabled && gate.getAsBoolean(); }
    @Override public void setEnabled(boolean value) { enabled = value; }
    @Override public boolean visibleNow() { return isEnabled() && !rows().isEmpty(); }
    @Override public String editorLabel() { return "Century Cakes"; }
    @Override protected String title() { return "Century Cakes"; }

    @Override
    protected List<Row> rows() {
        var cfg = PhoenixCenturyCake.config();
        if (cfg == null) return List.of();
        long expiry = PhoenixCenturyCake.expiry();
        long remaining = expiry - System.currentTimeMillis();
        if (expiry <= 0 && !cfg.centuryCakeShowUnknown) return List.of();
        if (expiry > 0 && remaining > 0 && cfg.centuryCakeOnlyExpired) return List.of();
        ArrayList<Row> rows = new ArrayList<>();
        if (cfg.centuryCakeShowProfile) rows.add(new Row("", "Profile", PhoenixCenturyCake.profile(), cfg.centuryCakeUnknownColor));
        if (expiry <= 0) rows.add(new Row("", "Buffs", "Unknown", cfg.centuryCakeUnknownColor));
        else if (remaining <= 0) rows.add(new Row("", "Buffs", "Expired", cfg.centuryCakeExpiredColor));
        else {
            int color = remaining <= Math.clamp(cfg.centuryCakeWarningMinutes, 0, 1440) * 60_000L
                ? cfg.centuryCakeWarningColor : cfg.centuryCakeActiveColor;
            rows.add(new Row("", "Buffs", PhoenixCenturyCake.format(remaining), color));
        }
        return rows;
    }

    @Override
    protected List<Row> previewRows() {
        return List.of(new Row("", "Buffs", "1d 23h", 0xFF55FF55));
    }
}
