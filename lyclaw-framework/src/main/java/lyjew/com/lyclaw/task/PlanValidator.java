package lyjew.com.lyclaw.task;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划校验器接口，对生成的任务计划进行合法性校验。
 * 包含一个内嵌的校验结果类。
 */
public interface PlanValidator {

    /**
     * 对任务计划进行校验。
     *
     * @param plan 待校验的任务计划
     * @return 校验结果
     */
    ValidationResult validate(TaskPlan plan);

    /**
     * 校验结果类，封装校验是否通过以及具体的错误信息列表。
     */
    class ValidationResult {
        /** 校验是否通过 */
        private final boolean valid;
        /** 错误信息列表 */
        private final List<String> errors = new ArrayList<>();

        public ValidationResult(boolean valid) { this.valid = valid; }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }

        /**
         * 添加一条错误信息。
         *
         * @param error 错误描述
         */
        public void addError(String error) { errors.add(error); }

        /** 创建校验通过的快捷工厂方法 */
        public static ValidationResult valid() { return new ValidationResult(true); }

        /** 创建校验失败的快捷工厂方法，附带失败原因 */
        public static ValidationResult invalid(String reason) {
            ValidationResult r = new ValidationResult(false);
            r.addError(reason);
            return r;
        }
    }
}
