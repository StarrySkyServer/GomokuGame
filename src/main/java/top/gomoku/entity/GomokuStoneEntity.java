package top.gomoku.entity;

import cn.nukkit.Player;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.custom.CustomEntity;
import cn.nukkit.entity.custom.EntityDefinition;
import cn.nukkit.entity.data.Vector3fEntityData;
import cn.nukkit.entity.data.property.BooleanEntityProperty;
import cn.nukkit.entity.data.property.EntityProperty;
import cn.nukkit.entity.data.property.IntEntityProperty;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.level.format.FullChunk;
import cn.nukkit.nbt.tag.CompoundTag;
import top.gomoku.game.GomokuBoard;

/**
 * 棋子渲染实体，对应资源包中的 gomoku:stone。
 * <p>
 * 棋盘状态通过实体属性 {@code gomoku:row_0 .. gomoku:row_14}（三进制编码）同步给客户端，
 * 客户端动画据此缩放对应骨骼来显示黑白棋子；{@code hover}/{@code last} 控制准星高亮与最后一手标记，
 * {@code large} 控制棋盘网格放大到 1.6 倍以对齐 2x2 棋盘方块。
 */
public class GomokuStoneEntity extends Entity implements CustomEntity {

    public static final String IDENTIFIER = "gomoku:stone";

    public static final int SIZE = 15;

    /** 一行 15 格三进制最大值：3^15 - 1 */
    public static final int MAX_ROW_VALUE = 14348906;

    public static final String P_HOVER = "gomoku:hover";
    public static final String P_LAST = "gomoku:last";
    public static final String P_LARGE = "gomoku:large";

    public static final EntityDefinition DEF = EntityDefinition.builder()
            .identifier(IDENTIFIER)
            // 基岩版客户端会为实体绘制一块软边阴影，其大小取自实体“定义”里的碰撞箱，
            // 与 metadata 的 DATA_BOUNDING_BOX_WIDTH/HEIGHT（只决定准星命中箱）无关，
            // 所以此前把命中箱设到 0.001 也消不掉棋盘中央那块阴影。
            // 自定义实体在 Nukkit 里无法直接声明碰撞箱，只能通过运行时标识符（bid）
            // 继承一个不绘制阴影的原版实体：armor_stand 明确会禁用实体阴影，
            // 且其行为对纯展示实体没有副作用（模型仍由资源包的 gomoku:stone 客户端定义渲染）。
            .parentEntity("minecraft:armor_stand")
            .spawnEgg(false)
            .implementation(GomokuStoneEntity.class)
            .build();

    public static String rowName(int row) {
        return "gomoku:row_" + row;
    }

    /**
     * 注册客户端侧实体属性定义。必须在任何实体生成前调用（插件 onLoad）。
     */
    public static void registerProperties() {
        for (int row = 0; row < SIZE; row++) {
            EntityProperty.register(IDENTIFIER, new IntEntityProperty(rowName(row), 0, MAX_ROW_VALUE, 0));
        }
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_HOVER, 0, SIZE * SIZE, 0));
        EntityProperty.register(IDENTIFIER, new IntEntityProperty(P_LAST, 0, SIZE * SIZE, 0));
        EntityProperty.register(IDENTIFIER, new BooleanEntityProperty(P_LARGE, true));
    }

    public GomokuStoneEntity(FullChunk chunk, CompoundTag nbt) {
        super(chunk, nbt);
    }

    @Override
    public EntityDefinition getEntityDefinition() {
        return DEF;
    }

    @Override
    public int getNetworkId() {
        return DEF.getRuntimeId();
    }

    /**
     * 该实体只负责渲染棋子，不参与碰撞与射线检测。
     * <p>
     * 这三个值会被 Nukkit 通过 {@code DATA_BOUNDING_BOX_WIDTH/HEIGHT} 同步给客户端，
     * 决定客户端准星的实体命中箱。若沿用 2x2x0.2 会正好盖住棋盘顶面，导致点击棋盘
     * 命中的是实体而非方块（右键弹不出菜单、左键无法破坏收回）。
     * 设为极小值后射线会直接穿过实体命中棋盘方块。
     * 棋子模型的渲染由资源包几何体按实体坐标绘制，与命中箱大小无关。
     */
    @Override
    public float getHeight() {
        return 0.001f;
    }

    @Override
    public float getWidth() {
        return 0.001f;
    }

    @Override
    public float getLength() {
        return 0.001f;
    }

    @Override
    protected void initEntity() {
        super.initEntity();
        this.setImmobile(true);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_GRAVITY, false);
        this.setDataFlag(DATA_FLAGS, DATA_FLAG_HAS_COLLISION, false);
        this.setNameTagVisible(false);
        this.setNameTagAlwaysVisible(false);
        this.setCanBeSavedWithChunk(false);
        // 再同步一个极小碰撞箱，双保险：即便某些版本按 metadata 绘制阴影/命中箱也能消掉
        this.setDataProperty(new Vector3fEntityData(DATA_COLLISION_BOX, 0.001f, 0.001f, 0.001f));
        this.setBooleanEntityProperty(P_LARGE, true);
    }

    @Override
    public boolean canCollide() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    private GomokuBoard board;

    public void setBoard(GomokuBoard board) {
        this.board = board;
    }

    public GomokuBoard getBoard() {
        return board;
    }

    /**
     * 棋子实体仅用于渲染，不参与破坏与收回：攻击无效，收起棋盘改由菜单触发。
     */
    @Override
    public boolean attack(EntityDamageEvent source) {
        return false;
    }

    /**
     * 禁止一切击杀途径，使 {@code /kill @e} 等指令无法清除该实体（类似箱子那样不可杀死）。
     * <p>
     * 棋子的唯一清除方式是棋盘菜单“收起棋盘”，届时由 {@code GomokuBoard.remove()}
     * 直接调用 {@code close()}，不经过 {@code kill()}，所以此处留空不影响正常收回。
     */
    @Override
    public void kill() {
    }

    public void setRow(int row, int value) {
        if (this.setIntEntityProperty(rowName(row), value)) {
            this.syncProperties();
        }
    }

    public void setHover(int index) {
        if (this.setIntEntityProperty(P_HOVER, index)) {
            this.syncProperties();
        }
    }

    public void setLast(int index) {
        if (this.setIntEntityProperty(P_LAST, index)) {
            this.syncProperties();
        }
    }

    public void syncProperties() {
        if (this.getViewers().isEmpty()) {
            return;
        }
        this.sendData(this.getViewers().values().toArray(new Player[0]));
    }
}