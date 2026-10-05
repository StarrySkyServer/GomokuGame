package top.gomoku.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 棋盘放置物品，手持后对准方块右键即可放下 2x2 棋盘。
 */
public class GomokuBoardItem extends ItemCustom {

    public static final String IDENTIFIER = "gomoku:board_item";

    public GomokuBoardItem() {
        super(IDENTIFIER, "五子棋棋盘", "gomoku_board_icon");
    }

    @Override
    public CustomItemDefinition getDefinition() {
        return CustomItemDefinition
                .simpleBuilder(this, CreativeItemCategory.ITEMS)
                .build();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }
}