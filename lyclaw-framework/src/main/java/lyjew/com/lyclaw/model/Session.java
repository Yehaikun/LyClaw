package lyjew.com.lyclaw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;
import lombok.experimental.SuperBuilder;
import lyjew.com.lyclaw.base.BaseDTO;

import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Session extends BaseDTO {
    private String sessionId;
    private String name;
    private String model;

    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    public void addMessage(Message message) {
        if (this.messages == null) {
            this.messages = new ArrayList<>();
        }
        this.messages.add(message);
    }
}
