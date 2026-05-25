package lyjew.com.lyclaw.reflect.topology;

public enum EdgeCondition {
    ALWAYS,
    ON_RETRY,
    ON_STOP,
    ON_FALLBACK,
    ON_CONTINUE,
    ON_SUCCESS,
    ON_FAIL,
    ON_SCORE_ABOVE,
    ON_SCORE_BELOW,
    ON_IMPORTANCE_ABOVE,
    ON_IMPORTANCE_BELOW,
    ON_CONSISTENT,
    ON_INCONSISTENT,
    ON_BRANCH,
    ON_RETRIEVE,
    ON_NO_RETRIEVE
}
