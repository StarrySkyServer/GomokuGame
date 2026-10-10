package top.tabletopgame;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.entity.custom.EntityManager;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import top.tabletopgame.board.OthelloBoardBlock;
import top.tabletopgame.board.TabletopGameBoard3x3Block;
import top.tabletopgame.board.TabletopGameBoardBlock;
import top.tabletopgame.board.XiangqiBoardBlock;
import top.tabletopgame.entity.OthelloDiscEntity;
import top.tabletopgame.entity.TabletopGameStoneBlackEntity;
import top.tabletopgame.entity.TabletopGameStoneWhiteEntity;
import top.tabletopgame.entity.XiangqiPieceEntity;
import top.tabletopgame.game.OthelloManager;
import top.tabletopgame.game.TabletopGameManager;
import top.tabletopgame.game.XiangqiManager;
import top.tabletopgame.item.OthelloBoardItem;
import top.tabletopgame.item.TabletopGameBoard3x3Item;
import top.tabletopgame.item.TabletopGameBoardItem;
import top.tabletopgame.item.XiangqiBoardItem;

/**
 * 五子棋插件入口。
 * <p>
 * 自定义实体、方块、物品必须在 {@code onLoad()} 中注册：此时 CustomBlockManager 已初始化
 * 但注册表尚未关闭，且早于 {@code EntityProperty.buildPacket()}，属性才能同步到客户端。
 */
public class TabletopGamePlugin extends PluginBase {

    private TabletopGameManager manager;
    private XiangqiManager xiangqiManager;
    private OthelloManager othelloManager;

    @Override
    public void onLoad() {
        TabletopGameStoneBlackEntity.registerProperties();
        EntityManager.get().registerDefinition(TabletopGameStoneBlackEntity.DEF);
        TabletopGameStoneWhiteEntity.registerProperties();
        EntityManager.get().registerDefinition(TabletopGameStoneWhiteEntity.DEF);
        XiangqiPieceEntity.registerProperties();
        EntityManager.get().registerDefinition(XiangqiPieceEntity.DEF);
        OthelloDiscEntity.registerProperties();
        EntityManager.get().registerDefinition(OthelloDiscEntity.DEF);
        TabletopGameBoardBlock.register();
        TabletopGameBoard3x3Block.register();
        XiangqiBoardBlock.register();
        OthelloBoardBlock.register();
        Item.registerCustomItem(TabletopGameBoardItem.class);
        Item.registerCustomItem(TabletopGameBoard3x3Item.class);
        Item.registerCustomItem(XiangqiBoardItem.class);
        Item.registerCustomItem(OthelloBoardItem.class);
        getLogger().info("已注册自定义实体 " + TabletopGameStoneBlackEntity.IDENTIFIER
                + " / " + TabletopGameStoneWhiteEntity.IDENTIFIER
                + " / " + XiangqiPieceEntity.IDENTIFIER
                + " / " + OthelloDiscEntity.IDENTIFIER
                + "、方块 " + TabletopGameBoardBlock.IDENTIFIER + " / " + TabletopGameBoard3x3Block.IDENTIFIER
                + " / " + XiangqiBoardBlock.IDENTIFIER
                + " / " + OthelloBoardBlock.IDENTIFIER
                + "、物品 " + TabletopGameBoardItem.IDENTIFIER + " / " + TabletopGameBoard3x3Item.IDENTIFIER
                + " / " + XiangqiBoardItem.IDENTIFIER
                + " / " + OthelloBoardItem.IDENTIFIER);
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

        // 中国象棋：复用五子棋的配置（玩家识别半径），存档走独立文件
        this.xiangqiManager = new XiangqiManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.xiangqiManager, this);
        getServer().getScheduler().scheduleRepeatingTask(this, this.xiangqiManager::tick, 5);
        this.xiangqiManager.requestLoad();

        // 黑白棋（奥赛罗）：复用五子棋的配置，存档走独立文件
        this.othelloManager = new OthelloManager(this, this.manager);
        getServer().getPluginManager().registerEvents(this.othelloManager, this);
        getServer().getScheduler().scheduleRepeatingTask(this, this.othelloManager::tick, 5);
        this.othelloManager.requestLoad();
        getLogger().info("TabletopGamePlugin 已启用");
    }

    @Override
    public void onDisable() {
        if (this.manager != null) {
            // 关服兜底：同步等待写盘完成
            this.manager.saveNow();
        }
        if (this.xiangqiManager != null) {
            this.xiangqiManager.saveNow();
        }
        if (this.othelloManager != null) {
            this.othelloManager.saveNow();
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
        boolean medium = args.length > 0 && "3x3".equalsIgnoreCase(args[0]);
        boolean xiangqi = args.length > 0 && "xiangqi".equalsIgnoreCase(args[0]);
        boolean othello = args.length > 0 && "othello".equalsIgnoreCase(args[0]);
        Item board;
        String sizeLabel;
        if (xiangqi) {
            board = new XiangqiBoardItem();
            sizeLabel = "中国象棋";
        } else if (othello) {
            board = new OthelloBoardItem();
            sizeLabel = "黑白棋";
        } else if (medium) {
            board = new TabletopGameBoard3x3Item();
            sizeLabel = "3×3";
        } else {
            board = new TabletopGameBoardItem();
            sizeLabel = "";
        }
        player.getInventory().addItem(board);
        player.sendMessage("§a已获得" + sizeLabel + "棋盘物品，对准地面右键即可放置。");
        return true;
    }
}