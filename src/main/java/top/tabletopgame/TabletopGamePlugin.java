package top.tabletopgame;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.custom.EntityManager;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import top.tabletopgame.board.TabletopGameBoard4x4Block;
import top.tabletopgame.board.TabletopGameBoardBlock;
import top.tabletopgame.entity.TabletopGameStoneEntity;
import top.tabletopgame.game.TabletopGameManager;
import top.tabletopgame.item.TabletopGameBoard4x4Item;
import top.tabletopgame.item.TabletopGameBoardItem;

/**
 * 五子棋插件入口。
 * <p>
 * 自定义实体、方块、物品必须在 {@code onLoad()} 中注册：此时 CustomBlockManager 已初始化
 * 但注册表尚未关闭，且早于 {@code EntityProperty.buildPacket()}，属性才能同步到客户端。
 */
public class TabletopGamePlugin extends PluginBase {

    private TabletopGameManager manager;

    @Override
    public void onLoad() {
        TabletopGameStoneEntity.registerProperties();
        EntityManager.get().registerDefinition(TabletopGameStoneEntity.DEF);
        TabletopGameBoardBlock.register();
        TabletopGameBoard4x4Block.register();
        Item.registerCustomItem(TabletopGameBoardItem.class);
        Item.registerCustomItem(TabletopGameBoard4x4Item.class);
        getLogger().info("已注册自定义实体 " + TabletopGameStoneEntity.IDENTIFIER
                + "、方块 " + TabletopGameBoardBlock.IDENTIFIER + " / " + TabletopGameBoard4x4Block.IDENTIFIER
                + "、物品 " + TabletopGameBoardItem.IDENTIFIER + " / " + TabletopGameBoard4x4Item.IDENTIFIER);
    }

    @Override
    public void onEnable() {
        this.manager = new TabletopGameManager(this);
        // 先加载配置，保证后续 tick 的范围检测能读到玩家识别半径
        this.manager.loadConfig();
        getServer().getPluginManager().registerEvents(this.manager, this);
        getServer().getScheduler().scheduleRepeatingTask(this, this.manager::tick, 5);
        // 插件在 STARTUP 阶段启用，此时关卡还没加载，等关卡就绪后由 tick 恢复已保存的棋盘
        this.manager.requestLoad();
        getLogger().info("TabletopGamePlugin 已启用");
    }

    @Override
    public void onDisable() {
        if (this.manager != null) {
            // 关服兜底：同步等待写盘完成
            this.manager.saveNow();
        }
        getLogger().info("TabletopGamePlugin 已关闭");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // /tabletopgame reload：重载配置文件（OP 或控制台）
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
            sender.sendMessage("§c该命令只能由玩家执行（重载配置请使用 /tabletopgame reload）。");
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage("§c你没有权限使用该命令。");
            return true;
        }
        boolean large = args.length > 0 && "4x4".equalsIgnoreCase(args[0]);
        player.getInventory().addItem(large ? new TabletopGameBoard4x4Item() : new TabletopGameBoardItem());
        player.sendMessage(large
                ? "§a已获得 4×4 五子棋棋盘物品，对准地面右键即可放置。"
                : "§a已获得五子棋棋盘物品，对准地面右键即可放置。");
        return true;
    }
}