package top.tabletopgame.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.block.Block;
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
import top.tabletopgame.ai.XiangqiAi;
import top.tabletopgame.board.XiangqiBoardBlock;
import top.tabletopgame.config.TabletopGameConfig;
import top.tabletopgame.entity.XiangqiPieceEntity;
import top.tabletopgame.item.XiangqiBoardItem;
import top.tabletopgame.ui.XiangqiMenu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 管理所有已放置的中国象棋棋盘：放置、破坏收回、菜单、交互、准星高亮、机器人派发与存档恢复。
 * <p>
 * 骨架对齐 {@link TabletopGameManager}，但走独立存档文件 {@code xiangqi_boards.txt}。
 */
public class XiangqiManager implements Listener {

    /** 放置棋盘的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    private final PluginBase plugin;

    /** 复用五子棋的玩家识别半径（同一个 config.yml，不重复加载配置）。 */
    private final TabletopGameManager gameManager;

    private final Map<String, XiangqiBoard> boards = new HashMap<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /**
     * 每名玩家最近一次刷新高亮的服务器 tick。同一 tick 内 onMove 与周期任务会重复触发，
     * 用此去重，保证同一 tick 每人只算一次。
     */
    private final Map<UUID, Integer> hoverTick = new HashMap<>();

    /** 按关卡索引棋盘，范围检测只需看玩家所在关卡。 */
    private final Map<Level, List<XiangqiBoard>> boardsByLevel = new HashMap<>();

    /** {@link XiangqiBoard#collectLeftPlayers(List)} 的输出缓冲，复用避免每 tick 分配。 */
    private final List<UUID> leftScratch = new ArrayList<>(4);

    /** 棋盘增删后置位，下一次 tick 落盘，避免频繁写文件。 */
    private boolean dirty;

    /** 插件以 STARTUP 阶段启用，此时关卡尚未加载，需等关卡就绪后再恢复棋盘。 */
    private boolean loadPending;

    /** 首次恢复是否已完成。未完成前禁止写盘，避免用空数据覆盖存档。 */
    private boolean restored;

    /** 存档写盘线程：单线程串行执行，守护线程，关服不阻塞进程退出。 */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Xiangqi-Save");
        thread.setDaemon(true);
        return thread;
    });

    public XiangqiManager(PluginBase plugin, TabletopGameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    /** 玩家识别半径（棋子区块卸载后按需重建时使用）。 */
    public double playerRange() {
        return gameManager.playerRange();
    }

    /** 插件配置（含赌博模式设置），供棋盘读取下注区间与抽水比例。 */
    public TabletopGameConfig config() {
        return gameManager.getConfig();
    }

    /** 「0 人入座」菜单占用超时（毫秒），沿用五子棋配置 {@code menuLockSeconds}。 */
    long menuLockMs() {
        return gameManager.getConfig().getMenuLockSeconds() * 1000L;
    }

    /** 请求在关卡加载完成后恢复磁盘上的棋盘。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    /** 标记存档已变更，等待下一次 tick 统一保存。包级可见，供同包的 {@link XiangqiBoard} 调用。 */
    void markDirty() {
        this.dirty = true;
    }

    private void indexBoard(XiangqiBoard board) {
        boardsByLevel.computeIfAbsent(board.getLevel(), k -> new ArrayList<>(4)).add(board);
    }

    private void unindexBoard(XiangqiBoard board) {
        List<XiangqiBoard> list = boardsByLevel.get(board.getLevel());
        if (list != null) {
            list.remove(board);
            if (list.isEmpty()) {
                boardsByLevel.remove(board.getLevel());
            }
        }
    }

    /** 命中棋盘方块所在的棋盘（破坏时使用）。 */
    private XiangqiBoard findBoard(Level level, int x, int y, int z) {
        List<XiangqiBoard> list = boardsByLevel.get(level);
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            XiangqiBoard board = list.get(i);
            if (board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
    }

    /**
     * 注意：与五子棋一致，这里刻意不设置 {@code ignoreCancelled = true}。
     * <p>
     * 基岩触屏点击会走 {@code Level#useItemOn}，该路径可能在派发事件前因出生点保护
     * 对非 OP 玩家调用 {@code setCancelled(true)}；忽略已取消事件会导致出生点附近
     * 完全收不到回调。放置棋盘仍遵守取消逻辑（内部再判断）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getItem() instanceof XiangqiBoardItem) {
            if (event.isCancelled() || !canPlace(player)) {
                event.setCancelled(true);
                return;
            }
            tryPlace(player, event);
            return;
        }
        // 右键命中棋盘方块：对局中选子/落子，否则弹出菜单
        Block clicked = event.getBlock();
        if (clicked instanceof XiangqiBoardBlock) {
            event.setCancelled(true);
            XiangqiBoard board = findBoard(player.getLevel(),
                    clicked.getFloorX(), clicked.getFloorY(), clicked.getFloorZ());
            if (board != null) {
                useBoard(player, board);
            }
        }
    }

    /** 命中棋盘方块：未开局弹菜单，对局中执行选子/落子状态机。 */
    private void useBoard(Player player, XiangqiBoard board) {
        if (!board.isRunning()) {
            XiangqiMenu.open(player, board);
            return;
        }
        if (!board.isSeated(player.getUniqueId())) {
            player.sendTip("§c你不是本局玩家。");
            return;
        }
        if (board.seatColor(player.getUniqueId()) != board.getTurn()) {
            player.sendTip("§e请等待对手行棋。");
            return;
        }
        String tip = board.select(player);
        if (tip != null) {
            player.sendTip(tip);
        }
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

    private void tryPlace(Player player, PlayerInteractEvent event) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = event.getBlock().getSide(face);
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        // 区域按玩家视角确定：从放置点沿玩家左手边（P）与正前方（Q）延伸
        int rotation = TabletopGameBoard.normalizeRotation((int) Math.round(player.getYaw() / 90.0));
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                Block b = level.getBlock(bx + i * px + j * qx, by, bz + i * pz + j * qz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendTip("§c空间不足，放置棋盘需要 2x2 空地。");
                    return;
                }
            }
        }
        List<XiangqiBoard> sameLevel = boardsByLevel.get(level);
        if (sameLevel != null) {
            for (int i = 0; i < sameLevel.size(); i++) {
                XiangqiBoard board = sameLevel.get(i);
                if (board.getBaseY() == by && overlaps(board, bx, bz, rotation)) {
                    player.sendTip("§c这里已经有棋盘了。");
                    return;
                }
            }
        }

        event.setCancelled(true);
        placeBlocks(level, bx, by, bz, rotation);

        // 放置音效：与橡木木板一致
        level.addSound(new Vector3(
                bx + 0.5 * (px + qx) + 0.5,
                by + 0.5,
                bz + 0.5 * (pz + qz) + 0.5), Sound.DIG_WOOD, 1.0f, 0.8f);

        XiangqiBoard board = new XiangqiBoard(this, level, bx, by, bz, rotation);
        board.spawnPieces();
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
        player.sendTip("§a已放置中国象棋棋盘。");
    }

    /** 按朝向铺设 2x2 棋盘方块，position = (i + j * 2) * 4 + rotation。 */
    private void placeBlocks(Level level, int bx, int by, int bz, int rotation) {
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                XiangqiBoardBlock block = new XiangqiBoardBlock();
                block.setPosition((i + j * 2) * 4 + rotation);
                level.setBlock(new Vector3(bx + i * px + j * qx, by, bz + i * pz + j * qz), block, true);
            }
        }
    }

    /** 两个棋盘的水平区域是否重叠（允许朝向不同）。 */
    private static boolean overlaps(XiangqiBoard board, int bx, int bz, int rotation) {
        int[] a = footprintBounds(board.getBaseX(), board.getBaseZ(), board.getRotation());
        int[] b = footprintBounds(bx, bz, rotation);
        return a[0] <= b[1] && b[0] <= a[1] && a[2] <= b[3] && b[2] <= a[3];
    }

    /** 计算 2x2 棋盘区域在世界中的包围盒：{minX, maxX, minZ, maxZ}。 */
    private static int[] footprintBounds(int bx, int bz, int rotation) {
        int px = TabletopGameBoard.axisPX(rotation);
        int pz = TabletopGameBoard.axisPZ(rotation);
        int qx = TabletopGameBoard.axisQX(rotation);
        int qz = TabletopGameBoard.axisQZ(rotation);
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
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

    /**
     * 棋盘方块可像橡木板一样被挖掘，但任意一处破坏成功即视作整体收回：
     * 取消单块破坏，改为延迟一 tick 移除整块棋盘（含 32 枚棋子），
     * 并按普通方块逻辑把棋盘物品掉落在原地（需玩家自行拾取；创造模式不掉落）。
     * <p>
     * {@link TabletopGameManager#onBreak} 对 {@link XiangqiBoardBlock} 已提前返回，
     * 不会抢先取消事件或重复播放音效。
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block instanceof XiangqiBoardBlock)) {
            return;
        }
        event.setCancelled(true);
        // 破坏事件被取消，服务端不会广播破坏特效，这里补上橡木板的破坏音效
        Level level = block.getLevel();
        level.addSound(
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.5, block.getFloorZ() + 0.5),
                Sound.DIG_WOOD, 1.0f, 1.0f);
        XiangqiBoard board = findBoard(level, block.getFloorX(), block.getFloorY(), block.getFloorZ());
        if (board == null) {
            return;
        }
        retract(event.getPlayer(), board,
                new Vector3(block.getFloorX() + 0.5, block.getFloorY() + 0.25, block.getFloorZ() + 0.5));
    }

    /**
     * 右键命中棋子时吞掉事件：本阶段棋子没有任何交互，避免右键被实体抢走
     * （与五子棋的 {@code TabletopGameManager#onInteractEntity} 同理）。
     * <p>
     * 左键不需要兜底：棋子实体的命中箱已被压到 0.001（见 {@link XiangqiPieceEntity}），
     * 准星射线会直接穿过棋子命中棋盘方块，挖掘照常。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getEntity() instanceof XiangqiPieceEntity) {
            event.setCancelled(true);
        }
    }

    /** 整体收回一个棋盘：延迟一 tick 移除棋子与方块，并按需掉落棋盘物品。 */
    private void retract(Player player, XiangqiBoard board, Vector3 dropAt) {
        boolean drop = !player.isCreative();
        Level level = board.getLevel();
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> {
            if (boards.remove(board.getKey()) == null) {
                return;
            }
            unindexBoard(board);
            board.remove();
            markDirty();
            if (drop) {
                level.dropItem(dropAt, new XiangqiBoardItem());
            }
        }, 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placeCooldown.remove(id);
        hoverTick.remove(id);
        for (XiangqiBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.leave(id);
            } else {
                // 未入座但正占用「0 人入座」菜单的玩家离线：立即释放，避免他人被锁到超时
                board.releaseMenu(id);
            }
        }
    }

    /** 准星移动时刷新落点高亮。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        updateHover(event.getPlayer());
    }

    /** 同一 tick 内只允许刷新一次高亮（onMove 与周期任务可能重复触发）。 */
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
        List<XiangqiBoard> list = boardsByLevel.get(player.getLevel());
        if (list == null || list.isEmpty()) {
            return;
        }
        if (!claimHover(player)) {
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            XiangqiBoard board = list.get(i);
            if (board.isRunning() && board.inRange(player)) {
                board.updateHover(player);
            }
        }
    }

    /** 延后一 tick 在主线程执行，避免在表单回调中直接弹窗造成客户端窗口冲突。 */
    public void runLater(Runnable action) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, action, 1);
    }

    /** 延迟一 tick 重新弹出指定玩家的棋盘菜单。 */
    public void reopenMenu(Player player, XiangqiBoard board) {
        runLater(() -> XiangqiMenu.reopen(player, board));
    }

    /**
     * 座位变化后刷新所有在座玩家的棋盘菜单：先关掉客户端上的旧表单，再延后一 tick 重弹，
     * 借客户端「新表单顶掉旧表单」完成自动切换。
     */
    public void refreshSeatMenus(XiangqiBoard board) {
        if (board.isRunning()) {
            return;
        }
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p == null) {
                continue;
            }
            XiangqiMenu.closeCurrentForm(p);
            reopenMenu(p, board);
        }
    }

    /** 开局时关闭所有在座玩家的棋盘表单。 */
    public void closeSeatMenus(XiangqiBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                XiangqiMenu.closeCurrentForm(p);
            }
        }
    }

    /**
     * 周期任务：关卡就绪后恢复存档；棋子实体缺失且玩家回到范围时按需重建。
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
        // 区块卸载会关闭非玩家实体，玩家回到范围后按需重建；仅在确实缺失时才扫描本关卡玩家
        for (XiangqiBoard board : boards.values()) {
            if (board.isPiecesMissing() && board.hasPlayerInRange()) {
                board.spawnPieces();
            }
            // 高亮：只处理当前回合方（O(1) 查座位），与 onMove 共用 claimHover 去重
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
            // 兜底：菜单持有者超时或下线时，自动关闭其表单并释放占用
            board.tickMenuHolder();
            // 轮到机器人：停顿片刻后异步计算落点，避免阻塞主线程
            if (board.isAiTurn() && board.beginThinking()) {
                scheduleAiMove(board);
            }
        }
    }

    /** 派发一次机器人行棋：在线程池中异步计算落点，再回到主线程落子（思考时长由 XiangqiAi 控制）。 */
    private void scheduleAiMove(XiangqiBoard board) {
        int[][] snapshot = board.copyGrid();
        int aiColor = board.aiMoveColor();
        int level = board.getAiLevel();
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            int[] move = XiangqiAi.bestMove(snapshot, aiColor, level);
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                board.finishThinking();
                // 兜底：机器人未给出着法或着法被拒时不能再派发，否则本轮会无限重算并把线程池打满
                if (move == null || !board.aiApply(move)) {
                    board.abort("机器人无法行棋，本局结束（可稍后「继承残局」继续）。");
                }
            }, false);
        }, true);
    }

    /**
     * 一条棋盘存档行：{@code X|世界|X|Y|Z|朝向|turn|started|grid}。
     * <ul>
     *     <li>{@code turn} ∈ {0(红),1(黑)}；{@code started} ∈ {0,1}；</li>
     *     <li>{@code grid} 为 90 字符，第 {@code row*9+col} 位：{@code .} = 空，其余为贴图编号的小写十六进制单字符。</li>
     * </ul>
     */
    private static String formatLine(XiangqiBoard board) {
        return "X|" + board.getLevel().getName() + "|" + board.getBaseX() + "|" + board.getBaseY()
                + "|" + board.getBaseZ() + "|" + board.getRotation()
                + "|" + board.getTurn() + "|" + (board.hasStarted() ? 1 : 0) + "|" + board.exportGrid();
    }

    /** 存档文件：每行一个棋盘，格式见 {@link #formatLine(XiangqiBoard)}。 */
    private File storageFile() {
        File folder = plugin.getDataFolder();
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录：" + folder.getPath());
        }
        return new File(folder, "xiangqi_boards.txt");
    }

    /** 把当前所有棋盘写入磁盘：主线程只拼行，实际写盘交给单线程串行执行。 */
    private void save() {
        if (!restored || saveExecutor.isShutdown()) {
            return;
        }
        File file = storageFile();
        List<String> lines = new ArrayList<>(boards.size());
        for (XiangqiBoard board : boards.values()) {
            lines.add(formatLine(board));
        }
        saveExecutor.execute(() -> writeLines(file, lines));
    }

    /** 关服兜底：同步排队写盘并等待队列中已有的写入全部完成。 */
    public void saveNow() {
        if (!restored) {
            return;
        }
        File file = storageFile();
        List<String> lines = new ArrayList<>(boards.size());
        for (XiangqiBoard board : boards.values()) {
            lines.add(formatLine(board));
        }
        if (saveExecutor.isShutdown()) {
            writeLines(file, lines);
            return;
        }
        saveExecutor.execute(() -> writeLines(file, lines));
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("等待象棋棋盘数据写入超时。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLines(File file, List<String> lines) {
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("保存象棋棋盘数据失败：" + e.getMessage());
        }
    }

    /**
     * 从磁盘恢复棋盘：读文件与解析在异步线程，重建方块与棋子回主线程。
     * <p>
     * 加载完成前 {@code restored} 保持 false，写盘会被跳过，避免用空数据覆盖存档。
     */
    public void load() {
        File file = storageFile();
        Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
            List<String[]> saved = parse(file);
            Server.getInstance().getScheduler().scheduleTask(plugin, () -> {
                for (String[] parts : saved) {
                    restoreBoard(parts);
                }
                restored = true;
                if (!saved.isEmpty()) {
                    plugin.getLogger().info("已从磁盘恢复 " + boards.size() + " 个中国象棋棋盘。");
                }
            }, false);
        }, true);
    }

    /** 读取并解析存档为纯数据（不触碰世界，可在异步线程执行）。 */
    private List<String[]> parse(File file) {
        List<String[]> saved = new ArrayList<>();
        if (!file.isFile()) {
            return saved;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("读取象棋棋盘数据失败：" + e.getMessage());
            return saved;
        }
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if ("X".equals(parts[0]) && parts.length >= 6) {
                saved.add(parts);
            }
        }
        return saved;
    }

    private void restoreBoard(String[] parts) {
        Level level = Server.getInstance().getLevelByName(parts[1]);
        if (level == null) {
            plugin.getLogger().warning("象棋棋盘所在世界未加载，跳过：" + parts[1]);
            return;
        }
        int x = parseInt(parts[2], 0);
        int y = parseInt(parts[3], 0);
        int z = parseInt(parts[4], 0);
        int rotation = parseInt(parts[5], 0);
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保棋盘方块仍然有效
        placeBlocks(level, x, y, z, rotation);
        XiangqiBoard board = new XiangqiBoard(this, level, x, y, z, rotation);
        // 兼容 6 段旧行：无局面数据时保持初始布局（构造时已填入），turn=红、started=false
        if (parts.length >= 9) {
            board.importState(parseInt(parts[6], XiangqiBoard.COLOR_RED),
                    parseInt(parts[7], 0) != 0, parts[8]);
        }
        board.spawnPieces();
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
}
