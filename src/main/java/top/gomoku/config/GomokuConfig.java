package top.gomoku.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

import java.util.List;
import java.util.Map;

/**
 * 插件配置。字段默认值即首次运行生成的 config.yml 内容，可用 {@code /gomoku reload} 重载。
 * <p>
 * 奖励列表刻意使用 {@code List<Map<String, Integer>>} 而非自定义 POJO：
 * okaeri-configs 5.x 只内置了标准类型与集合的序列化器，任意 POJO 需要在
 * {@code Configurer} 上注册 {@code ObjectSerializer} 才能写盘，否则抛
 * "cannot simplify type" 异常。用 Map 可以零配置地读写 {@code {id, count}}。
 */
@Header({"五子棋插件配置文件", "修改后可用 /gomoku reload 重载（需 OP 或控制台）"})
public class GomokuConfig extends OkaeriConfig {

    @Comment("玩家离开棋盘中心多少格后自动退出棋局（水平方向）")
    private double playerRange = 7.0;

    @Comment("战胜机器人后的奖励，可配置多项；每项包含 id（物品数字ID）与 count（数量）")
    private Rewards rewards = new Rewards();

    public double getPlayerRange() {
        return playerRange;
    }

    public Rewards getRewards() {
        return rewards;
    }

    /** 三档机器人各自的胜利奖励。 */
    public static class Rewards extends OkaeriConfig {

        @Comment("战胜入门机器人（等级 1）")
        private List<Map<String, Integer>> beginner = List.of(entry(265, 2));

        @Comment("战胜普通机器人（等级 2）")
        private List<Map<String, Integer>> normal = List.of(entry(266, 1));

        @Comment("战胜大师机器人（等级 3）")
        private List<Map<String, Integer>> master = List.of(entry(264, 1));

        public List<Map<String, Integer>> forLevel(int level) {
            switch (level) {
                case 1:
                    return beginner;
                case 2:
                    return normal;
                case 3:
                    return master;
                default:
                    return List.of();
            }
        }

        private static Map<String, Integer> entry(int id, int count) {
            return Map.of("id", id, "count", count);
        }
    }
}