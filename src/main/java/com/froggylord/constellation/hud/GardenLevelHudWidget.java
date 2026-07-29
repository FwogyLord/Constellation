package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesGardenLevel;
import com.froggylord.constellation.constellation.HerculesGardenTracker;

import java.util.*;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/GardenLevelDisplay.kt
public final class GardenLevelHudWidget extends ThemedHudWidget {
    private final BooleanSupplier gate;private HudPosition position;private boolean enabled=true;
    public GardenLevelHudWidget(HudPosition position,BooleanSupplier gate){this.position=position;this.gate=gate;}
    @Override public String id(){return"garden-level";}@Override public HudPosition position(){return position;}@Override public void setPosition(HudPosition p){position=p;}
    @Override public boolean isEnabled(){return enabled&&gate.getAsBoolean();}@Override public void setEnabled(boolean value){enabled=value;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesGardenLevel.visible();}@Override public String editorLabel(){return"Garden Level";}
    @Override protected String title(){return"Garden Level";}
    @Override protected List<Row> rows(){
        var state=HerculesGardenLevel.state();if(state==null)return List.of(new Row("","Status","Open Desk"));
        var cfg=HerculesGardenTracker.config();List<Row> rows=new ArrayList<>();
        rows.add(new Row("","Level",HerculesGardenLevel.number(state.level()),0xFF55FF55));
        if(cfg.gardenLevelShowProgress)rows.add(new Row("","XP",state.maximum()?"Maximum":HerculesGardenLevel.format(state.levelXp())+" / "+HerculesGardenLevel.format(state.neededXp()),0xFFFFFF55));
        if(cfg.gardenLevelShowPercentage&&!state.maximum())rows.add(new Row("","Progress",HerculesGardenLevel.decimal(state.percentage())+"%",0xFFFFFF55));
        if(cfg.gardenLevelShowTotalXp)rows.add(new Row("","Total XP",HerculesGardenLevel.format(state.totalXp()),0xFF55FFFF));
        if(cfg.gardenLevelShowOverflowXp&&state.level()>=15)rows.add(new Row("","Overflow",HerculesGardenLevel.format(state.overflowXp()),0xFF55FFFF));
        return rows;
    }
    @Override protected List<Row> previewRows(){return List.of(new Row("","Level","18",0xFF55FF55),new Row("","XP","4,280 / 10,000",0xFFFFFF55),new Row("","Progress","42.8%",0xFFFFFF55),new Row("","Overflow","34,280",0xFF55FFFF));}
}
