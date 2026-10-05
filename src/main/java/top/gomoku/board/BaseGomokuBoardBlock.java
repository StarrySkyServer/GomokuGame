package top.gomoku.board;

import cn.nukkit.Player;
import cn.nukkit.block.custom.container.CustomBlockMeta;
import cn.nukkit.block.custom.properties.BlockProperties;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.item.Item;

/**
 * 五子棋棋盘方块基类。
 * <p>
 * 所有尺寸的棋盘共享同一套行为：不可破坏、右键打开菜单、左键 / 触屏长按一律视为触摸交互。
 */
public abstract class BaseGomokuBoardBlock extends CustomBlockMeta {

    protected BaseGomokuBoardBlock(String identifier, BlockProperties properties, int meta) {
        super(identifier, properties, meta);
    }

    /** 设置该方块在整块棋盘拼接中的序号（决定显示棋盘贴图的哪一份）。 */
    public abstract void setPosition(int position);

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
}