package io.lw900925.tools.app;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.standard.CommandValueProvider;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ShellComponent
public class RebuildExifShell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildExifShell.class);

    @Autowired
    private AppProperties appProperties;

    @ShellMethod(value = "重建照片EXIF信息")
    public void rebuildExif(@ShellOption(valueProvider = CommandValueProvider.class, value = {"-S", "--source"}, help = "原文件夹") String source,
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

                    Path targetPath = null;
                    String extension = filename.substring(filename.lastIndexOf(0x2e) + 1);
                    if (Arrays.asList("jpeg", "jpg").contains(extension.toLowerCase())) {
                        targetPath = withImage(file, target);
                    } else if (Arrays.asList("mp4", "mov", "m4v").contains(extension.toLowerCase())) {
                        targetPath = withVideo(file, target);
                    }

                    if (targetPath == null) {
                        throw new RuntimeException("处理失败");
                    }

                    LOGGER.info("[{}/{}] - source:{} target:{}", index, count, filename, targetPath.getFileName());

                    index++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }


    private Path withImage(Path file, String target) throws IOException {
        String filename = file.getFileName().toString();

        Path targetPath = null;
        TiffOutputSet output = null;
        ZonedDateTime dateTime = null;
        try {
            JpegImageMetadata metadata = (JpegImageMetadata) Imaging.getMetadata(file.toFile());
            output = metadata.getExif().getOutputSet();
            TiffOutputDirectory directory = output.getOrCreateExifDirectory();

            // 移除原始拍摄日期
            directory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL);
            directory.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED);

            // 重建EXIF信息
            String strDateTime = getFilenameDateTime(filename);
            directory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, strDateTime);
            directory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, strDateTime);

            dateTime = ZonedDateTime.parse(strDateTime, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").withLocale(Locale.CHINESE).withZone(ZoneId.systemDefault()));

            targetPath = Paths.get(target + File.separator + filename);
        } catch (ImageReadException | ImageWriteException e) {
            LOGGER.error("读取照片EXIF信息失败 - " + e.getMessage(), e);
            return null;
        }

        if (Files.notExists(targetPath.getParent())) {
            Files.createDirectories(targetPath.getParent());
        }

        try (InputStream inputStream = Files.newInputStream(file);
             OutputStream outputStream = Files.newOutputStream(targetPath)) {
            ExifRewriter rewriter = new ExifRewriter();
            rewriter.updateExifMetadataLossless(inputStream, outputStream, output);
        } catch (ImageWriteException | ImageReadException e) {
            LOGGER.error("写入照片EXIF信息失败 - " + e.getMessage(), e);
            return null;
        }

        Files.setAttribute(targetPath, "basic:creationTime", FileTime.from(dateTime.toInstant()));
        return targetPath;
    }

    private Path withVideo(Path file, String target) throws IOException {
        String filename = file.getFileName().toString();
        String baseFilename = filename.substring(0, filename.lastIndexOf("_IMG_"));

        // 将文件拷贝到目标文件夹
        Path targetPath = Paths.get(target + File.separator + filename);
        if (Files.notExists(targetPath.getParent())) {
            Files.createDirectories(targetPath.getParent());
        }
        Files.copy(file, targetPath);

        // 格式化标签日期
        String strDateTime = getFilenameDateTime(filename);

        // 判断运行环境
        String command = "${cmd} ${args} ${tags} ${file}";
        String os = System.getProperty("os.name");
        if (StringUtils.containsIgnoreCase(os, "windows")) {
            String exiftool = appProperties.getExifTool().getPath() + File.separator + "exiftool.exe";
            command = StringUtils.replace(command, "${cmd}", exiftool);
        } else if (StringUtils.containsIgnoreCase(os, "linux") || StringUtils.containsIgnoreCase(os, "mac")) {
            throw new RuntimeException("linux 和 macOS 还没支持");
        }

        // 使用exiftool命令
        boolean backup = appProperties.getExifTool().isBackup();
        String delOrig = backup ? "" : "-overwrite_original";
        command = StringUtils.replace(command, "${args}", delOrig);

        // 填入exif标签值
        List<String> tags = ImmutableMap.<String, String> builder()
                .put("-CreateDate", strDateTime)
                .put("-ModifyDate", strDateTime)
                .put("-MediaCreateDate", strDateTime)
                .put("-MediaModifyDate", strDateTime)
                .put("-FileCreateDate", strDateTime + "+08:00")
                .put("-FileModifyDate", strDateTime + "+08:00")
                .build()
                .entrySet()
                .stream()
                .map(entry -> entry.getKey() + "=\"" + entry.getValue() + "\"")
                .collect(Collectors.toList());
        String strTags = String.join(" ", tags);

        command = StringUtils.replaceEach(command, new String[] {"${tags}", "${file}"}, new String[] {strTags, targetPath.toString()});

        // 运行命令
        Runtime runtime = Runtime.getRuntime();
        runtime.exec(command);

        return targetPath;
    }

    private String getFilenameDateTime(String filename) {
        ZonedDateTime dateTime;
        if (filename.contains("_IMG_")) {
            String baseFilename = filename.substring(0, filename.lastIndexOf("_IMG_"));
            dateTime = ZonedDateTime.parse(baseFilename, DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm").withZone(ZoneId.systemDefault()).withLocale(Locale.CHINESE));
        } else {
            String baseFilename = filename.substring(0, filename.lastIndexOf(0x2e));
            dateTime = ZonedDateTime.parse(baseFilename, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault()).withLocale(Locale.CHINESE));
        }
        return DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(dateTime);
    }
}
