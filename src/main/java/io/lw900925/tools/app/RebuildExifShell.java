package io.lw900925.tools.app;

import org.apache.commons.imaging.ImageReadException;
import org.apache.commons.imaging.ImageWriteException;
import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputDirectory;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Locale;

@ShellComponent
public class RebuildExifShell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RebuildExifShell.class);

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
                        if (filename.contains("_IMG_")) {
                            String baseFilename = filename.substring(0, filename.lastIndexOf("_IMG_"));
                            dateTime = ZonedDateTime.parse(baseFilename, DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm").withZone(ZoneId.systemDefault()).withLocale(Locale.CHINESE));
                        } else {
                            String baseFilename = filename.substring(0, filename.lastIndexOf(0x2e));
                            dateTime = ZonedDateTime.parse(baseFilename, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneId.systemDefault()).withLocale(Locale.CHINESE));
                        }
                        String strDateTime = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(dateTime);

                        directory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, strDateTime);
                        directory.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, strDateTime);

                        targetPath = Paths.get(target + File.separator + filename);
                    } catch (ImageReadException | ImageWriteException e) {
                        LOGGER.error("读取照片EXIF信息失败 - " + e.getMessage(), e);
                        return FileVisitResult.TERMINATE;
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
                        return FileVisitResult.TERMINATE;
                    }

                    Files.setAttribute(targetPath, "basic:creationTime", FileTime.from(dateTime.toInstant()));

                    LOGGER.info("已处理[{}/{}]个文件，源文件：{}，目标文件：{}", index, count, filename, targetPath.getFileName());

                    index++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
