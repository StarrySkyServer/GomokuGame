package top.gomoku.ui;

import cn.nukkit.Player;
import top.gomoku.game.GomokuBoard;

import java.util.Random;

/**
 * 五子棋棋盘菜单：根据当前座位与选色状态动态生成。
 *
 * <p>状态机：
 * <ul>
 *     <li>无人入座：选择执白 / 选择执黑 + 三个单人对战（敬请期待）</li>
 *     <li>一人入座（本人）：三个单人对战 + 退出棋局</li>
 *     <li>一人入座（其他人）：加入对方棋色</li>
 *     <li>两人入座（先加入者）：开始棋局（随机先手 / 对方先手 / 我方先手 / 继承棋局）+ 退出棋局</li>
 *     <li>两人入座（后加入者）：退出棋局</li>
 *     <li>对局进行中：不弹菜单</li>
 * </ul>
 *
 * <p>所有选项点击后都只执行对应动作，不再自动重新弹出菜单。
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
            addAiButtons(form, player);
            form.add("退出棋局", () -> board.leave(player.getUniqueId()));
        }
    }

    /** 未入座玩家看到的菜单。 */
    private static void buildVisitor(Simple form, Player player, GomokuBoard board) {
        int count = board.seatCount();
        if (count == 0) {
            form.add("选择执白方", () -> board.join(player, GomokuBoard.WHITE));
            form.add("选择执黑方", () -> board.join(player, GomokuBoard.BLACK));
            addAiButtons(form, player);
            addPickupButton(form, player, board);
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

    private static void addAiButtons(Simple form, Player player) {
        for (String level : AI_LEVELS) {
            form.add("单人对战（" + level + "）", () -> showComingSoon(player, level));
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

    private static void showComingSoon(Player player, String level) {
        new Modal("敬请期待 [Beta]", "单人对战（" + level + "）正在开发中，敬请期待！", "确定", "取消")
                .show(player);
    }
}