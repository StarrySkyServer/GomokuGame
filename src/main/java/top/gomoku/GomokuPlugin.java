package top.gomoku;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.custom.EntityManager;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import top.gomoku.board.GomokuBoardBlock;
import top.gomoku.entity.GomokuStoneEntity;
import top.gomoku.game.GomokuManager;
import top.gomoku.item.GomokuBoardItem;

/**
 * 五子棋插件入口。
 * <p>
 * 自定义实体、方块、物品必须在 {@code onLoad()} 中注册：此时 CustomBlockManager 已初始化
 * 但注册表尚未关闭，且早于 {@code EntityProperty.buildPacket()}，属性才能同步到客户端。
 */
public class GomokuPlugin extends PluginBase {

    private GomokuManager manager;

    @Override
    public void onLoad() {
        GomokuStoneEntity.registerProperties();
        EntityManager.get().registerDefinition(GomokuStoneEntity.DEF);
        GomokuBoardBlock.register();
        Item.registerCustomItem(GomokuBoardItem.class);
        getLogger().info("已注册自定义实体 " + GomokuStoneEntity.IDENTIFIER
                + "、方块 " + GomokuBoardBlock.IDENTIFIER
                + "、物品 " + GomokuBoardItem.IDENTIFIER);
    }

    @Override
    public void onEnable() {
        this.manager = new GomokuManager(this);
        getServer().getPluginManager().registerEvents(this.manager, this);
        getServer().getScheduler().scheduleRepeatingTask(this, this.manager::tick, 5);
        // 插件在 STARTUP 阶段启用，此时关卡还没加载，等关卡就绪后由 tick 恢复已保存的棋盘
        this.manager.requestLoad();
        getLogger().info("GomokuPlugin 已启用");
    }

    @Override
    public void onDisable() {
        if (this.manager != null) {
            this.manager.save();
        }
        getLogger().info("GomokuPlugin 已关闭");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§c你没有权限使用该命令。");
            return true;
        }
        player.getInventory().addItem(new GomokuBoardItem());
        player.sendMessage("§a已获得五子棋棋盘物品，对准地面右键即可放置。");
        return true;
    }
}