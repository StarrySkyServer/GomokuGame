package top.gomoku.game;

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
import cn.nukkit.math.BlockFace;
import cn.nukkit.math.Vector3;
import cn.nukkit.plugin.PluginBase;
import top.gomoku.board.GomokuBoardBlock;
import top.gomoku.entity.GomokuStoneEntity;
import top.gomoku.item.GomokuBoardItem;
import top.gomoku.ui.GomokuMenu;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 管理所有已放置的棋盘，处理放置、交互、范围检测与清理。
 */
public class GomokuManager implements Listener {

    /** 放置棋盘的冷却时间（毫秒）：一次点击可能触发多个交互事件，需去重 */
    private static final long PLACE_COOLDOWN_MS = 500L;

    private final PluginBase plugin;
    private final Map<String, GomokuBoard> boards = new HashMap<>();
    private final Map<UUID, Long> placeCooldown = new HashMap<>();

    /** 棋盘状态发生变更后置位，下一次 tick 落盘，避免频繁写文件。 */
    private boolean dirty;

    /** 插件以 STARTUP 阶段启用，此时关卡尚未加载，需等关卡就绪后再恢复棋盘。 */
    private boolean loadPending;

    /** 首次恢复是否已完成。未完成前禁止写盘，避免用空数据覆盖存档。 */
    private boolean restored;

    public GomokuManager(PluginBase plugin) {
        this.plugin = plugin;
    }

    /** 标记棋盘状态已变更，等待下一次 tick 统一保存。 */
    public void markDirty() {
        this.dirty = true;
    }

    /** 请求在关卡加载完成后恢复磁盘上的棋盘。 */
    public void requestLoad() {
        this.loadPending = true;
    }

    public GomokuBoard findBoard(Level level, int x, int y, int z) {
        for (GomokuBoard board : boards.values()) {
            if (board.getLevel() == level && board.contains(x, y, z)) {
                return board;
            }
        }
        return null;
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
            if (item instanceof GomokuBoardItem) {
                // 放置棋盘会真正改动世界，仍遵守服务端取消（如出生点保护）
                if (event.isCancelled() || !canPlace(player)) {
                    event.setCancelled(true);
                    return;
                }
                tryPlace(player, event, clicked);
                return;
            }
            if (clicked instanceof GomokuBoardBlock) {
                useBoardAt(player, event, clicked);
            }
            return;
        }

        // 左键不触发棋盘菜单：仅右键（PC 右键 / 触屏“使用”交互）打开，避免与挖掘及出生点保护冲突。
    }

    /** 命中棋盘方块时打开菜单或落子。 */
    private void useBoardAt(Player player, PlayerInteractEvent event, Block clicked) {
        GomokuBoard board = findBoard(player.getLevel(),
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
        if (!(event.getEntity() instanceof GomokuStoneEntity stone)) {
            return;
        }
        GomokuBoard board = stone.getBoard();
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

    private void tryPlace(Player player, PlayerInteractEvent event, Block clicked) {
        BlockFace face = event.getFace();
        if (face == null) {
            return;
        }
        Vector3 target = clicked.getSide(face);
        int bx = target.getFloorX();
        int by = target.getFloorY();
        int bz = target.getFloorZ();
        Level level = player.getLevel();

        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                Block b = level.getBlock(bx + dx, by, bz + dz);
                if (!b.isAir() && !b.canBeReplaced()) {
                    player.sendMessage("§c空间不足，放置棋盘需要 2x2 空地。");
                    return;
                }
            }
        }
        for (GomokuBoard board : boards.values()) {
            if (board.getLevel() == level && board.getBaseY() == by
                    && Math.abs(board.getBaseX() - bx) < 2
                    && Math.abs(board.getBaseZ() - bz) < 2) {
                player.sendMessage("§c这里已经有棋盘了。");
                return;
            }
        }

        event.setCancelled(true);
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                GomokuBoardBlock block = new GomokuBoardBlock();
                block.setPosition(dx + dz * 2);
                level.setBlock(new Vector3(bx + dx, by, bz + dz), block, true);
            }
        }

        GomokuBoard board = new GomokuBoard(this, level, bx, by, bz);
        board.spawnEntity();
        boards.put(board.getKey(), board);
        markDirty();

        // 无论游戏模式，放置后都消耗手中的棋盘物品
        Item hand = player.getInventory().getItemInHand();
        if (hand.getCount() <= 1) {
            player.getInventory().setItemInHand(Item.get(Item.AIR));
        } else {
            hand.setCount(hand.getCount() - 1);
            player.getInventory().setItemInHand(hand);
        }
        player.sendMessage("§a已放置五子棋棋盘。右键棋盘可打开菜单。");
    }

    private void useBoard(Player player, GomokuBoard board) {
        if (board.isRunning()) {
            boolean ended = board.place(player, board.aimCell(player));
            if (ended) {
                reopenForSeats(board);
            }
        } else {
            GomokuMenu.open(player, board);
        }
    }

    private void reopenForSeats(GomokuBoard board) {
        for (UUID id : new ArrayList<>(board.getSeats())) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                Server.getInstance().getScheduler().scheduleDelayedTask(plugin,
                        () -> GomokuMenu.open(p, board), 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock() instanceof GomokuBoardBlock) {
            event.setCancelled(true);
        }
    }

    /** 延迟一 tick 再收起棋盘，避免在事件回调中直接移除方块与实体。 */
    public void requestPickup(GomokuBoard board, Player player) {
        Server.getInstance().getScheduler().scheduleDelayedTask(plugin, () -> pickup(board, player), 1);
    }

    /** 收起棋盘：移除方块与棋子实体，并把棋盘物品返还给玩家。 */
    private void pickup(GomokuBoard board, Player player) {
        if (boards.remove(board.getKey()) == null) {
            return;
        }
        board.remove();
        markDirty();
        player.getInventory().addItem(new GomokuBoardItem());
        player.sendMessage("§e已收起五子棋棋盘。");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        placeCooldown.remove(id);
        for (GomokuBoard board : boards.values()) {
            if (board.isSeated(id)) {
                board.leave(id);
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

    private void updateHover(Player player) {
        for (GomokuBoard board : boards.values()) {
            if (board.isRunning() && board.getLevel() == player.getLevel() && board.inRange(player)) {
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
        for (GomokuBoard board : new ArrayList<>(boards.values())) {
            for (UUID id : board.refreshNearby()) {
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null && board.isSeated(id)) {
                    board.leave(id);
                    p.sendMessage("§e你已离开棋盘范围，自动退出棋局。");
                }
            }
            if (board.isRunning()) {
                for (Player p : board.getLevel().getPlayers().values()) {
                    if (board.inRange(p)) {
                        board.updateHover(p);
                    }
                }
            }
        }
    }

    /**
     * 持久化文件：每行一条记录，用 {@code |} 分隔（世界名不会含该字符）。
     * <ul>
     *     <li>{@code B|世界|X|Y|Z|是否进行中|当前回合|胜者UUID}：一个棋盘的头部</li>
     *     <li>{@code S|玩家UUID|棋色}：一条座位记录</li>
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

    /** 把当前所有棋盘写入磁盘。 */
    public void save() {
        if (!restored) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (GomokuBoard board : boards.values()) {
            UUID winner = board.getWinner();
            lines.add("B|" + board.getLevel().getName() + "|" + board.getBaseX() + "|" + board.getBaseY()
                    + "|" + board.getBaseZ() + "|" + board.isRunning() + "|" + board.getTurn()
                    + "|" + (winner == null ? "" : winner));
            for (UUID id : board.getSeats()) {
                lines.add("S|" + id + "|" + board.seatColor(id));
            }
            StringBuilder row = new StringBuilder("R");
            for (int value : board.exportRows()) {
                row.append('|').append(value);
            }
            lines.add(row.toString());
        }
        try {
            Files.write(storageFile().toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("保存棋盘数据失败：" + e.getMessage());
        }
    }

    /**
     * 启动时从磁盘恢复棋盘：重建棋盘对象、按坐标重新放置方块并生成棋子实体。
     */
    public void load() {
        File file = storageFile();
        if (!file.isFile()) {
            restored = true;
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().warning("读取棋盘数据失败：" + e.getMessage());
            return;
        }
        List<SavedBoard> saved = new ArrayList<>();
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
                    current.colors.put(id, parseInt(parts[2], GomokuBoard.BLACK));
                }
            } else if ("R".equals(parts[0]) && current != null) {
                current.rows = new int[GomokuBoard.SIZE];
                for (int i = 0; i < current.rows.length && i + 1 < parts.length; i++) {
                    current.rows[i] = parseInt(parts[i + 1], 0);
                }
            }
        }
        for (SavedBoard data : saved) {
            restoreBoard(data);
        }
        restored = true;
        plugin.getLogger().info("已从磁盘恢复 " + boards.size() + " 个五子棋棋盘。");
    }

    private void restoreBoard(SavedBoard data) {
        Level level = Server.getInstance().getLevelByName(data.level);
        if (level == null) {
            plugin.getLogger().warning("棋盘所在世界未加载，跳过：" + data.level);
            return;
        }
        GomokuBoard board = new GomokuBoard(this, level, data.x, data.y, data.z);
        board.restore(data.seats, data.colors,
                data.rows != null ? data.rows : new int[GomokuBoard.SIZE],
                data.running, data.turn, data.winner);
        // 自定义方块 id 重启后可能重新分配，这里按坐标重放一遍，确保棋盘方块仍然有效
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                GomokuBoardBlock block = new GomokuBoardBlock();
                block.setPosition(dx + dz * 2);
                level.setBlock(new Vector3(data.x + dx, data.y, data.z + dz), block, true);
            }
        }
        board.spawnEntity();
        boards.put(board.getKey(), board);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
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
        final List<UUID> seats = new ArrayList<>();
        final Map<UUID, Integer> colors = new HashMap<>();
        int[] rows;

        SavedBoard(String[] parts) {
            this.level = parts.length > 1 ? parts[1] : "";
            this.x = parseInt(parts.length > 2 ? parts[2] : "0", 0);
            this.y = parseInt(parts.length > 3 ? parts[3] : "0", 0);
            this.z = parseInt(parts.length > 4 ? parts[4] : "0", 0);
            this.running = parts.length > 5 && Boolean.parseBoolean(parts[5]);
            this.turn = parseInt(parts.length > 6 ? parts[6] : "1", GomokuBoard.BLACK);
            this.winner = parts.length > 7 ? parseUuid(parts[7]) : null;
        }
    }
}