package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesJacobHistory;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/contest/JacobContestTimeNeeded.kt
public final class JacobPlannerHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;private HudPosition position;private boolean enabled=true;
    public JacobPlannerHudWidget(HudPosition position,BooleanSupplier configEnabled){this.position=position;this.configEnabled=configEnabled;}
    @Override public String id(){return"garden-jacob-planner";}@Override public HudPosition position(){return position;}@Override public void setPosition(HudPosition p){position=p;}
    @Override public boolean isEnabled(){return enabled&&configEnabled.getAsBoolean();}@Override public void setEnabled(boolean value){enabled=value;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesJacobHistory.hudVisible();}@Override public String editorLabel(){return"Jacob Medal Planner";}
    @Override protected String title(){return"Jacob Medal Planner";}@Override protected List<Row> rows(){return HerculesJacobHistory.hudRows().stream().map(r->new Row("",r.label(),r.value(),r.color())).toList();}
    @Override protected List<Row> previewRows(){return List.of(new Row("","Target","Gold",0xFFFFAA00),new Row("","Wheat","12m 18s | 1,420 FF",0xFF55FFFF),new Row("","Melon","Not possible",0xFFFF5555));}
}
