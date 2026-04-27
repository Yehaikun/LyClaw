package lyjew.com.lyclaw.base;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class BaseDTO {

    private String id;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
