package io.acelance.graph.dsl.saa.loop;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.flow.agent.loop.LoopStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 OverAllState 键值的 LOOP 退出策略（对齐 DSL exitCondition* + maxIterations）。
 *
 * <p>先判退出条件；未满足则按计数继续，直到 {@code maxCount}。</p>
 */
public final class StateKeyExitLoopStrategy implements LoopStrategy {

    private static final Logger log = LoggerFactory.getLogger(StateKeyExitLoopStrategy.class);

    private final int maxCount;
    private final String exitKey;
    private final String op;
    private final String threshold;

    /**
     * @param maxCount  迭代上限（会与 {@link #maxLoopCount()} 取较小值）
     * @param exitKey   退出条件 state 键
     * @param op        运算符：GT / GTE / LT / LTE / EQ / NEQ
     * @param threshold 比较值（字符串；数字则按 double 比较）
     */
    public StateKeyExitLoopStrategy(int maxCount, String exitKey, String op, String threshold) {
        this.maxCount = Math.min(Math.max(maxCount, 1), maxLoopCount());
        this.exitKey = Objects.requireNonNull(exitKey, "exitKey").trim();
        this.op = op == null ? "GT" : op.trim().toUpperCase(Locale.ROOT);
        this.threshold = threshold == null ? "" : threshold.trim();
    }

    @Override
    public Map<String, Object> loopInit(OverAllState state) {
        boolean start = maxCount > 0;
        return Map.of(
                loopCountKey(), 0,
                loopFlagKey(), start
        );
    }

    @Override
    public Map<String, Object> loopDispatch(OverAllState state) {
        if (exitConditionMet(state)) {
            log.info("LOOP 退出条件满足, key={}, op={}, threshold={}", exitKey, op, threshold);
            return Map.of(loopFlagKey(), false);
        }
        int count = (Integer) state.value(loopCountKey(), maxCount);
        if (count >= maxCount) {
            log.info("LOOP 达到 maxIterations={}, 强制退出", maxCount);
            return Map.of(loopFlagKey(), false);
        }
        return Map.of(
                loopCountKey(), count + 1,
                loopFlagKey(), true
        );
    }

    private boolean exitConditionMet(OverAllState state) {
        if (!StringUtils.hasText(exitKey) || state == null) {
            return false;
        }
        Object raw = state.value(exitKey).orElse(null);
        if (raw == null) {
            return false;
        }
        String left = String.valueOf(raw).trim();
        try {
            double lv = Double.parseDouble(left);
            double rv = Double.parseDouble(threshold);
            return switch (op) {
                case "GTE", "GE" -> lv >= rv;
                case "LT", "LESS" -> lv < rv;
                case "LTE", "LE" -> lv <= rv;
                case "EQ", "EQUALS" -> Double.compare(lv, rv) == 0;
                case "NEQ", "NE", "NOT_EQUALS" -> Double.compare(lv, rv) != 0;
                default -> lv > rv; // GT
            };
        } catch (NumberFormatException ex) {
            return switch (op) {
                case "EQ", "EQUALS" -> left.equals(threshold);
                case "NEQ", "NE", "NOT_EQUALS" -> !left.equals(threshold);
                default -> false;
            };
        }
    }
}
