package client.nilore.utils.math;

/**
 * 查表三角函数，供 BetterFPS 的 Fast Math 开关使用。
 *
 * <p>{@code Math.sin}/{@code Math.cos} 走的是 fdlibm：参数归约 + 多项式求值，单次调用几十个
 * 浮点操作，而且不开 {@code -ffast-math} 时还带边界分支。客户端每 tick 的朝向计算、实体朝向、
 * 抛射物弹道会把它们调用上百次，在渲染线程上是一笔能看见的开销。
 *
 * <p>这里换成 4096 项的等距正弦表 + 线性插值：两次数组读 + 一次乘加，最大绝对误差约
 * {@code 3e-7}（半个格距的二次项）。朝向最终会被鼠标格点量化（默认灵敏度约 0.15 度一格），
 * 这点误差落不到任何可见的地方。
 *
 * <p>开关是 {@code volatile} 的静态标志：BetterFPS 每个 tick 同步一次，热路径只读一个字段，
 * 不做模块查找。
 */
public final class FastMath {
    private static final int BITS = 12;
    private static final int SIZE = 1 << BITS;
    private static final int MASK = SIZE - 1;

    private static final float[] SIN = new float[SIZE];

    private static final float RAD_TO_INDEX = SIZE / (float) (Math.PI * 2.0);
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float HALF_PI = (float) (Math.PI / 2.0);
    private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);

    private static volatile boolean enabled;

    static {
        for (int i = 0; i < SIZE; i++) {
            SIN[i] = (float) Math.sin(i * (Math.PI * 2.0 / SIZE));
        }
    }

    private FastMath() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static float sin(float radians) {
        if (!enabled) {
            return (float) Math.sin(radians);
        }
        return table(radians);
    }

    public static float cos(float radians) {
        if (!enabled) {
            return (float) Math.cos(radians);
        }
        return table(radians + HALF_PI);
    }

    public static float sinDeg(float degrees) {
        return sin(degrees * DEG_TO_RAD);
    }

    public static float cosDeg(float degrees) {
        return cos(degrees * DEG_TO_RAD);
    }

    private static float table(float radians) {
        float wrapped = radians % TWO_PI;
        if (wrapped < 0.0f) {
            wrapped += TWO_PI;
        }
        float x = wrapped * RAD_TO_INDEX;
        int i = (int) x;
        if (i >= SIZE) {
            i = SIZE - 1;
        }
        int j = (i + 1) & MASK;
        float a = SIN[i];
        return a + (SIN[j] - a) * (x - i);
    }
}
