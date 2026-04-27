package lyjew.com.lyclaw.storage;

import lyjew.com.lyclaw.exception.StorageException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Test {
    public static void main(String[] args) throws IOException {
        String path = "src/test/java/lyjew/com/lyclaw/storage";
        Stream<Path> walk = Files.walk(Path.of(path), 1);
        List<Path> collect = walk.collect(Collectors.toList());
        for (Path path1 : collect) {
            System.out.println(path1);
        }
        System.out.println();
        List<String> strings = listFiles("cron", "json");
        for (String string : strings) {
            System.out.println(string);
        }
    }

    public static List<String> listFiles(String relativePath, String suffix) {
        Path dir = Paths.get("test-data-f1", relativePath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> suffix == null || path.toString().endsWith(suffix))
                    .map(path -> relativePath + "/" + path.getFileName().toString())  // ✅ 必须转换
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new StorageException("STORAGE_LIST_ERROR",
                    "列出目录失败: " + relativePath, e);
        }
    }
}
