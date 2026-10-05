package top.gomoku.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 标准 2x2 棋盘方块，对应资源包中的 gomoku:board。
 * <p>
 * 通过 {@code gomoku:position} 属性（0..3）选择 2x2 拼接中的四分之一模型。
 * position = dx + dz * 2，其中 dx/dz 为该方块在 2x2 区域中的偏移。
 */
public class GomokuBoardBlock extends BaseGomokuBoardBlock {

    public static final String IDENTIFIER = "gomoku:board";

    /** 0..3，对应模型 board_segment_11 / _01 / _10 / _00（NW / NE / SW / SE） */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("gomoku:position", true, 3, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 四象限几何模型：索引 = position，分别对应 NW / NE / SW / SE 纹理象限。 */
    private static final String[] GEOMETRIES = {
            "geometry.gomoku.board_segment_11",
            "geometry.gomoku.board_segment_01",
            "geometry.gomoku.board_segment_10",
            "geometry.gomoku.board_segment_00",
    };

    public GomokuBoardBlock() {
        this(0);
    }

    public GomokuBoardBlock(int meta) {
        super(IDENTIFIER, PROPERTIES, meta);
    }

    public int getPosition() {
        return this.getIntValue(POSITION);
    }

    public void setPosition(int position) {
        this.setIntValue(POSITION, position);
    }

    @Override
    public String getName() {
        return "五子棋棋盘";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "五子棋棋盘", GomokuBoardBlock::new);
    }
}