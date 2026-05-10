package lyjew.com.lyclaw.parser;

public interface ResponseParser {

    boolean canParse(String rawJson);

    <T> T parse(String rawJson, Class<T> clazz);

    String getFormat();
}
