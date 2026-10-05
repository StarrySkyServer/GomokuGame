package top.gomoku.board;

import cn.nukkit.block.custom.CustomBlockDefinition;
import cn.nukkit.block.custom.CustomBlockManager;
import cn.nukkit.block.custom.container.BlockContainerFactory;
import cn.nukkit.block.custom.container.data.Component;
import cn.nukkit.block.custom.container.data.Geometry;
import cn.nukkit.block.custom.container.data.Materials;
import cn.nukkit.block.custom.container.data.Permutation;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.block.custom.properties.IntBlockProperty;
import cn.nukkit.math.Vector3f;
import cn.nukkit.network.protocol.types.inventory.creative.CreativeItemCategory;

/**
 * 棋盘方块注册辅助。
 * <p>
 * 2x2 与 4x4 两种棋盘共用同一套客户端定义：顶面用 {@code gomoku_board_top}、侧面用
 * {@code gomoku_board_side}，并通过 {@code gomoku:position} 属性在 permutation 中切换几何模型。
 * 差异只有几何模型数量与 position 的取值范围，因此抽出此方法复用。
 */
final class BoardBlocks {

    private BoardBlocks() {
    }

    /**
     * @param identifier 方块标识符
     * @param position   position 属性（取值范围决定 permutation 数量）
     * @param properties 方块属性集
     * @param geometries 按 position 索引排列的几何模型标识符
     * @param name       方块显示名
     * @param factory    方块工厂
     */
    static void register(String identifier, IntBlockProperty position, BlockProperties properties,
                         String[] geometries, String name, BlockContainerFactory factory) {
        Materials materials = Materials.builder();
        materials.any(Materials.RenderMethod.OPAQUE, false, true, "gomoku_board_top");
        materials.up(Materials.RenderMethod.OPAQUE, false, true, "gomoku_board_top");
        // 侧面 / 底面使用边框贴图：几何模型里以 material_instance "wood" 引用
        materials.process("wood", false, true, "opaque", "gomoku_board_side");

        CustomBlockDefinition.Builder builder = CustomBlockDefinition.builder(factory.create(0))
                .name(name)
                .breakTime(99999.0)
                .collisionBox(new Vector3f(-8f, 0f, -8f), new Vector3f(16f, 2f, 16f))
                .selectionBox(new Vector3f(-7.6f, 0.1f, -7.6f), new Vector3f(15.2f, 1.3f, 15.2f))
                .creativeCategory(CreativeItemCategory.CONSTRUCTION)
                .registerCreativeItem(false)
                .geometry(new Geometry(geometries[0]))
                .materials(materials);

        for (int i = 0; i < geometries.length; i++) {
            Component component = Component.builder()
                    .geometry(new Geometry(geometries[i]))
                    .materialInstances(materials)
                    .rotation(new Vector3f(0f, 180f, 0f))
                    // 消除软边阴影：自定义方块默认 light_dampening=15
                    .lightDampening(0)
                    .build();
            builder.permutation(new Permutation(component,
                    "query.block_property('" + position.getName() + "') == " + i));
        }

        CustomBlockManager.get().registerCustomBlock(identifier, properties, builder.build(), factory);
    }
}