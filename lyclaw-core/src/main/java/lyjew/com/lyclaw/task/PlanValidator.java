package lyjew.com.lyclaw.task;

import java.util.ArrayList;
import java.util.List;

public interface PlanValidator {

    ValidationResult validate(TaskPlan plan);

    class ValidationResult {
        private final boolean valid;
        private final List<String> errors = new ArrayList<>();

        public ValidationResult(boolean valid) { this.valid = valid; }

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
