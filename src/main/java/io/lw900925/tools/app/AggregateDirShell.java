package io.lw900925.tools.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.CommandValueProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@ShellComponent
public class AggregateDirShell {

    private static final Logger LOGGER = LoggerFactory.getLogger(AggregateDirShell.class);

    @ShellMethod(value = "整理&重命名文件夹")
    public void aggregateDir(@ShellOption(valueProvider = CommandValueProvider.class, value = {"-S", "--source"}, help = "原文件夹") String source,
                             @ShellOption(valueProvider = CommandValueProvider.class, value = {"-T", "--target"}, help = "目标文件夹")String target) {
        try {
            long count = Files.list(Paths.get(source)).count();

            int index = 1;
            List<Path> paths = Files.list(Paths.get(source)).collect(Collectors.toList());
            for (Path path : paths) {
                List<LocalDateTime> localDateTimes = new ArrayList<>();
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String filename = file.getFileName().toString();
                        filename = filename.substring(0, filename.lastIndexOf("_IMG")) + DateTimeFormatter.ofPattern("_ss_SSS").format(LocalDateTime.now());
                        localDateTimes.add(LocalDateTime.parse(filename, DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss_SSS")));
                        return FileVisitResult.CONTINUE;
                    }
                });

                // 取日期最早的一条作为文件夹名
                LocalDateTime localDateTime = localDateTimes.stream().min(Comparator.naturalOrder()).orElseThrow(() -> new NullPointerException(String.format("发生错误，未找到文件[%s]", path)));
                String filename = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(localDateTime);
                Path dest = Paths.get(target, filename);

                // COPY
                FileSystemUtils.copyRecursively(path, dest);
                LOGGER.info("[{}/{}] - source:{} target:{}", index, count, path, dest);
                index ++;
            }
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

}
