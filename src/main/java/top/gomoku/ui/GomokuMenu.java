package top.gomoku.ui;

import cn.nukkit.Player;
import top.gomoku.game.GomokuBoard;

import java.util.Random;

/**
 * 五子棋棋盘菜单：根据当前座位与选色状态动态生成。
 *
 * <p>状态机：
 * <ul>
 *     <li>无人入座：选择执白 / 选择执黑 + 三个等级的单人对战 + 收起棋盘</li>
 *     <li>无人入座且已选单人对战：选择执白 / 选择执黑 + 退出棋局</li>
 *     <li>一人入座（本人）：三个等级的单人对战 + 退出棋局</li>
 *     <li>一人入座（其他人）：加入对方棋色</li>
 *     <li>两人入座（先加入者）：开始棋局（随机先手 / 对方先手 / 我方先手 / 继承棋局）+ 退出棋局</li>
 *     <li>两人入座（后加入者）：退出棋局</li>
 *     <li>对局进行中：不弹菜单</li>
 * </ul>
 *
 * <p>除「单人对战」这条流程（选对手 → 选棋色 → 开始棋局）会连续自动弹表单外，
 * 其余选项点击后都只执行对应动作，不自动重新弹出菜单。
 */
public final class GomokuMenu {

    private static final String[] AI_LEVELS = {"入门", "进阶", "大师"};
    private static final Random RANDOM = new Random();

    private GomokuMenu() {
    }

    public static void open(Player player, GomokuBoard board) {
        if (board.isRunning()) {
            return;
        }
        int myColor = board.seatColor(player.getUniqueId());
        Simple form = new Simple("五子棋 [Beta]", board.statusText());
        if (myColor != 0) {
            buildSeated(form, player, board, myColor);
        } else {
            buildVisitor(form, player, board);
        }
        if (form.buttonCount() == 0) {
            return;
        }
        form.show(player);
    }

    /** 已入座玩家看到的菜单。 */
    private static void buildSeated(Simple form, Player player, GomokuBoard board, int myColor) {
        if (board.seatCount() >= 2) {
            if (board.isFirstJoiner(player.getUniqueId())) {
                int opponent = myColor == GomokuBoard.BLACK ? GomokuBoard.WHITE : GomokuBoard.BLACK;
                form.add("开始棋局（随机先手）", () -> board.start(RANDOM.nextBoolean() ? GomokuBoard.BLACK : GomokuBoard.WHITE));
                form.add("开始棋局（对方先手）", () -> board.start(opponent));
                form.add("开始棋局（我方先手）", () -> board.start(myColor));
                form.add("开始棋局（继承棋局）", board::resume);
            }
            form.add("退出棋局", () -> board.leave(player.getUniqueId()));
        } else {
            addAiButtons(form, player, board);
            form.add("退出棋局", () -> board.leave(player.getUniqueId()));
        }
    }

    /** 未入座玩家看到的菜单。 */
    private static void buildVisitor(Simple form, Player player, GomokuBoard board) {
        int count = board.seatCount();
        if (count == 0) {
            if (board.hasPendingAi()) {
                // 已选单人对战、等待选色：这一步之后机器人会自动入座
                addColorButtons(form, player, board, true);
                form.add("退出棋局", () -> board.cancelPendingAi(player));
            } else {
                addColorButtons(form, player, board, false);
                addAiButtons(form, player, board);
                addPickupButton(form, player, board);
            }
        } else if (count == 1) {
            if (board.isColorTaken(GomokuBoard.WHITE)) {
                form.add("加入执黑方", () -> board.join(player, GomokuBoard.BLACK));
            } else {
                form.add("加入执白方", () -> board.join(player, GomokuBoard.WHITE));
            }
        } else {
            player.sendMessage("§c座位已满（2 人），无法加入。");
        }
    }

    /**
     * 棋色选择按钮。
     *
     * @param autoReopen 选完棋色后是否立即重新弹出菜单（人机对战的分步表单需要，普通流程不需要）
     */
    private static void addColorButtons(Simple form, Player player, GomokuBoard board, boolean autoReopen) {
        form.add("选择执白方", () -> {
            if (board.join(player, GomokuBoard.WHITE) && autoReopen) {
                board.reopenMenu(player);
            }
        });
        form.add("选择执黑方", () -> {
            if (board.join(player, GomokuBoard.BLACK) && autoReopen) {
                board.reopenMenu(player);
            }
        });
    }

    /**
     * 单人对战按钮。点击后只登记对手（未选色时暂存等级），
     * 并立即重新弹出菜单，形成「选对手 → 选棋色 → 开始棋局」的连续表单。
     */
    private static void addAiButtons(Simple form, Player player, GomokuBoard board) {
        for (int i = 0; i < AI_LEVELS.length; i++) {
            final int level = i + 1;
            form.add("单人对战（" + AI_LEVELS[i] + "）", () -> {
                if (board.chooseAi(player, level)) {
                    board.reopenMenu(player);
                }
            });
        }
    }

    /** 收起棋盘：仅 OP 可执行，其他玩家点击后仅收到权限提示。 */
    private static void addPickupButton(Simple form, Player player, GomokuBoard board) {
        if (player.isOp()) {
            form.add("收起棋盘", () -> board.requestPickup(player));
        } else {
            form.add("收起棋盘", () -> player.sendMessage("§c你没有权限进行该操作"));
        }
    }
}