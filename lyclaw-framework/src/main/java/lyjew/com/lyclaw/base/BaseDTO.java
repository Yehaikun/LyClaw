package lyjew.com.lyclaw.base;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BaseDTO {
    private String id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
