package lyjew.com.lyclaw.task;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划验证器 —— 验证 TaskPlan 的可行性和完整性。
 *
 * @since 2.0
 */
public interface PlanValidator {

    ValidationResult validate(TaskPlan plan);

    class ValidationResult {
        private final boolean valid;
        private final List<String> errors = new ArrayList<>();

        public ValidationResult(boolean valid) {
            this.valid = valid;
        }

        public boolean isValid() { return valid; }
        public List<String> getErrors() { return errors; }
        public void addError(String error) { errors.add(error); }

        public static ValidationResult valid() { return new ValidationResult(true); }
        public static ValidationResult invalid(String reason) {
            ValidationResult r = new ValidationResult(false);
            r.addError(reason);
            return r;
        }
    }
}
