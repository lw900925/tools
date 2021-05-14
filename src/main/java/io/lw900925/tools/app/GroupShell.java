package io.lw900925.tools.app;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.shell.standard.CommandValueProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

@ShellComponent
public class GroupShell {

    private static final Logger LOGGER = LoggerFactory.getLogger(GroupShell.class);

    private static final Map<String, String> MONTHS = ImmutableMap.<String, String> builder()
            .put("01", "01.Jan")
            .put("02", "02.Feb")
            .put("03", "03.Mar")
            .put("04", "04.Apr")
            .put("05", "05.May")
            .put("06", "06.Jun")
            .put("07", "07.Jul")
            .put("08", "08.Aug")
            .put("09", "09.Sep")
            .put("10", "10.Oct")
            .put("11", "11.Nov")
            .put("12", "12.Dec")
            .build();

    @ShellMethod(value = "将文件夹中的文件按月份分组，输出到目标文件夹")
    public void group(@ShellOption(valueProvider = CommandValueProvider.class, value = {"-S", "--source"}, help = "原文件夹") String source,
                      @ShellOption(valueProvider = CommandValueProvider.class, value = {"-T", "--target"}, help = "目标文件夹")String target) {
        try {
            long count = Files.walk(Paths.get(source)).filter(path -> !Files.isDirectory(path)).count();

            Files.walkFileTree(Paths.get(source), new SimpleFileVisitor<Path>() {

                private int index = 1;

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {

                    String filename = file.getFileName().toString();
                    if (filename.contains(".DS_Store")) {
                        return FileVisitResult.CONTINUE;
                    }

                    // 获取文件名中的年份和月份
                    String year = filename.substring(0, 4);
                    String month = filename.substring(4, 6);
                    if (month.contains("_")) {
                        month = filename.substring(5, 7);
                    }

                    Path targetPath = Paths.get(target + File.separator + year + File.separator + MONTHS.get(month) + File.separator + filename);
                    if (Files.notExists(targetPath.getParent())) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    Files.copy(file, targetPath);

                    LOGGER.info("已处理[{}/{}]个文件 - {}", index, count, filename);

                    index++;

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
