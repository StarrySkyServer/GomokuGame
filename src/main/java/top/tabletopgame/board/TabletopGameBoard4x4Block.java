package top.tabletopgame.board;

import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;

/**
 * 大号 4x4 棋盘方块，对应资源包中的 tabletopgame:board_4x4。
 * <p>
 * 由 4x4 = 16 个方块拼接而成，每个方块仍只占 1 格，整体棋盘因此是标准 2x2 棋盘的两倍大
 * （棋盘贴图被切成 16 等份，每块显示其中一份，视觉上相当于把整张棋盘图放大 2 倍）。
 * <p>
 * 通过 {@code tabletopgame:position} 属性（0..15）选择对应的一份几何模型。
 * position = dx + dz * 4，其中 dx/dz 为该方块在 4x4 区域中的偏移。
 */
public class TabletopGameBoard4x4Block extends BaseTabletopGameBoardBlock {

    public static final String IDENTIFIER = "tabletopgame:board_4x4";

    /** 0..63：{@code position = segment * 4 + rotation}。segment 0..15 选择 16 等份贴图，rotation 0..3 为棋盘朝向。 */
    public static final IntBlockProperty POSITION =
            new IntBlockProperty("tabletopgame:position", true, 63, 0);

    public static final BlockProperties PROPERTIES = new BlockProperties(POSITION);

    /** 16 份几何模型：索引 = position，up 面 UV 偏移为 (dx*64, dz*64)。 */
    private static final String[] GEOMETRIES = new String[16];

    static {
        for (int i = 0; i < GEOMETRIES.length; i++) {
            GEOMETRIES[i] = "geometry.tabletopgame.board4_segment_" + i;
        }
    }

    public TabletopGameBoard4x4Block() {
        this(0);
    }

    public TabletopGameBoard4x4Block(int meta) {
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
        return "五子棋棋盘（4×4）";
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        BoardBlocks.register(IDENTIFIER, POSITION, PROPERTIES, GEOMETRIES,
                "五子棋棋盘（4×4）", TabletopGameBoard4x4Block::new);
    }
}