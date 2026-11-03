package zeg;

import zeg.content.*;
import mindustry.mod.Mod;
import zeg.content.ZegPlanets;

public class ZegMod extends Mod {
    
    public ZegMod() {
        super();
    }
    
    @Override
    public void loadContent() {
        // Load your content here (blocks, items, units, etc.)
        ZegItems.load();
        ZegUnitType.load();
        ZegPlanets.load();
        ZegBlocks.load();
        ZegTechTree.load();
    }
}
