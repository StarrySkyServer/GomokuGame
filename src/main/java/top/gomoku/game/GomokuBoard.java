package top.gomoku.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import top.gomoku.board.GomokuBoardBlock;
import top.gomoku.entity.GomokuStoneEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一个已放置的棋盘实例：负责座位、落子、胜负判定，并驱动棋子实体的渲染属性。
 */
public class GomokuBoard {

    public static final int SIZE = 15;
    public static final int BLACK = 1;
    public static final int WHITE = 2;

    /** 棋盘方块顶面高度（2 像素） */
    public static final double BOARD_TOP = 2.0 / 16.0;
    /** 网格区域距棋盘边缘的留白（方块数） */
    private static final double GRID_MARGIN = 0.125;
    /** 15 条网格线跨越的长度（方块数） */
    private static final double GRID_SPAN = 1.75;
    private static final double LINE_STEP = GRID_SPAN / (SIZE - 1);

    /** 座位检测半径：以棋盘中心为圆心，水平 7 格（14x14 区域） */
    public static final double RANGE = 7.0;

    private static final int[] POW3 = new int[SIZE + 1];

    static {
        POW3[0] = 1;
        for (int i = 1; i <= SIZE; i++) {
            POW3[i] = POW3[i - 1] * 3;
        }
    }

    private final GomokuManager manager;
    private final Level level;
    private final int baseX;
    private final int baseY;
    private final int baseZ;

    private GomokuStoneEntity entity;
    private boolean pickupScheduled;

    /** 座位（按加入先后排序，index 0 为第一位加入者） */
    private final List<UUID> seats = new ArrayList<>(2);
    /** 各座位玩家选择的棋色 */
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    private final int[][] grid = new int[SIZE][SIZE];
    private final Set<UUID> nearby = new HashSet<>();

    private boolean running;
    private int turn = BLACK;
    private int lastIndex;
    private int hoverIndex;
    private UUID winner;

    public GomokuBoard(GomokuManager manager, Level level, int baseX, int baseY, int baseZ) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    public Level getLevel() {
        return level;
    }

    public int getBaseX() {
        return baseX;
    }

    public int getBaseY() {
        return baseY;
    }

    public int getBaseZ() {
        return baseZ;
    }

    public String getKey() {
        return level.getName() + ":" + baseX + ":" + baseY + ":" + baseZ;
    }

    public Vector3 getCenter() {
        return new Vector3(baseX + 1, baseY, baseZ + 1);
    }

    public boolean isRunning() {
        return running;
    }

    public List<UUID> getSeats() {
        return seats;
    }

    public boolean isSeated(UUID uuid) {
        return seats.contains(uuid);
    }

    public int seatCount() {
        return seats.size();
    }

    /** 玩家所选棋色：BLACK / WHITE，未入座为 0。 */
    public int seatColor(UUID uuid) {
        return seatColors.getOrDefault(uuid, 0);
    }

    /** 某棋色是否已被占用。 */
    public boolean isColorTaken(int color) {
        return seatColors.containsValue(color);
    }

    /** 是否为第一位加入者（用于决定开局先后手）。 */
    public boolean isFirstJoiner(UUID uuid) {
        return !seats.isEmpty() && seats.get(0).equals(uuid);
    }

    public static String colorName(int color) {
        return color == BLACK ? "黑棋" : "白棋";
    }

    public UUID getWinner() {
        return winner;
    }

    public int getTurn() {
        return turn;
    }

    /**
     * 加入座位并选择棋色。
     */
    public boolean join(Player player, int color) {
        UUID id = player.getUniqueId();
        if (seats.contains(id)) {
            player.sendMessage("§e你已在座位上。");
            return false;
        }
        if (seats.size() >= 2) {
            player.sendMessage("§c座位已满（最多 2 人）。");
            return false;
        }
        if (color != BLACK && color != WHITE) {
            return false;
        }
        if (seatColors.containsValue(color)) {
            player.sendMessage("§c该棋色已被选择。");
            return false;
        }
        seats.add(id);
        seatColors.put(id, color);
        manager.markDirty();
        player.sendMessage("§a你已加入对局（" + colorName(color) + "）。");
        return true;
    }

    public boolean leave(UUID uuid) {
        int i = seats.indexOf(uuid);
        if (i < 0) {
            return false;
        }
        seats.remove(i);
        seatColors.remove(uuid);
        manager.markDirty();
        if (running) {
            abort("有玩家退出，本局结束");
        }
        return true;
    }

    /**
     * 开始对局。
     *
     * @param firstColor 先手棋色（BLACK / WHITE）
     */
    public void start(int firstColor) {
        if (running) {
            return;
        }
        if (seats.size() < 2) {
            return;
        }
        clearGrid();
        running = true;
        turn = firstColor == WHITE ? WHITE : BLACK;
        winner = null;
        setLast(0);
        setHover(0);
        manager.markDirty();
        broadcast("§e五子棋对局开始！" + colorName(turn) + "先手。");
    }

    /**
     * 继承棋盘上已有的棋子继续对局：不清空棋盘，先手由棋子数决定。
     * <p>
     * 黑棋先行，故黑白棋子数相等时轮到黑棋，黑棋多一子时轮到白棋。
     */
    public void resume() {
        if (running || seats.size() < 2) {
            return;
        }
        running = true;
        winner = null;
        turn = stoneCount(BLACK) > stoneCount(WHITE) ? WHITE : BLACK;
        setHover(0);
        manager.markDirty();
        broadcast("§e已继承棋局，" + colorName(turn) + "继续落子。");
    }

    private int stoneCount(int color) {
        int count = 0;
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (grid[r][c] == color) {
                    count++;
                }
            }
        }
        return count;
    }

    private void abort(String reason) {
        running = false;
        broadcast("§c" + reason);
    }

    private void clearGrid() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                grid[r][c] = 0;
            }
            pushRow(r);
        }
    }

    /**
     * 计算玩家准星命中的网格交点（1 基索引，0 表示未命中）。
     */
    public int aimCell(Player player) {
        if (player.getLevel() != this.level) {
            return 0;
        }
        Vector3 dir = player.getDirectionVector();
        if (Math.abs(dir.y) < 1e-6) {
            return 0;
        }
        Position pos = player.getPosition();
        double eyeX = pos.x;
        double eyeY = pos.y + player.getEyeHeight();
        double eyeZ = pos.z;

        double planeY = baseY + BOARD_TOP;
        double t = (planeY - eyeY) / dir.y;
        if (t <= 0 || t > 12) {
            return 0;
        }
        double hitX = eyeX + dir.x * t;
        double hitZ = eyeZ + dir.z * t;

        double minX = baseX + GRID_MARGIN;
        double minZ = baseZ + GRID_MARGIN;
        int col = (int) Math.round((hitX - minX) / LINE_STEP);
        // 实体模型 Z 轴与世界 Z 轴方向相反，需反转行索引才能与准星指向一致
        int row = SIZE - 1 - (int) Math.round((hitZ - minZ) / LINE_STEP);
        if (col < 0 || col >= SIZE || row < 0 || row >= SIZE) {
            return 0;
        }
        return row * SIZE + col + 1;
    }

    public void updateHover(Player player) {
        if (!running) {
            return;
        }
        int color = seatColor(player.getUniqueId());
        if (color == 0 || color != turn) {
            return;
        }
        setHover(aimCell(player));
    }

    /**
     * 尝试在准星命中的交点落子。
     *
     * @return 落子后本局是否刚刚结束（胜/负/和）
     */
    public boolean place(Player player, int index) {
        if (!running) {
            return false;
        }
        int color = seatColor(player.getUniqueId());
        if (color == 0) {
            player.sendMessage("§c你不在座位上，无法落子。");
            return false;
        }
        if (color != turn) {
            return false;
        }
        if (index <= 0) {
            player.sendMessage("§c请将准星对准棋盘上的网格交点。");
            return false;
        }
        int row = (index - 1) / SIZE;
        int col = (index - 1) % SIZE;
        if (grid[row][col] != 0) {
            player.sendMessage("§c该位置已有棋子。");
            return false;
        }

        grid[row][col] = color;
        pushRow(row);
        setLast(index);
        manager.markDirty();

        if (checkWin(row, col, color)) {
            running = false;
            winner = player.getUniqueId();
            setHover(0);
            Server.getInstance().broadcastMessage("§6" + player.getName() + " 在与 " + opponentName(player.getUniqueId())
                    + " 的五子棋对战中获胜！");
            return true;
        }
        if (isFull()) {
            running = false;
            winner = null;
            setHover(0);
            broadcast("§e棋盘已满，本局平局。");
            return true;
        }

        turn = color == BLACK ? WHITE : BLACK;
        setHover(0);
        return false;
    }

    private boolean isFull() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                if (grid[r][c] == 0) {
                    return false;
                }
            }
        }
        return true;
    }

    private void pushRow(int row) {
        if (entity == null || entity.isClosed()) {
            return;
        }
        int value = 0;
        for (int c = 0; c < SIZE; c++) {
            value += grid[row][c] * POW3[c];
        }
        entity.setRow(row, value);
    }

    private void setHover(int index) {
        if (hoverIndex == index) {
            return;
        }
        hoverIndex = index;
        if (entity != null && !entity.isClosed()) {
            entity.setHover(index);
        }
    }

    private void setLast(int index) {
        lastIndex = index;
        if (entity != null && !entity.isClosed()) {
            entity.setLast(index);
        }
    }

    private boolean checkWin(int row, int col, int color) {
        int[][] dirs = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        for (int[] d : dirs) {
            int count = 1;
            count += countDir(row, col, d[0], d[1], color);
            count += countDir(row, col, -d[0], -d[1], color);
            if (count >= 5) {
                return true;
            }
        }
        return false;
    }

    private int countDir(int row, int col, int dr, int dc, int color) {
        int count = 0;
        int r = row + dr;
        int c = col + dc;
        while (r >= 0 && r < SIZE && c >= 0 && c < SIZE && grid[r][c] == color) {
            count++;
            r += dr;
            c += dc;
        }
        return count;
    }

    public void spawnEntity() {
        if (entity != null && !entity.isClosed()) {
            return;
        }
        Entity created = Entity.createEntity(GomokuStoneEntity.IDENTIFIER,
                Position.fromObject(getCenter(), level));
        if (created instanceof GomokuStoneEntity stone) {
            this.entity = stone;
            stone.setBoard(this);
            stone.spawnToAll();
            for (int r = 0; r < SIZE; r++) {
                pushRow(r);
            }
            setLast(0);
            setHover(0);
        }
    }

    public void remove() {
        if (entity != null && !entity.isClosed()) {
            entity.close();
        }
        entity = null;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                Vector3 p = new Vector3(baseX + dx, baseY, baseZ + dz);
                if (level.getBlock(p) instanceof GomokuBoardBlock) {
                    level.setBlock(p, cn.nukkit.block.Block.get(cn.nukkit.block.Block.AIR), true);
                }
            }
        }
    }

    /**
     * 导出每行的三进制编码（与实体属性同构），用于持久化落子状态。
     */
    public int[] exportRows() {
        int[] rows = new int[SIZE];
        for (int r = 0; r < SIZE; r++) {
            int value = 0;
            for (int c = 0; c < SIZE; c++) {
                value += grid[r][c] * POW3[c];
            }
            rows[r] = value;
        }
        return rows;
    }

    /**
     * 从磁盘数据恢复棋盘状态：座位、棋色、落子与对局进度。
     */
    public void restore(List<UUID> savedSeats, Map<UUID, Integer> savedColors,
                        int[] rows, boolean savedRunning, int savedTurn, UUID savedWinner) {
        seats.clear();
        seats.addAll(savedSeats);
        seatColors.clear();
        seatColors.putAll(savedColors);
        for (int r = 0; r < SIZE; r++) {
            int value = r < rows.length ? rows[r] : 0;
            for (int c = 0; c < SIZE; c++) {
                grid[r][c] = value % 3;
                value /= 3;
            }
        }
        running = savedRunning;
        turn = savedTurn == WHITE ? WHITE : BLACK;
        winner = savedWinner;
    }

    /**
     * 玩家点击棋盘实体（左键）时请求收起棋盘。交由管理器延迟一 tick 处理，
     * 避免在实体自身的攻击回调里直接关闭该实体。
     */
    public void requestPickup(Player player) {
        if (pickupScheduled) {
            return;
        }
        pickupScheduled = true;
        manager.requestPickup(this, player);
    }

    /**
     * 判断给定方块坐标是否属于本棋盘。
     */
    public boolean contains(int x, int y, int z) {
        return y == baseY
                && x >= baseX && x <= baseX + 1
                && z >= baseZ && z <= baseZ + 1;
    }

    /**
     * 座位检测：以棋盘中心为圆心，水平方向 7 格内视为在座。
     */
    public boolean inRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        Vector3 c = getCenter();
        return Math.abs(player.x - c.x) <= RANGE
                && Math.abs(player.z - c.z) <= RANGE
                && Math.abs(player.y - c.y) <= 12.0;
    }

    /**
     * 刷新附近玩家集合，返回刚离开范围的玩家。
     */
    public Set<UUID> refreshNearby() {
        Set<UUID> current = new HashSet<>();
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                current.add(p.getUniqueId());
            }
        }
        Set<UUID> left = new HashSet<>(nearby);
        left.removeAll(current);
        nearby.clear();
        nearby.addAll(current);
        return left;
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7座位：§f");
        if (seats.isEmpty()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                String name = p != null ? p.getName() : id.toString().substring(0, 8);
                sb.append(seatColor(id) == BLACK ? "§0[黑] " : "§f[白] ").append("§7").append(name).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(turn == BLACK ? "§0黑棋" : "§f白棋").append("§7。");
        } else if (winner != null) {
            Player p = Server.getInstance().getPlayer(winner).orElse(null);
            sb.append("已结束，").append(p != null ? p.getName() : "?").append(" 获胜。");
        } else {
            sb.append("未开始。");
        }
        sb.append("\n\n§7提示：加入座位后需满 2 人方可开始。");
        return sb.toString();
    }

    /** 对局过程中的提示只发给执棋的两位玩家。 */
    private void broadcast(String msg) {
        for (UUID id : seats) {
            Player p = Server.getInstance().getPlayer(id).orElse(null);
            if (p != null) {
                p.sendMessage(msg);
            }
        }
    }

    private String opponentName(UUID self) {
        for (UUID id : seats) {
            if (!id.equals(self)) {
                Player p = Server.getInstance().getPlayer(id).orElse(null);
                if (p != null) {
                    return p.getName();
                }
            }
        }
        return "对手";
    }
}