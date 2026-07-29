package com.froggylord.constellation.hud;

import com.froggylord.constellation.constellation.HerculesCropMoney;
import com.froggylord.constellation.constellation.HerculesGardenTracker;

import java.util.*;
import java.util.function.BooleanSupplier;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/CropMoneyDisplay.kt
public final class CropMoneyHudWidget extends ThemedHudWidget {
    private final BooleanSupplier gate;private HudPosition position;private boolean enabled=true;
    public CropMoneyHudWidget(HudPosition position,BooleanSupplier gate){this.position=position;this.gate=gate;}
    @Override public String id(){return"garden-crop-money";}@Override public HudPosition position(){return position;}@Override public void setPosition(HudPosition p){position=p;}
    @Override public boolean isEnabled(){return enabled&&gate.getAsBoolean();}@Override public void setEnabled(boolean value){enabled=value;}
    @Override public boolean visibleNow(){return isEnabled()&&HerculesCropMoney.visible();}@Override public String editorLabel(){return"Crop Money per Hour";}
    @Override protected String title(){return HerculesGardenTracker.config().cropMoneyHideTitle?"": "Crop Money per Hour";}
    @Override protected List<Row> rows(){
        var state=HerculesCropMoney.state();if(state==null)return List.of();
        var cfg=HerculesGardenTracker.config();List<Row> out=new ArrayList<>();
        if(state.rows().isEmpty()){out.add(new Row("","Status",state.pricesReady()?"Farm each crop to learn BPS":"Loading prices"));return out;}
        for(var row:state.rows()){
            String label=cfg.cropMoneyCompact?"":(cfg.cropMoneyManualOrder?"#"+row.rank()+" "+row.crop().display():row.crop().display());
            List<String> values=new ArrayList<>();
            if(cfg.cropMoneyShowSellOffer)values.add(coins(row.price().sellOffer()));
            if(cfg.cropMoneyShowInstantSell)values.add(coins(row.price().instantSell()));
            if(cfg.cropMoneyShowNpc)values.add(coins(row.price().npc()));
            if(values.isEmpty())values.add(coins(row.price().sellOffer()));
            out.add(new Row("",label,String.join("/",values),row.current()?0xFFFFFF55:0xFFFFAA00));
        }
        return out;
    }
    @Override protected List<Row> previewRows(){return List.of(new Row("","Melon","18.4m",0xFFFFAA00),new Row("","Sugar Cane","16.8m",0xFFFFFF55),new Row("","Cactus","15.2m",0xFFFFAA00));}
    private static String coins(double value){
        if(value<=0)return"?";
        if(!HerculesGardenTracker.config().cropMoneyCompactPrice)return String.format(Locale.ROOT,"%,.0f",value);
        if(value>=1_000_000_000)return String.format(Locale.ROOT,"%.2fb",value/1_000_000_000);
        if(value>=1_000_000)return String.format(Locale.ROOT,"%.2fm",value/1_000_000);
        if(value>=1_000)return String.format(Locale.ROOT,"%.1fk",value/1_000);
        return String.format(Locale.ROOT,"%.0f",value);
    }
}
