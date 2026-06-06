package lyjew.com.lyclaw;

import java.util.Collection;
import java.util.Iterator;

public class Test {
    public static void main(String[] args) {
        int i = 10;
        Integer j = i;
        Integer o = 4;
        int k = o;

        op(1,2,3,4,5);
    }
    public static <A extends Comparable<A>> A max(Collection<A> xs) {
        Iterator<A> xi = xs.iterator();
        A w = xi.next();
        while (xi.hasNext()) {
            A x = xi.next();
            if (w.compareTo(x) < 0)
                w = x;
        }
        return w;
    }
    public static <T extends Integer> T op(T... args){
        for (int i = 0; i < args.length; i++) {
            System.out.println(args[i]);
        }
        return args[0];
    }
}
