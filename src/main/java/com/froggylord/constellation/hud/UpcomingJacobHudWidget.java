package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesJacobUpcoming;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/GardenNextJacobContest.kt
public final class UpcomingJacobHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;private HudPosition position;private boolean enabled=true;
    public UpcomingJacobHudWidget(HudPosition position,BooleanSupplier configEnabled){this.position=position;this.configEnabled=configEnabled;}
    @Override public String id(){return"garden-next-jacob";}@Override public HudPosition position(){return position;}@Override public void setPosition(HudPosition p){position=p;}
    @Override public boolean isEnabled(){return enabled&&configEnabled.getAsBoolean();}@Override public void setEnabled(boolean value){enabled=value;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesJacobUpcoming.hudVisible();}@Override public String editorLabel(){return"Next Jacob Contest";}
    @Override protected String title(){return"Next Jacob Contest";}@Override protected List<Row> rows(){return HerculesJacobUpcoming.hudRows().stream().map(r->new Row("",r.label(),r.value(),r.color())).toList();}
    @Override protected List<Row> previewRows(){return List.of(new Row("","Next","Wheat, Melon, Sunflower",0xFFFFFF55),new Row("","Starts in","12m 34s",0xFF55FFFF),new Row("","Boosted","Sunflower",0xFFFFAA00));}
}
