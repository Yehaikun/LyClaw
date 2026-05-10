package lyjew.com.lyclaw.strategy;

public interface FormatStrategy<T> {

    String serialize(T entity);

    T deserialize(String content, Class<T> clazz);

    String suffix();
}
