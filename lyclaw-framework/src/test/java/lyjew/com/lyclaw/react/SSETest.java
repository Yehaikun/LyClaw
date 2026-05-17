package lyjew.com.lyclaw.react;

import reactor.core.publisher.Flux;

import javax.sound.midi.Soundbank;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.*;

public class SSETest {
    public static void main(String[] args) {
        System.out.println(123);
        Function<String, Integer> fn1 = s -> s.length();
        Consumer<String> fn2 = s -> {
            System.out.println("输入的s是: "+s);

        };
        fn2.accept("qwe21");
        System.out.println(fn1.apply("12345"));
        Supplier<Integer> su = ()-> 2;
        System.out.println(su.get());
        Predicate<String> isGreater = s -> {return s.length()>5;};
        System.out.println(isGreater.test("12345"));

        //0.6练习题
        List<String> names = Arrays.asList("Alice", "Bob", "Charlie");
        Collections.sort(names, (o1, o2)-> o1.length()-o2.length());
        names.forEach(System.out::println);
        // String::trim;
        // Math::max
        // user::getName
        // Object::new
        Function<String, String> f1 = String::trim;
        BinaryOperator<Integer> f2 = Math::max;
        Supplier<Object> f3 = Object::new;
        System.out.println(f1.apply("  1 3  "));
        System.out.println(f2.apply(2, 3));
        System.out.println(f3.get());
    }
}
