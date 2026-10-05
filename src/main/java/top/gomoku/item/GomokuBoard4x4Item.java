package top.gomoku.item;

import cn.nukkit.item.customitem.CustomItemDefinition;
import cn.nukkit.item.customitem.ItemCustom;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 大号棋盘放置物品，手持后对准方块右键即可放下 4x4 棋盘。
 * <p>
 * 与标准棋盘共用物品贴图与手持 3D 模型，仅放置尺寸与显示名不同（以「4×4」区分）。
 */
public class GomokuBoard4x4Item extends ItemCustom {

    public static final String IDENTIFIER = "gomoku:board_4x4_item";

    public GomokuBoard4x4Item() {
        super(IDENTIFIER, "五子棋棋盘（4×4）", "gomoku_board_icon");
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