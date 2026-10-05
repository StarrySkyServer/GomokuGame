package top.gomoku.game;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.math.Vector3;
import top.gomoku.board.BaseGomokuBoardBlock;
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

    /** 机器人座位的哨兵 UUID（全 0），不对应任何真实玩家。 */
    public static final UUID AI_ID = new UUID(0L, 0L);

    /** 棋盘方块顶面高度（2 像素） */
    public static final double BOARD_TOP = 2.0 / 16.0;

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
    /** 棋盘边长（方块数）：2 = 标准 2x2 棋盘，4 = 大号 4x4 棋盘。网格始终为 15x15。 */
    private final int boardSize;

    /** 棋盘中心（构造时算好并复用）。只读，调用方不得修改。 */
    private final Vector3 center;

    private GomokuStoneEntity entity;
    private boolean pickupScheduled;

    /** 座位（按加入先后排序，index 0 为第一位加入者） */
    private final List<UUID> seats = new ArrayList<>(2);
    /** 各座位玩家选择的棋色 */
    private final Map<UUID, Integer> seatColors = new HashMap<>(2);
    private final int[][] grid = new int[SIZE][SIZE];
    private final Set<UUID> nearby = new HashSet<>();

    /** 黑白已落子数：用于 O(1) 判满与判断先手，避免整盘扫描。 */
    private int blackStones;
    private int whiteStones;

    private boolean running;
    private int turn = BLACK;
    private int lastIndex;
    private int hoverIndex;
    private UUID winner;

    /** 机器人棋色（0 表示本局没有机器人）。 */
    private int aiColor;
    /** 机器人难度：1 入门 / 2 进阶 / 3 大师。 */
    private int aiLevel;
    /**
     * 玩家已选择「单人对战」但尚未选色时暂存的机器人等级（0 表示无）。
     * 该状态保存在棋盘上，中途关闭菜单再打开也不会丢失。
     */
    private int pendingAiLevel;
    /** 机器人是否正在思考，避免重复派发计算任务。 */
    private boolean aiThinking;

    public GomokuBoard(GomokuManager manager, Level level, int baseX, int baseY, int baseZ) {
        this(manager, level, baseX, baseY, baseZ, 2);
    }

    public GomokuBoard(GomokuManager manager, Level level, int baseX, int baseY, int baseZ, int boardSize) {
        this.manager = manager;
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.boardSize = boardSize >= 4 ? 4 : 2;
        int half = this.boardSize / 2;
        this.center = new Vector3(baseX + half, baseY, baseZ + half);
    }

    /** 棋盘边长（方块数）：2 或 4。 */
    public int getBoardSize() {
        return boardSize;
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

    /** 棋盘中心。返回共享实例，调用方不得修改。 */
    public Vector3 getCenter() {
        return center;
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

    /** 该 UUID 是否为机器人座位。 */
    public static boolean isAiSeat(UUID id) {
        return AI_ID.equals(id);
    }

    /** 本局是否为人机对战。 */
    public boolean isAiGame() {
        return aiColor == BLACK || aiColor == WHITE;
    }

    public int getAiColor() {
        return aiColor;
    }

    public int getAiLevel() {
        return aiLevel;
    }

    /** 当前是否轮到机器人落子。 */
    public boolean isAiTurn() {
        return running && isAiGame() && turn == aiColor;
    }

    public static String aiLevelName(int level) {
        switch (level) {
            case 1:
                return "入门";
            case 2:
                return "普通";
            case 3:
                return "大师";
            default:
                return "";
        }
    }

    public String aiName() {
        return aiLevelName(aiLevel) + "机器人";
    }

    /** 机器人是否正在思考。 */
    public boolean isAiThinking() {
        return aiThinking;
    }

    /** 申请一次机器人思考（已有任务在跑时返回 false）。 */
    public boolean beginThinking() {
        if (aiThinking) {
            return false;
        }
        aiThinking = true;
        return true;
    }

    public void finishThinking() {
        aiThinking = false;
    }

    /** 导出棋盘副本，供机器人异步计算，避免跨线程读取内部数组。 */
    public int[][] copyGrid() {
        int[][] copy = new int[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            System.arraycopy(grid[r], 0, copy[r], 0, SIZE);
        }
        return copy;
    }

    /** 玩家是否已选择「单人对战」但尚未选色。 */
    public boolean hasPendingAi() {
        return pendingAiLevel > 0;
    }

    /**
     * 选择单人对战。
     * <p>
     * 玩家已选棋色时机器人立刻坐到对面；尚未选色时只记下等级，等玩家选色后再入座。
     * 状态保存在棋盘上，因此中途关闭菜单再打开仍停留在下一步。
     *
     * @return 棋盘状态是否发生变化（用于决定是否重新弹出菜单）
     */
    public boolean chooseAi(Player player, int level) {
        if (running || level < 1 || level > 3) {
            return false;
        }
        UUID id = player.getUniqueId();
        int myColor = seatColor(id);
        if (myColor == 0) {
            pendingAiLevel = level;
            player.sendMessage("§a已选择" + aiLevelName(level) + "机器人，请选择你的棋色。");
            return true;
        }
        if (seats.size() >= 2) {
            player.sendMessage("§c座位已满。");
            return false;
        }
        seatAi(myColor == BLACK ? WHITE : BLACK, level);
        player.sendMessage("§a对手已就位：" + aiName() + "（你执" + colorName(myColor) + "）。");
        return true;
    }

    /** 放弃单人对战选择，恢复为普通菜单。 */
    public void cancelPendingAi(Player player) {
        if (pendingAiLevel == 0) {
            return;
        }
        pendingAiLevel = 0;
        player.sendMessage("§e已取消单人对战选择。");
    }

    /** 让机器人坐到指定棋色。 */
    private void seatAi(int color, int level) {
        seats.remove(AI_ID);
        seatColors.remove(AI_ID);
        seats.add(AI_ID);
        seatColors.put(AI_ID, color);
        aiColor = color;
        aiLevel = level;
        pendingAiLevel = 0;
        manager.markDirty();
    }

    /** 请求重新弹出本棋盘的菜单，供人机对战的分步表单使用。 */
    public void reopenMenu(Player player) {
        manager.reopenMenu(player, this);
    }

    /**
     * 应用机器人算出的落点。
     *
     * @return 落子后本局是否刚刚结束
     */
    public boolean aiPlace(int row, int col) {
        if (!running || !isAiGame() || turn != aiColor) {
            return false;
        }
        if (row < 0 || row >= SIZE || col < 0 || col >= SIZE || grid[row][col] != 0) {
            return false;
        }
        return applyMove(row, col, aiColor, aiName());
    }

    /** 从磁盘恢复机器人设置。 */
    public void restoreAi(int level, int color) {
        if (color == BLACK || color == WHITE) {
            aiColor = color;
            aiLevel = level >= 1 && level <= 3 ? level : 1;
        }
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
        // 若此前选择了单人对战，机器人随即坐到对面
        if (pendingAiLevel > 0) {
            if (seats.size() < 2) {
                seatAi(color == BLACK ? WHITE : BLACK, pendingAiLevel);
            } else {
                pendingAiLevel = 0;
            }
        }
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
        // 人机对战中玩家退出，机器人一并撤离，避免留下无主的机器人座位
        if (isAiGame()) {
            seats.remove(AI_ID);
            seatColors.remove(AI_ID);
            aiColor = 0;
            aiLevel = 0;
            aiThinking = false;
        }
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
        aiThinking = false;
        pendingAiLevel = 0;
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
        turn = blackStones > whiteStones ? WHITE : BLACK;
        setHover(0);
        manager.markDirty();
        broadcast("§e已继承棋局，" + colorName(turn) + "继续落子。");
    }

    private void abort(String reason) {
        running = false;
        aiThinking = false;
        broadcast("§c" + reason);
    }

    private void clearGrid() {
        for (int r = 0; r < SIZE; r++) {
            for (int c = 0; c < SIZE; c++) {
                grid[r][c] = 0;
            }
        }
        blackStones = 0;
        whiteStones = 0;
        if (entity == null || entity.isClosed()) {
            return;
        }
        // 15 行属性合并成一次同步，避免清盘连发 15 个元数据包
        entity.beginBatch();
        for (int r = 0; r < SIZE; r++) {
            pushRow(r);
        }
        entity.endBatch();
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

        double step = lineStep();
        double minX = baseX + gridMargin();
        double minZ = baseZ + gridMargin();
        int col = (int) Math.round((hitX - minX) / step);
        // 实体模型 Z 轴与世界 Z 轴方向相反，需反转行索引才能与准星指向一致
        int row = SIZE - 1 - (int) Math.round((hitZ - minZ) / step);
        if (col < 0 || col >= SIZE || row < 0 || row >= SIZE) {
            return 0;
        }
        return row * SIZE + col + 1;
    }

    /** 网格区域距棋盘边缘的留白（方块数）：标准棋盘 0.125，大棋盘 0.25。 */
    private double gridMargin() {
        return boardSize * 0.0625;
    }

    /** 15 条网格线跨越的长度（方块数）：标准棋盘 1.75，大棋盘 3.5。 */
    private double gridSpan() {
        return boardSize * 0.875;
    }

    private double lineStep() {
        return gridSpan() / (SIZE - 1);
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

        return applyMove(row, col, color, player.getName());
    }

    /**
     * 落子核心：写入棋盘、刷新实体、判定胜负并换手。玩家与机器人共用。
     *
     * @param actorName 落子者名称，用于播报
     * @return 本局是否结束
     */
    private boolean applyMove(int row, int col, int color, String actorName) {
        grid[row][col] = color;
        if (color == BLACK) {
            blackStones++;
        } else {
            whiteStones++;
        }
        manager.markDirty();

        boolean win = checkWin(row, col, color);
        boolean full = !win && isFull();

        // 本步的属性变更（行、最后一手、清除高亮）合并成一次同步，整步只发一个元数据包
        boolean hasEntity = entity != null && !entity.isClosed();
        if (hasEntity) {
            entity.beginBatch();
        }
        pushRow(row);
        setLast(row * SIZE + col + 1);
        setHover(0);
        if (hasEntity) {
            entity.endBatch();
        }

        if (win) {
            running = false;
            winner = seatOfColor(color);
            announceWin(color, actorName);
            return true;
        }
        if (full) {
            running = false;
            winner = null;
            broadcast("§e棋盘已满，本局平局。");
            return true;
        }

        turn = color == BLACK ? WHITE : BLACK;
        return false;
    }

    /**
     * 胜负播报与奖励结算。
     * <p>
     * 玩家对局与「战胜大师级机器人」全服广播；战胜入门/普通机器人、或机器人获胜，只发给当事玩家。
     * 玩家战胜机器人时静默发放对应等级的奖励，不额外播报。
     */
    private void announceWin(int color, String actorName) {
        String msg = "§6" + actorName + " 在与 " + opponentNameOf(color) + " 的五子棋对战中获胜！";
        if (!isAiGame()) {
            Server.getInstance().broadcastMessage(msg);
            return;
        }
        boolean playerWon = color != aiColor;
        if (playerWon && winner != null) {
            Player player = Server.getInstance().getPlayer(winner).orElse(null);
            if (player != null) {
                manager.giveAiReward(player, aiLevel);
            }
        }
        if (playerWon && aiLevel == 3) {
            Server.getInstance().broadcastMessage(msg);
            return;
        }
        // 其余结果（战胜入门/普通机器人，或机器人获胜）：只让本人看到
        UUID target = isAiSeat(winner) ? otherSeat(winner) : winner;
        Player human = target == null ? null : Server.getInstance().getPlayer(target).orElse(null);
        if (human != null) {
            human.sendMessage(msg);
        }
    }

    private boolean isFull() {
        return blackStones + whiteStones >= SIZE * SIZE;
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
            // 边长先设好，让生成包就带正确尺寸；此时还没有观察者，不会额外发包
            stone.setBoardSize(boardSize);
            stone.spawnToAll();
            // 15 行属性合并成一次同步，开局只多发一个包
            stone.beginBatch();
            for (int r = 0; r < SIZE; r++) {
                pushRow(r);
            }
            setLast(0);
            setHover(0);
            stone.endBatch();
        }
    }

    public void remove() {
        running = false;
        aiThinking = false;
        pendingAiLevel = 0;
        if (entity != null && !entity.isClosed()) {
            entity.close();
        }
        entity = null;
        for (int dx = 0; dx < boardSize; dx++) {
            for (int dz = 0; dz < boardSize; dz++) {
                Vector3 p = new Vector3(baseX + dx, baseY, baseZ + dz);
                if (level.getBlock(p) instanceof BaseGomokuBoardBlock) {
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
        blackStones = 0;
        whiteStones = 0;
        for (int r = 0; r < SIZE; r++) {
            int value = r < rows.length ? rows[r] : 0;
            for (int c = 0; c < SIZE; c++) {
                int cell = value % 3;
                grid[r][c] = cell;
                if (cell == BLACK) {
                    blackStones++;
                } else if (cell == WHITE) {
                    whiteStones++;
                }
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
                && x >= baseX && x < baseX + boardSize
                && z >= baseZ && z < baseZ + boardSize;
    }

    /**
     * 座位检测：以棋盘中心为圆心，水平方向 {@code playerRange} 格（默认 7）内视为在座。
     */
    public boolean inRange(Player player) {
        if (player.getLevel() != this.level) {
            return false;
        }
        double range = manager.getConfig().getPlayerRange();
        Vector3 c = center;
        return Math.abs(player.x - c.x) <= range
                && Math.abs(player.z - c.z) <= range
                && Math.abs(player.y - c.y) <= 12.0;
    }

    /**
     * 刷新附近玩家集合，返回刚离开范围的玩家。
     */
    public Set<UUID> refreshNearby() {
        // 复用 nearby 集合，只额外分配一个返回值集合，避免每 tick 产生两个 HashSet
        Set<UUID> left = new HashSet<>(nearby);
        nearby.clear();
        for (Player p : level.getPlayers().values()) {
            if (inRange(p)) {
                UUID id = p.getUniqueId();
                nearby.add(id);
                left.remove(id);
            }
        }
        return left;
    }

    public String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("§7座位：§f");
        if (seats.isEmpty()) {
            sb.append("空");
        } else {
            for (UUID id : seats) {
                sb.append(seatColor(id) == BLACK ? "§0[黑] " : "§f[白] ")
                        .append("§7").append(nameOf(id)).append("  ");
            }
        }
        sb.append("\n§7状态：§f");
        if (running) {
            sb.append("对局中，轮到").append(turn == BLACK ? "§0黑棋" : "§f白棋").append("§7。");
        } else if (winner != null) {
            sb.append("已结束，").append(nameOf(winner)).append(" 获胜。");
        } else {
            sb.append("未开始。");
        }
        sb.append("\n\n§7提示：可加入他人对局，或直接选择「单人对战」挑战机器人。");
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

    /** 座位显示名：机器人返回其等级名，离线玩家返回 UUID 前缀。 */
    private String nameOf(UUID id) {
        if (isAiSeat(id)) {
            return aiName();
        }
        Player p = Server.getInstance().getPlayer(id).orElse(null);
        return p != null ? p.getName() : id.toString().substring(0, 8);
    }

    /** 指定棋色对应的座位 UUID。 */
    private UUID seatOfColor(int color) {
        for (Map.Entry<UUID, Integer> entry : seatColors.entrySet()) {
            if (entry.getValue() == color) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 除给定座位外的另一个座位（用于人机对战中定位玩家）。 */
    private UUID otherSeat(UUID seat) {
        for (UUID id : seats) {
            if (!id.equals(seat)) {
                return id;
            }
        }
        return null;
    }

    /** 与指定棋色对阵的座位名称。 */
    private String opponentNameOf(int color) {
        for (Map.Entry<UUID, Integer> entry : seatColors.entrySet()) {
            if (entry.getValue() != color) {
                return nameOf(entry.getKey());
            }
        }
        return "对手";
    }
}