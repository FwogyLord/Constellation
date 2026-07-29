package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesComposter;
import java.util.List;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/composter/ComposterDisplay.kt
public final class ComposterHudWidget extends ThemedHudWidget {
    private final BooleanSupplier configEnabled;
    private HudPosition position;
    private boolean enabled = true;
    public ComposterHudWidget(HudPosition position,BooleanSupplier configEnabled){this.position=position;this.configEnabled=configEnabled;}
    @Override public String id(){return"garden-composter";}
    @Override public HudPosition position(){return position;}
    @Override public void setPosition(HudPosition position){this.position=position;}
    @Override public boolean isEnabled(){return enabled&&configEnabled.getAsBoolean();}
    @Override public void setEnabled(boolean enabled){this.enabled=enabled;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesComposter.hudVisible();}
    @Override public String editorLabel(){return"Composter";}
    @Override protected String title(){return"Composter";}
    @Override protected List<Row> rows(){return HerculesComposter.hudRows().stream().map(r->new Row("",r.label(),r.value(),r.color())).toList();}
    @Override protected List<Row> previewRows(){return List.of(new Row("","Organic Matter","37,500",0xFFFFFF55),new Row("","Fuel","82,000",0xFF55FF55),new Row("","Empty in","2h 18m",0xFF55FFFF));}
}
