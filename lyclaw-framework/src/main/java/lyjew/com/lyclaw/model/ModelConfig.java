package lyjew.com.lyclaw.model;

import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfig extends BaseDTO {

    private String name;
    private String provider;
    private String apiKey;
    private String model;
    private String baseUrl;
    private boolean enabled;
}
