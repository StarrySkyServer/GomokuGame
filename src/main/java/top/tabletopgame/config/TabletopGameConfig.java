package top.tabletopgame.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

import java.util.List;
import java.util.Map;

/**
 * 插件配置。字段默认值即首次运行生成的 config.yml 内容，可用 {@code /tabletopgame reload} 重载。
 * <p>
 * 奖励列表刻意使用 {@code List<Map<String, Integer>>} 而非自定义 POJO：
 * okaeri-configs 5.x 只内置了标准类型与集合的序列化器，任意 POJO 需要在
 * {@code Configurer} 上注册 {@code ObjectSerializer} 才能写盘，否则抛
 * "cannot simplify type" 异常。用 Map 可以零配置地读写 {@code {id, count}}。
 */
@Header({"五子棋插件配置文件", "修改后可用 /tabletopgame reload 重载（需 OP 或控制台）"})
public class TabletopGameConfig extends OkaeriConfig {

    @Comment("玩家离开棋盘中心多少格后自动退出棋局（水平方向）")
    private double playerRange = 7.0;

    @Comment("0 人入座时菜单的独占超时（秒）。超时或持有者下线会自动释放占用，让给其他人；有人入座后不再有该兜底")
    private int menuLockSeconds = 30;

    @Comment("战胜机器人后的奖励，可配置多项；每项包含 id（物品数字ID）与 count（数量）")
    private Rewards rewards = new Rewards();

    @Comment("赌博模式设置（仅玩家对玩家对局生效；本插件强依赖 EconomyAPI）")
    private Gambling gambling = new Gambling();

    public double getPlayerRange() {
        return playerRange;
    }

    /** 菜单独占超时秒数（至少 1 秒）。 */
    public int getMenuLockSeconds() {
        return Math.max(1, menuLockSeconds);
    }

    public Rewards getRewards() {
        return rewards;
    }

    public Gambling getGambling() {
        return gambling;
    }

    /** 三档机器人各自的胜利奖励。 */
    public static class Rewards extends OkaeriConfig {

        @Comment("战胜入门机器人（等级 1）。id=0 且 count=0 表示不发奖励")
        private List<Map<String, Integer>> beginner = List.of(entry(0, 0));

        @Comment("战胜进阶机器人（等级 2）。id=0 且 count=0 表示不发奖励")
        private List<Map<String, Integer>> normal = List.of(entry(0, 0));

        @Comment("战胜大师机器人（等级 3）。id=0 且 count=0 表示不发奖励")
        private List<Map<String, Integer>> master = List.of(entry(0, 0));

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

    /** 赌博模式：官方抽水比例与每局可下注金额区间。 */
    public static class Gambling extends OkaeriConfig {

        @Comment("官方每局抽水百分比（0~100，保留两位小数）。默认 0.3 即 3‰；100 表示赢家也拿不到奖金")
        private double cut = 0.3;

        @Comment("每局可下注的最小金额")
        private double minBet = 1000.0;

        @Comment("每局可下注的最大金额")
        private double maxBet = 1000000.0;

        public double getCut() {
            return cut;
        }

        public double getMinBet() {
            return minBet;
        }

        public double getMaxBet() {
            return maxBet;
        }

        /** 抽水比例：钳制到 0~100 并四舍五入保留两位小数。 */
        public double normalizedCut() {
            double c = Math.max(0.0, Math.min(100.0, cut));
            return Math.round(c * 100.0) / 100.0;
        }

        /** 每局最小下注金额（负数归零）。 */
        public double normalizedMinBet() {
            return Math.max(0.0, Math.round(minBet * 100.0) / 100.0);
        }

        /** 每局最大下注金额；若配置小于最小值则取最小值。 */
        public double normalizedMaxBet() {
            double max = Math.max(0.0, Math.round(maxBet * 100.0) / 100.0);
            return Math.max(max, normalizedMinBet());
        }
    }
}