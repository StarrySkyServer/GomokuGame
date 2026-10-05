package top.gomoku.board;

import cn.nukkit.Player;
import cn.nukkit.block.custom.CustomBlockDefinition;
import cn.nukkit.block.custom.CustomBlockManager;
import cn.nukkit.block.custom.container.CustomBlockMeta;
import cn.nukkit.block.custom.container.data.Component;
import cn.nukkit.block.custom.container.data.Geometry;
import cn.nukkit.block.custom.container.data.Materials;
import cn.nukkit.block.custom.container.data.Permutation;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 棋盘方块，对应资源包中的 gomoku:board。
 * <p>
 * 通过 {@code gomoku:position} 属性（0..3）选择 2x2 拼接中的四分之一模型。
 * position = dx + dz * 2，其中 dx/dz 为该方块在 2x2 区域中的偏移。
 */
public class GomokuBoardBlock extends CustomBlockMeta {

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
     * 硬度 -1 与服务端语义一致：不可破坏（同基岩）。
     */
    @Override
    public double getHardness() {
        return -1;
    }

    /**
     * 棋盘方块不可被任何工具挖掘（同基岩），收回只能通过菜单“收起棋盘”。
     */
    @Override
    public boolean isBreakable(Item item) {
        return false;
    }

    @Override
    public double getResistance() {
        return 3.0;
    }

    @Override
    public boolean canBeActivated() {
        return true;
    }

    /**
     * 棋盘不接受任何挖掘操作：左键 / 触屏长按一律视为“触摸交互”。
     * <p>
     * 返回非 0 会让服务端跳过挖掘进度与裂纹动画（见 Player#onBlockBreakStart 与 Level#useItemOn），
     * 同时也不会把棋盘方块当作可破坏方块处理。
     */
    @Override
    public int onTouch(Player player, PlayerInteractEvent.Action action) {
        return 1;
    }

    /**
     * 注册自定义方块。必须在 CustomBlockManager 关闭注册表之前调用（插件 onLoad / STARTUP）。
     */
    public static void register() {
        // ambient_occlusion=false：棋盘是薄板模型，开启 AO 会让方块向四周与下方投射接触阴影
        // （官方文档：ambient_occlusion 为 true 时会在方块周围及下方产生阴影）。
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, "gomoku_board_top");
        materials.up(Materials.RenderMethod.OPAQUE, false, true, "gomoku_board_top");
        // 侧面与底面使用木纹材质（几何模型中 material_instance 名为 wood）
        materials.process("wood", false, true, "opaque", "gomoku_board_side");

        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(new GomokuBoardBlock())
                .name("五子棋棋盘")
                // 客户端侧挖掘时间设为极大值，方块无法被挖掘，不再出现裂纹与回弹（同基岩）
                .breakTime(99999)
                .collisionBox(new Vector3f(-8, 0, -8), new Vector3f(16, 2, 16))
                // 选中框（准星瞄准时的高亮线框）整体缩进到模型内部：模型本体为
                // origin(-8,0,-8) size(16,1.5,16)，这里取 (-7.6,0.1,-7.6) size(15.2,1.3,15.2)，
                // 使线框被不透明模型遮挡，从而瞄准棋盘时不再出现挡视野的轮廓。
                // 仍然保留完整覆盖棋盘顶面的命中范围，射线照样能选中方块。
                .selectionBox(new Vector3f(-7.6f, 0.1f, -7.6f), new Vector3f(15.2f, 1.3f, 15.2f))
                .creativeCategory(CreativeItemCategory.CONSTRUCTION)
                .registerCreativeItem(false)
                .geometry(new Geometry(GEOMETRIES[0]))
                .materials(materials);

        for (int position = 0; position < GEOMETRIES.length; position++) {
            Component component = Component.builder()
                    .geometry(new Geometry(GEOMETRIES[position]))
                    .materialInstances(materials)
                    // 单个 1x1 象限模型顶面 UV 相对棋盘整体旋转了 180°，逐格旋转回正
                    .rotation(new Vector3f(0, 180, 0))
                    // 关键：Nukkit 对非透明自定义方块默认写入 light_dampening=15（见
                    // Block#computeCustomBlockLightFilter），方块会完全遮挡天空光，顶面被平滑光照
                    // 渲染成中间暗、四周亮的软边阴影。置 0 后棋盘不再挡光，阴影消失。
                    .lightDampening(0)
                    .build();
            builder.permutation(new Permutation(component,
                    "query.block_property('" + POSITION.getName() + "') == " + position));
        }

        CustomBlockManager.get().registerCustomBlock(
                IDENTIFIER,
                PROPERTIES,
                builder.build(),
                GomokuBoardBlock::new);
    }
}