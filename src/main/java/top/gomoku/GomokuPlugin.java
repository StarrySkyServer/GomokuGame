package top.gomoku;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.custom.EntityManager;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import top.gomoku.board.GomokuBoard4x4Block;
import top.gomoku.board.GomokuBoardBlock;
import top.gomoku.entity.GomokuStoneEntity;
import top.gomoku.game.GomokuManager;
import top.gomoku.item.GomokuBoard4x4Item;
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
        GomokuBoard4x4Block.register();
        Item.registerCustomItem(GomokuBoardItem.class);
        Item.registerCustomItem(GomokuBoard4x4Item.class);
        getLogger().info("已注册自定义实体 " + GomokuStoneEntity.IDENTIFIER
                + "、方块 " + GomokuBoardBlock.IDENTIFIER + " / " + GomokuBoard4x4Block.IDENTIFIER
                + "、物品 " + GomokuBoardItem.IDENTIFIER + " / " + GomokuBoard4x4Item.IDENTIFIER);
    }

    @Override
    public void onEnable() {
        this.manager = new GomokuManager(this);
        // 先加载配置，保证后续 tick 的范围检测能读到玩家识别半径
        this.manager.loadConfig();
        getServer().getPluginManager().registerEvents(this.manager, this);
        getServer().getScheduler().scheduleRepeatingTask(this, this.manager::tick, 5);
        // 插件在 STARTUP 阶段启用，此时关卡还没加载，等关卡就绪后由 tick 恢复已保存的棋盘
        this.manager.requestLoad();
        getLogger().info("GomokuPlugin 已启用");
    }

    @Override
    public void onDisable() {
        if (this.manager != null) {
            // 关服兜底：同步等待写盘完成
            this.manager.saveNow();
        }
        getLogger().info("GomokuPlugin 已关闭");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /gomoku reload：重载配置文件（OP 或控制台）
        if (args.length > 0 && "reload".equalsIgnoreCase(args[0])) {
            if (!sender.isOp()) {
                sender.sendMessage("§c你没有权限使用该命令。");
                return true;
            }
            sender.sendMessage("§7正在重载配置…");
            this.manager.reloadConfig(sender);
            return true;
        }

        // 其余用法：发放棋盘物品，仅玩家可用
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行（重载配置请使用 /gomoku reload）。");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§c你没有权限使用该命令。");
            return true;
        }
        boolean large = args.length > 0 && "4x4".equalsIgnoreCase(args[0]);
        player.getInventory().addItem(large ? new GomokuBoard4x4Item() : new GomokuBoardItem());
        player.sendMessage(large
                ? "§a已获得 4×4 五子棋棋盘物品，对准地面右键即可放置。"
                : "§a已获得五子棋棋盘物品，对准地面右键即可放置。");
        return true;
    }
}