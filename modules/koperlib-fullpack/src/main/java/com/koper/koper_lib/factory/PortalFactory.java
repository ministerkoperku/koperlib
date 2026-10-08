package com.koper.koper_lib.factory;

import com.google.gson.JsonObject;
import com.koper.koper_lib.block.PortalBlock;
import com.koper.koper_lib.item.IgniterItem;
import com.koper.koper_lib.loader.ContentRegistry;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;

public class PortalFactory {
    public static void createAndRegister(JsonObject json) {
        String id = json.get("id").getAsString();
        String dimension = json.get("dimension").getAsString();
        String blockId = id + "_block";
        String igniterId = id + "_igniter";

        BlockBehaviour.Properties blockSettings = ContentRegistry.createBlockSettings(blockId)
            .noCollision()
            .noOcclusion()
            .lightLevel(state -> 11)
            .sound(SoundType.GLASS);
        PortalBlock portalBlock = new PortalBlock(blockSettings, dimension);
        ContentRegistry.registerBlock(blockId, portalBlock, "KoperLib Portals");

        Item.Properties itemSettings = ContentRegistry.createItemSettings(igniterId).stacksTo(1);
        IgniterItem igniterItem = new IgniterItem(itemSettings, blockId);
        ContentRegistry.registerItem(igniterId, igniterItem, "KoperLib Portals");
    }
}
