package top.tabletopgame.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.BlockBreakEvent;
import cn.nukkit.event.player.PlayerInteractEntityEvent;
import cn.nukkit.event.player.PlayerInteractEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.item.Item;
import cn.nukkit.level.Level;
import cn.nukkit.level.Sound;
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.yaml.snakeyaml.YamlSnakeYamlConfigurer;
import top.tabletopgame.ai.TabletopGameAi;
import top.tabletopgame.board.BaseTabletopGameBoardBlock;
import top.tabletopgame.board.TabletopGameBoard4x4Block;
import top.tabletopgame.board.TabletopGameBoardBlock;
import top.tabletopgame.config.TabletopGameConfig;
import top.tabletopgame.entity.TabletopGameStoneEntity;
import top.tabletopgame.item.TabletopGameBoard4x4Item;
import top.tabletopgame.item.TabletopGameBoardItem;
import top.tabletopgame.ui.TabletopGameMenu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理所有已放置的棋盘，处理放置、交互、范围检测与清理。
 */
public class TabletopGameManager implements Listener {

    /** 放置棋盘的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    /** 机器人“思考”停顿，让落子有节奏感（tick 数，20 tick ≈ 1 秒）。 */
    private static final int AI_THINK_DELAY_TICKS = 10;

    private final PluginBase plugin;
    private final Map<String, TabletopGameBoard> boards = new HashMap<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /**
     * 按关卡索引棋盘：准星/范围相关的高频遍历只需看玩家所在关卡，避免跨世界空转。
     * 与 {@link #boards} 同步维护（放置、破坏、读档三处）。
     */
    private final Map<Level, List<TabletopGameBoard>> boardsByLevel = new HashMap<>();

    /**
     * 每名玩家最近一次刷新高亮的服务器 tick。同一 tick 内 onMove 与周期任务会重复触发，
     * 用此去重，保证同一 tick 每人只算一次。
     */
    private final Map<UUID, Integer> hoverTick = new HashMap<>();

    /** {@link TabletopGameBoard#collectLeftPlayers(List)} 的输出缓冲，复用避免每 tick 分配。 */
    private final List<UUID> leftScratch = new ArrayList<>(4);

    /**
     * 每棋盘上一次生成的存档快照。棋盘未变更时直接复用，只有变脏的棋盘才重新读取字段与编码落子行，
     * 避免「一步棋就重编码全部棋盘」。仅主线程访问（capture 在主线程）。
     */
    private final Map<String, BoardSnapshot> snapshotCache = new HashMap<>();

    /** 棋盘状态发生变更后置位，下一次 tick 落盘，避免频繁写文件。 */
    private boolean dirty;

    /** 插件以 STARTUP 阶段启用，此时关卡尚未加载，需等关卡就绪后再恢复棋盘。 */
    private boolean loadPending;

    /** 首次恢复是否已完成。未完成前禁止写盘，避免用空数据覆盖存档。 */
    private boolean restored;

    /** 插件配置。始终非空：未加载前使用字段默认值（半径 7 格）。 */
    private TabletopGameConfig config = new TabletopGameConfig();

    /**
     * 玩家识别半径缓存。{@code inRange} 在准星/范围检测里被高频调用，这里缓存成基本类型字段，
     * 避免每次都穿过 config 的访问器；{@link #loadConfig()}/{@link #reloadConfig} 时刷新。
     */
    private double playerRange = 7.0;

    /**
     * 存档写盘线程：单线程串行执行，避免并发写文件；主线程只负责生成快照。
     * 守护线程，关服不阻塞进程退出。
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "TabletopGame-Save");
        thread.setDaemon(true);
        return thread;
    });

    public TabletopGameManager(PluginBase plugin) {
        this.plugin = plugin;
    }

    /** 当前生效的配置。 */
    public TabletopGameConfig getConfig() {
        return config;
    }

    /** 玩家识别半径（缓存值，避免高频范围检测反复读配置）。 */
    public double playerRange() {
        return playerRange;
    }

    /** 配置文件。 */
    private File configFile() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录：" + folder.getPath());
        }
        return new File(folder, "config.yml");
    }

    /**
     * 启动时同步加载配置。
     * <p>
     * 文件很小，且必须保证 {@link #getConfig()} 在首个 tick（范围检测）前就已可用，
     * 因此这里不异步；{@code /tabletopgame reload} 才走异步。
     */
    public void loadConfig() {
        this.config = readConfig();
        this.playerRange = this.config.getPlayerRange();
    }

    private TabletopGameConfig readConfig() {
        return ConfigManager.create(TabletopGameConfig.class, it -> {
            it.withConfigurer(new YamlSnakeYamlConfigurer());
            it.withBindFile(configFile());
            it.saveDefaults();
            it.load(true);
        });
    }

    /**
     * 异步重载配置：文件读取与 YAML 解析放到线程池，解析完成后回主线程替换引用并反馈结果。
     */
    public void reloadConfig(CommandSender feedback) {
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            TabletopGameConfig loaded = null;
            String error = null;
            try {
                loaded = readConfig();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final TabletopGameConfig result = loaded;
            final String failure = error;
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                if (result != null) {
                    this.config = result;
                    this.playerRange = result.getPlayerRange();
                    feedback.sendMessage("§a配置已重载：玩家识别半径 " + result.getPlayerRange()
                            + " 格；赌博抽水 " + result.getGambling().normalizedCut() + "%，下注区间 "
                            + result.getGambling().normalizedMinBet() + " ~ "
                            + result.getGambling().normalizedMaxBet() + "。");
                    plugin.getLogger().info("配置已重载（playerRange=" + result.getPlayerRange()
                            + ", cut=" + result.getGambling().normalizedCut() + "）");
                } else {
                    feedback.sendMessage("§c配置重载失败：" + failure);
                    plugin.getLogger().warning("配置重载失败：" + failure);
                }
            }, false);
        }, true);
    }

    /**
     * 静默发放战胜机器人的奖励：按等级读取配置逐项加入背包，放不下的掉落在玩家脚下。
     * <p>
     * 不发送任何提示（玩家要求奖励不播报）。本方法在主线程调用（落子流程内）。
     */
    public void giveAiReward(Player player, int level) {
        for (Map<String, Integer> entry : config.getRewards().forLevel(level)) {
            Integer id = entry.get("id");
            Integer count = entry.get("count");
            if (id == null || count == null || count <= 0) {
                continue;
            }
            Item reward = Item.get(id, 0, count);
            if (reward == null || reward.getId() == 0) {
                plugin.getLogger().warning("奖励配置中的物品 ID 无效：" + id);
                continue;
            }
            for (Item overflow : player.getInventory().addItem(reward)) {
                player.getLevel().dropItem(player, overflow);
            }
        }
    }

    /** 标记棋盘状态已变更，等待下一次 tick 统一保存。 */
    public void markDirty() {
        this.dirty = true;
    }

    /** 请求在关卡加载完成后恢复磁盘上的棋盘。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    public TabletopGameBoard findBoard(Level level, int x, int y, int z) {
        for (TabletopGameBoard board : boards.values()) {
            if (board.getLevel() == level && board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
    }

    /** 把棋盘登记进关卡索引（放置、读档后调用）。 */
    private void indexBoard(TabletopGameBoard board) {
        boardsByLevel.computeIfAbsent(board.getLevel(), k -> new ArrayList<>(4)).add(board);
    }

    /** 把棋盘从关卡索引移除（破坏后调用）。 */
    private void unindexBoard(TabletopGameBoard board) {
        List<TabletopGameBoard> list = boardsByLevel.get(board.getLevel());
        if (list != null) {
            list.remove(board);
            if (list.isEmpty()) {
                boardsByLevel.remove(board.getLevel());
            }
        }
    }

    /**
     * 注意：这里刻意不设置 {@code ignoreCancelled = true}。
     * <p>
     * 基岩触屏（默认“点击即交互”模式）点击棋盘会走 {@code Level#useItemOn}，而该路径会
     * 在派发事件之前因“出生点保护”对非 OP 玩家调用 {@code setCancelled(true)}
     * （见 {@code Level#isInSpawnRadius}，默认半径 10）。若监听器忽略已取消事件，
     * 非 OP 玩家在出生点附近点击棋盘就完全收不到回调，表现为“只有 OP 才能打开表单”。
     * <p>
     * 因此改为接收已取消事件，并在内部区分：打开菜单不受影响；放置棋盘仍遵守取消逻辑。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Item item = event.getItem();
        Block clicked = event.getBlock();
        PlayerInteractEvent.Action action = event.getAction();

        // 右键：PC 鼠标右键，或触屏对可交互方块的使用。用于放置棋盘或打开棋盘菜单。
        if (action == PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            if (item instanceof TabletopGameBoardItem || item instanceof TabletopGameBoard4x4Item) {
                // 放置棋盘会真正改动世界，仍遵守服务端取消（如出生点保护）
                if (event.isCancelled() || !canPlace(player)) {
                    event.setCancelled(true);
                    return;
                }
                tryPlace(player, event, clicked, item instanceof TabletopGameBoard4x4Item ? 4 : 2);
                return;
            }
            if (clicked instanceof BaseTabletopGameBoardBlock) {
                useBoardAt(player, event, clicked);
            }
            return;
        }

        // 左键不触发棋盘菜单：仅右键（PC 右键 / 触屏“使用”交互）打开，避免与挖掘及出生点保护冲突。
    }

    /** 命中棋盘方块时打开菜单或落子。 */
    private void useBoardAt(Player player, PlayerInteractEvent event, Block clicked) {
        TabletopGameBoard board = findBoard(player.getLevel(),
                clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
        if (board != null) {
            event.setCancelled(true);
            useBoard(player, board);
        }
    }

    /**
     * 兜底处理：若客户端仍把右键命中到棋子实体（例如命中箱同步未生效），
     * 同样按“点击棋盘”处理——对局中落子，否则弹出菜单。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getEntity() instanceof TabletopGameStoneEntity stone)) {
            return;
        }
        TabletopGameBoard board = stone.getBoard();
        if (board == null) {
            return;
        }
        event.setCancelled(true);
        useBoard(event.getPlayer(), board);
    }

    /** 同一玩家的放置冷却检查，首次通过后记录时间戳。 */
    private boolean canPlace(Player player) {
        long now = System.currentTimeMillis();
        Long last = placeCooldown.get(player.getUniqueId());
        if (last != null && now - last < PLACE_COOLDOWN_MS) {
            return false;
        }
        placeCooldown.put(player.getUniqueId(), now);
        return true;
    }

    private void tryPlace(Player player, PlayerInteractEvent event, Block clicked, int size) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = clicked.getSide(face);
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        // 区域按玩家视角确定：从放置点沿玩家左手边（P）与正前方（Q）延伸
        int rotation = rotationOf(player);
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                Block b = level.getBlock(bx + i * px + j * qx, by, bz + i * pz + j * qz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，放置棋盘需要 " + size + "x" + size + " 空地。");
                    return;
                }
            }
        }
        for (TabletopGameBoard board : boards.values()) {
            if (board.getLevel() == level && board.getBaseY() == by
                    && overlaps(board, bx, bz, size, rotation)) {
                player.sendTip("§c这里已经有棋盘了。");
                return;
            }
        }

        event.setCancelled(true);
        placeBlocks(level, bx, by, bz, size, rotation);

        // 放置音效：与橡木木板一致（Bedrock 放置方块用的就是 dig.wood，音调略低）
        double half = (size - 1) / 2.0;
        level.addSound(new Vector3(
                bx + half * (px + qx) + 0.5,
                by + 0.5,
                bz + half * (pz + qz) + 0.5), Sound.DIG_WOOD, 1.0f, 0.8f);

        TabletopGameBoard board = new TabletopGameBoard(this, level, bx, by, bz, size, rotation);
        board.spawnEntity();
        boards.put(board.getKey(), board);
        indexBoard(board);
        markDirty();

        // 创造模式放置不消耗物品
        if (!player.isCreative()) {
            Item hand = player.getInventory().getItemInHand();
            if (hand.getCount() <= 1) {
                player.getInventory().setItemInHand(Item.get(Item.AIR));
            } else {
                hand.setCount(hand.getCount() - 1);
                player.getInventory().setItemInHand(hand);
            }
        }
        player.sendTip("§a已放置" + (size >= 4 ? "4x4 " : "") + "五子棋棋盘。右键棋盘可打开菜单。");
    }

    /** 按玩家朝向取最近的 90° 朝向：0=南、1=西、2=北、3=东。 */
    private static int rotationOf(Player player) {
        return TabletopGameBoard.normalizeRotation((int) Math.round(player.getYaw() / 90.0));
    }

    /** 按边长与朝向铺设棋盘方块，position = i + j * size。 */
    private void placeBlocks(Level level, int bx, int by, int bz, int size, int rotation) {
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                BaseTabletopGameBoardBlock block = size >= 4 ? new TabletopGameBoard4x4Block() : new TabletopGameBoardBlock();
                // position = segment * 4 + rotation：segment 选择贴图象限，rotation 让方块模型跟随棋盘朝向
                block.setPosition((i + j * size) * 4 + rotation);
                level.setBlock(new Vector3(bx + i * px + j * qx, by, bz + i * pz + j * qz), block, true);
            }
        }
    }

    /** 两个棋盘的水平区域是否重叠（允许尺寸/朝向不同）。 */
    private boolean overlaps(TabletopGameBoard board, int bx, int bz, int size, int rotation) {
        int[] a = footprintBounds(board.getBaseX(), board.getBaseZ(), board.getBoardSize(), board.getRotation());
        int[] b = footprintBounds(bx, bz, size, rotation);
        return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
    }

    /** 计算棋盘区域在世界中的包围盒：{minX, maxX, minZ, maxZ}。 */
    private static int[] footprintBounds(int bx, int bz, int size, int rotation) {
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int wx = bx + i * px + j * qx;
                int wz = bz + i * pz + j * qz;
                minX = Math.min(minX, wx);
                maxX = Math.max(maxX, wx);
                minZ = Math.min(minZ, wz);
                maxZ = Math.max(maxZ, wz);
            }
        }
        return new int[]{minX, maxX, minZ, maxZ};
    }

    private void useBoard(Player player, TabletopGameBoard board) {
        if (board.isRunning()) {
            // Swap2 开局选择阶段：只有需要选择的一方点击棋盘时才弹出选择表单
            if (board.needsSwapDecision()) {
                UUID seat = board.swapDecisionSeat();
                if (seat != null && seat.equals(player.getUniqueId())) {
                    TabletopGameMenu.openSwapDecision(player, board);
                } else {
                    player.sendTip("§e请等待对手完成 Swap2 开局选择。");
                }
                return;
            }
            boolean ended = board.place(player, board.aimCell(player));
            // 摆子后若轮到某方做开局选择，自动弹出选择表单（关闭后可再次点击棋盘重开）
            if (board.needsSwapDecision()) {
                openSwapDecisionFor(board);
            } else if (ended) {
                reopenForSeats(board);
            }
        } else {
            TabletopGameMenu.open(player, board);
        }
    }

    /** 为需要做 Swap2 开局选择的一方弹出选择表单。 */
    private void openSwapDecisionFor(TabletopGameBoard board) {
        UUID seat = board.swapDecisionSeat();
        if (seat == null) {
            return;
        }
        Player decider = Server.getInstance().getPlayer(seat).orElse(null);
        if (decider != null) {
            runLater(() -> TabletopGameMenu.openSwapDecision(decider, board));
        }
    }

    private void reopenForSeats(TabletopGameBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                Server.getInstance().getScheduler().scheduleDelayedTask(plugin,
                        () -> TabletopGameMenu.open(p, board), 1);
            }
        }
    }

    /** 延迟一 tick 重新弹出指定玩家的棋盘菜单，供人机对战的分步表单使用。 */
    public void reopenMenu(Player player, TabletopGameBoard board) {
        runLater(() -> TabletopGameMenu.reopen(player, board));
    }

    /** 延后一 tick 在主线程执行，避免在表单回调中直接弹窗造成客户端窗口冲突。 */
    public void runLater(Runnable action) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    /**
     * 棋盘方块可像橡木板一样被挖掘，但任意一处破坏成功即视作整体收回：
     * 取消单块破坏，改为延迟一 tick 移除整块棋盘，并按普通方块逻辑把棋盘物品掉落在原地
     * （需玩家自行拾取；创造模式不掉落）。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof BaseTabletopGameBoardBlock)) {
            return;
        }
        event.setCancelled(true);
        // 破坏事件被取消，服务端不会广播破坏特效，这里补上橡木板的破坏音效
        block.getLevel().addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        TabletopGameBoard board = findBoard(block.getLevel(),
                block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (board == null) {
            return;
        }
        Player player = event.getPlayer();
        Level level = block.getLevel();
        Vector3 dropAt = new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5);
        boolean drop = !player.isCreative();
        boolean large = board.getBoardSize() >= 4;
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (boards.remove(board.getKey()) == null) {
                return;
            }
            unindexBoard(board);
            snapshotCache.remove(board.getKey());
            board.remove();
            markDirty();
            if (drop) {
                level.dropItem(dropAt, large ? new TabletopGameBoard4x4Item() : new TabletopGameBoardItem());
            }
        }, 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placeCooldown.remove(id);
        hoverTick.remove(id);
        for (TabletopGameBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.leave(id);
            } else {
                // 未入座但正占用「0 人入座」菜单的玩家离线：立即释放，避免他人被锁到超时
                board.releaseMenu(id);
            }
        }
    }

    /**
     * 准星移动时刷新落点高亮。落子前只有当前回合的玩家会看到高亮。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        updateHover(event.getPlayer());
    }

    /**
     * 同一 tick 内只允许刷新一次高亮：onMove（高频）与周期任务（4Hz）会对同一玩家重复触发，
     * 用服务器 tick 去重，避免同一 tick 内算两遍。返回 true 表示本次调用获得刷新权。
     */
    private boolean claimHover(Player player) {
        int now = Server.getInstance().getTick();
        Integer last = hoverTick.get(player.getUniqueId());
        if (last != null && last == now) {
            return false;
        }
        hoverTick.put(player.getUniqueId(), now);
        return true;
    }

    private void updateHover(Player player) {
        // 先按关卡过滤：玩家所在关卡没有棋盘时直接返回，连去重都不必做
        List<TabletopGameBoard> list = boardsByLevel.get(player.getLevel());
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!claimHover(player)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            TabletopGameBoard board = list.get(i);
            if (board.isRunning() && board.inRange(player)) {
                board.updateHover(player);
            }
        }
    }

    /**
     * 周期任务：范围检测（离开自动离座）。菜单不再随进入范围自动弹出，需右键棋盘打开。
     */
    public void tick() {
        if (loadPending && !Server.getInstance().getLevels().isEmpty()) {
            loadPending = false;
            load();
        }
        if (dirty) {
            dirty = false;
            save();
        }
        // 直接遍历：tick 期间只做只读计算与「离座」这类不改动 boards 结构的操作
        // （破坏棋盘走 scheduleDelayedTask，在另一次任务里执行），无需每 tick 拷贝一份列表
        for (TabletopGameBoard board : boards.values()) {
            // 高亮：只处理本棋盘当前该看高亮的那一名玩家（O(1) 查座位），不再遍历整关卡玩家表；
            // 与 onMove 共用 claimHover 去重，同一 tick 内不会重复计算
            UUID hoverId = board.hoverPlayerId();
            if (hoverId != null) {
                Player hover = Server.getInstance().getPlayer(hoverId).orElse(null);
                if (hover != null && board.inRange(hover) && claimHover(hover)) {
                    board.updateHover(hover);
                }
            }
            // 离座：只检查座位（≤2），不再扫描整关卡的玩家表
            board.collectLeftPlayers(leftScratch);
            for (int i = 0; i < leftScratch.size(); i++) {
                UUID id = leftScratch.get(i);
                board.leave(id);
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null) {
                    p.sendTip("§e你已离开棋盘范围，自动退出棋局。");
                }
            }
            // 兜底：菜单持有者超时或离开范围时，自动关闭其表单并释放占用
            board.tickMenuHolder();
            // 区块卸载会关闭非玩家实体（Nukkit 的 BaseFullChunk.unload），玩家回到范围后按需重建，
            // 否则重新进入区块会看到棋盘但棋子全部消失；重建时一并恢复棋子/最后一手/高亮。
            // 仅在实体确实缺失时才按需扫描本关卡玩家，平时不维护玩家集合
            if (board.isEntityMissing() && board.hasPlayerInRange()) {
                board.spawnEntity();
            }
            // 轮到机器人落子：停顿片刻后异步计算落点，避免阻塞主线程
            if (board.isAiPlaceTurn() && board.beginThinking()) {
                scheduleAiMove(board);
            } else if (board.needsAiSwapDecision() && board.beginThinking()) {
                scheduleAiDecision(board);
            }
        }
    }

    /**
     * 派发一次机器人落子：先延迟制造“思考”停顿，再在线程池中计算，最后回到主线程落子。
     */
    private void scheduleAiMove(TabletopGameBoard board) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            int[][] snapshot = board.copyGrid();
            int aiColor = board.aiMoveColor();
            int level = board.getAiLevel();
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                int[] move = TabletopGameAi.bestMove(snapshot, aiColor, level);
                Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                    board.finishThinking();
                    if (move != null && board.aiPlace(move[0], move[1])) {
                        reopenForSeats(board);
                    } else if (board.needsSwapDecision()) {
                        // 机器人落子后轮到对方做 Swap2 开局选择（对方是真人时才弹表单）
                        openSwapDecisionFor(board);
                    }
                }, false);
            }, true);
        }, AI_THINK_DELAY_TICKS);
    }

    /** 派发一次机器人 Swap2 开局选择：短暂停顿后直接在主线程决策。 */
    private void scheduleAiDecision(TabletopGameBoard board) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            board.finishThinking();
            board.aiSwapDecide();
        }, AI_THINK_DELAY_TICKS);
    }

    /**
     * 持久化文件：每行一条记录，用 {@code |} 分隔（世界名不会含该字符）。
     * <ul>
     *     <li>{@code B|世界|X|Y|Z|是否进行中|当前回合|胜者UUID|机器人等级|机器人棋色|棋盘边长|下注金额|赌博开关|规则|Swap2阶段|Swap2已摆子数}：一个棋盘的头部</li>
     *     <li>{@code S|玩家UUID|棋色}：一条座位记录</li>
     *     <li>{@code N|玩家UUID|名字}：一条曾记录的玩家名（离线时用于显示座位名/上一局胜者）</li>
     *     <li>{@code R|三进制行值...}：15 行落子状态</li>
     * </ul>
     */
    private File storageFile() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录：" + folder.getPath());
        }
        return new File(folder, "boards.txt");
    }

    /**
     * 主线程捕获的棋盘原始快照：只拷贝值（坐标、状态、座位、落子行编码），
     * 不持有 {@link TabletopGameBoard} 引用，因此可以安全地交给异步线程做字符串格式化。
     */
    private static final class BoardSnapshot {
        final String level;
        final int x;
        final int y;
        final int z;
        final int boardSize;
        final boolean running;
        final int turn;
        final UUID winner;
        final int aiLevel;
        final int aiColor;
        final double betAmount;
        final boolean gamblingEnabled;
        final int rule;
        final int swapPhase;
        final int swapPlaced;
        final int rotation;
        final List<UUID> seats;
        final List<Integer> colors;
        final Map<UUID, String> names;
        final int[] rows;

        BoardSnapshot(TabletopGameBoard board) {
            this.level = board.getLevel().getName();
            this.x = board.getBaseX();
            this.y = board.getBaseY();
            this.z = board.getBaseZ();
            this.boardSize = board.getBoardSize();
            this.running = board.isRunning();
            this.turn = board.getTurn();
            this.winner = board.getWinner();
            this.aiLevel = board.getAiLevel();
            this.aiColor = board.getAiColor();
            this.betAmount = board.getBetAmount();
            this.gamblingEnabled = board.isGamblingEnabled();
            this.rule = board.getRule();
            this.swapPhase = board.getSwapPhase();
            this.swapPlaced = board.getSwapPlaced();
            this.rotation = board.getRotation();
            this.seats = new ArrayList<>(board.getSeats());
            this.colors = new ArrayList<>(seats.size());
            for (UUID id : seats) {
                this.colors.add(board.seatColor(id));
            }
            this.names = board.copyKnownNames();
            this.rows = board.exportRows();
        }
    }

    /**
     * 在主线程把当前所有棋盘拷贝成不可变快照。
     * <p>
     * 只做字段读取与行编码（每行 15 次乘加），字符串拼接与 UUID 格式化留到异步线程，
     * 尽量缩短主线程在存档上的停留时间。
     * <p>
     * 只有自上次快照后发生过变更（{@link TabletopGameBoard#consumeStateDirty()}）的棋盘才重新读取字段、
     * 重编码落子行；未变更的棋盘直接复用 {@link #snapshotCache} 里的上一份快照。快照本身不可变
     * （座位/名字/落子行都是拷贝），因此可安全地在多次落盘与异步格式化之间共享。
     */
    private List<BoardSnapshot> capture() {
        List<BoardSnapshot> snapshots = new ArrayList<>(boards.size());
        for (TabletopGameBoard board : boards.values()) {
            boolean changed = board.consumeStateDirty();
            String key = board.getKey();
            BoardSnapshot cached = snapshotCache.get(key);
            if (cached == null || changed) {
                cached = new BoardSnapshot(board);
                snapshotCache.put(key, cached);
            }
            snapshots.add(cached);
        }
        return snapshots;
    }

    /** 把快照格式化为存档文本行（纯计算，可在异步线程执行）。 */
    private static List<String> format(List<BoardSnapshot> snapshots) {
        List<String> lines = new ArrayList<>(snapshots.size() * 3);
        for (BoardSnapshot s : snapshots) {
            lines.add("B|" + s.level + "|" + s.x + "|" + s.y + "|" + s.z
                    + "|" + s.running + "|" + s.turn
                    + "|" + (s.winner == null ? "" : s.winner)
                    + "|" + s.aiLevel + "|" + s.aiColor + "|" + s.boardSize
                    + "|" + s.betAmount + "|" + s.gamblingEnabled
                    + "|" + s.rule + "|" + s.swapPhase + "|" + s.swapPlaced + "|" + s.rotation);
            for (int i = 0; i < s.seats.size(); i++) {
                lines.add("S|" + s.seats.get(i) + "|" + s.colors.get(i));
            }
            for (Map.Entry<UUID, String> e : s.names.entrySet()) {
                lines.add("N|" + e.getKey() + "|" + e.getValue());
            }
            StringBuilder row = new StringBuilder("R");
            for (int value : s.rows) {
                row.append('|').append(value);
            }
            lines.add(row.toString());
        }
        return lines;
    }

    /**
     * 把当前所有棋盘写入磁盘。
     * <p>
     * 主线程只捕获原始快照，字符串格式化与实际写盘都交给单线程串行执行，
     * 避免每步棋都占用主线程做序列化与磁盘 IO。
     */
    public void save() {
        if (!restored || saveExecutor.isShutdown()) {
            return;
        }
        List<BoardSnapshot> snapshots = capture();
        File file = storageFile();
        saveExecutor.execute(() -> writeLines(file, format(snapshots)));
    }

    /** 关服兜底：同步排队写盘并等待队列中已有的写入全部完成。 */
    public void saveNow() {
        if (!restored) {
            return;
        }
        List<BoardSnapshot> snapshots = capture();
        File file = storageFile();
        if (saveExecutor.isShutdown()) {
            writeLines(file, format(snapshots));
            return;
        }
        saveExecutor.execute(() -> writeLines(file, format(snapshots)));
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("等待棋盘数据写入超时。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLines(File file, List<String> lines) {
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("保存棋盘数据失败：" + e.getMessage());
        }
    }

    /**
     * 启动时从磁盘恢复棋盘：重建棋盘对象、按坐标重新放置方块并生成棋子实体。
     * <p>
     * 读文件与解析是纯 IO/计算，放到异步线程；解析出纯数据后再回主线程重建方块与实体
     * （放置方块、生成实体属于改世界，必须在主线程）。
     * 加载完成前 {@code restored} 保持 false，写盘会被跳过，避免用空数据覆盖存档。
     */
    public void load() {
        File file = storageFile();
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            List<SavedBoard> saved = parse(file);
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                for (SavedBoard data : saved) {
                    restoreBoard(data);
                }
                restored = true;
                if (!saved.isEmpty()) {
                    plugin.getLogger().info("已从磁盘恢复 " + boards.size() + " 个五子棋棋盘。");
                }
            }, false);
        }, true);
    }

    /** 读取并解析存档为纯数据（不触碰世界，可在异步线程执行）。 */
    private List<SavedBoard> parse(File file) {
        List<SavedBoard> saved = new ArrayList<>();
        if (!file.isFile()) {
            return saved;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("读取棋盘数据失败：" + e.getMessage());
            return saved;
        }
        SavedBoard current = null;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if ("B".equals(parts[0])) {
                current = new SavedBoard(parts);
                saved.add(current);
            } else if ("S".equals(parts[0]) && current != null && parts.length >= 3) {
                UUID id = parseUuid(parts[1]);
                if (id != null) {
                    current.seats.add(id);
                    current.colors.put(id, parseInt(parts[2], TabletopGameBoard.BLACK));
                }
            } else if ("N".equals(parts[0]) && current != null && parts.length >= 3) {
                UUID id = parseUuid(parts[1]);
                if (id != null && !parts[2].isEmpty()) {
                    current.names.put(id, parts[2]);
                }
            } else if ("R".equals(parts[0]) && current != null) {
                current.rows = new int[TabletopGameBoard.SIZE];
                for (int i = 0; i < current.rows.length && i + 1 < parts.length; i++) {
                    current.rows[i] = parseInt(parts[i + 1], 0);
                }
            }
        }
        return saved;
    }

    private void restoreBoard(SavedBoard data) {
        Level level = Server.getInstance().getLevelByName(data.level);
        if (level == null) {
            plugin.getLogger().warning("棋盘所在世界未加载，跳过：" + data.level);
            return;
        }
        TabletopGameBoard board = new TabletopGameBoard(this, level, data.x, data.y, data.z, data.boardSize, data.rotation);
        board.restore(data.seats, data.colors, data.names,
                data.rows != null ? data.rows : new int[TabletopGameBoard.SIZE],
                data.running, data.turn, data.winner, data.betAmount, data.gamblingEnabled,
                data.rule, data.swapPhase, data.swapPlaced);
        board.restoreAi(data.aiLevel, data.aiColor);
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保棋盘方块仍然有效
        placeBlocks(level, data.x, data.y, data.z, data.boardSize, data.rotation);
        board.spawnEntity();
        boards.put(board.getKey(), board);
        indexBoard(board);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 磁盘上的一条棋盘记录。 */
    private static final class SavedBoard {
        final String level;
        final int x;
        final int y;
        final int z;
        final boolean running;
        final int turn;
        final UUID winner;
        final int aiLevel;
        final int aiColor;
        final int boardSize;
        final double betAmount;
        final boolean gamblingEnabled;
        final int rule;
        final int swapPhase;
        final int swapPlaced;
        final int rotation;
        final List<UUID> seats = new ArrayList<>();
        final Map<UUID, Integer> colors = new HashMap<>();
        final Map<UUID, String> names = new LinkedHashMap<>();
        int[] rows;

        SavedBoard(String[] parts) {
            this.level = parts.length > 1 ? parts[1] : "";
            this.x = parseInt(parts.length > 2 ? parts[2] : "0", 0);
            this.y = parseInt(parts.length > 3 ? parts[3] : "0", 0);
            this.z = parseInt(parts.length > 4 ? parts[4] : "0", 0);
            this.running = parts.length > 5 && Boolean.parseBoolean(parts[5]);
            this.turn = parseInt(parts.length > 6 ? parts[6] : "1", TabletopGameBoard.BLACK);
            this.winner = parts.length > 7 ? parseUuid(parts[7]) : null;
            this.aiLevel = parseInt(parts.length > 8 ? parts[8] : "0", 0);
            this.aiColor = parseInt(parts.length > 9 ? parts[9] : "0", 0);
            this.boardSize = parseInt(parts.length > 10 ? parts[10] : "2", 2);
            this.betAmount = parseDouble(parts.length > 11 ? parts[11] : "0", 0);
            this.gamblingEnabled = parts.length > 12 && Boolean.parseBoolean(parts[12]);
            this.rule = parseInt(parts.length > 13 ? parts[13] : "0", TabletopGameBoard.RULE_CASUAL);
            this.swapPhase = parseInt(parts.length > 14 ? parts[14] : "0", 0);
            this.swapPlaced = parseInt(parts.length > 15 ? parts[15] : "0", 0);
            this.rotation = parseInt(parts.length > 16 ? parts[16] : "0", 0);
        }
    }
}