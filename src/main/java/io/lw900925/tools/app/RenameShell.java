package io.lw900925.tools.app;

import com.drew.imaging.ImageProcessingException;
import com.drew.imaging.jpeg.JpegMetadataReader;
import com.drew.imaging.mp4.Mp4MetadataReader;
import com.drew.imaging.png.PngMetadataReader;
import com.drew.imaging.quicktime.QuickTimeMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
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
import java.text.ParseException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

@ShellComponent
public class RenameShell {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenameShell.class);

    private static final String DEFAULT_PATTERN = "yyyy_MM_dd_HH_mm";
    private static final String METADATA_PATTERN_1 = "EEE MMM dd HH:mm:ss XXX yyyy";
    private static final String METADATA_PATTERN_2 = "yyyy:MM:dd HH:mm:ss";

    private static final Map<Set<String>, Map<String, Object>> METADATA_EXTRACTORS = ImmutableMap.<Set<String>, Map<String, Object>> builder()
            .put(
                    Sets.newHashSet("jpeg", "jpg"),
                    ImmutableMap.<String, Object> builder()
                            .put("patterns", Lists.newArrayList(METADATA_PATTERN_1, METADATA_PATTERN_2))
                            .put("tags", Lists.newArrayList("Date/Time Original", "File Modified Date"))
                            .put("extractor", new JpegMetadataExtractor())
                            .build()
            )
            .put(
                    Sets.newHashSet( "mp4", "m4v" ),
                    ImmutableMap.<String, Object> builder()
                            .put("patterns", Lists.newArrayList(METADATA_PATTERN_1))
                            .put("tags", Lists.newArrayList("Creation Time"))
                            .put("extractor", new Mp4MetadataExtractor())
                            .build()
            )
            .put(
                    Sets.newHashSet( "mov" ),
                    ImmutableMap.<String, Object> builder()
                            .put("patterns", Lists.newArrayList(METADATA_PATTERN_1))
                            .put("tags", Lists.newArrayList("Creation Time"))
                            .put("extractor", new MovMetadataExtractor())
                            .build()
            )
            .put(
                    Sets.newHashSet("png"),
                    ImmutableMap.<String, Object> builder()
                            .put("patterns", Lists.newArrayList(METADATA_PATTERN_1, METADATA_PATTERN_2, "yyyy:MM:dd HH:mm:ss[a]"))
                            .put("tags", Lists.newArrayList("File Modified Date"))
                            .put("extractor", new PngMetadataExtractor())
                            .build()
            )
            .build();

    @ShellMethod(value = "???????????????????????????")
    @SuppressWarnings("unchecked")
    public void rename(@ShellOption(valueProvider = CommandValueProvider.class, value = {"-S", "--source"}, help = "????????????") String source,
                       @ShellOption(valueProvider = CommandValueProvider.class, value = {"-T", "--target"}, help = "???????????????")String target) {
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

                    // ???????????????
                    String extension = filename.substring(filename.lastIndexOf(0x2e) + 1);

                    // ???????????????MetadataExtractor
                    Set<String> extensionSet = METADATA_EXTRACTORS.keySet().stream()
                            .filter(keys -> keys.contains(extension.toLowerCase()))
                            .findFirst().orElseThrow(() -> new UnsupportedOperationException("Unsupported file extension." + file));
                    Map<String, Object> extractorMap = METADATA_EXTRACTORS.get(extensionSet);


                    String strDateTime = null;
                    List<String> patterns = (List<String>) extractorMap.get("patterns");
                    List<String> tags = (List<String>) extractorMap.get("tags");
                    MetadataExtractor extractor = (MetadataExtractor) extractorMap.get("extractor");
                    try {
                        Metadata metadata = extractor.extract(file.toFile());

                        String tagDesc = StreamSupport.stream(metadata.getDirectories().spliterator(), false)
                                .map(Directory::getTags)
                                .flatMap(Collection::stream)
                                .filter(tag -> tags.contains(tag.getTagName()))
                                .findFirst().orElseThrow(() -> new NullPointerException(String.format("File [%s] cannot find metadata named %s", file, String.join(",", tags))))
                                .getDescription();

                        // ??????????????????Pattern
                        Locale locale = Locale.ENGLISH;
                        if (Pattern.compile("[\u4e00-\u9fa5]").matcher(tagDesc).find()) {
                            locale = Locale.CHINESE;
                        }
                        Date date = DateUtils.parseDate(tagDesc, locale, patterns.toArray(new String[0]));
                        strDateTime = DateTimeFormatter.ofPattern(DEFAULT_PATTERN).withZone(ZoneId.systemDefault()).format(date.toInstant());
                        if (StringUtils.isEmpty(strDateTime) || strDateTime.equals("null")) {
                            LOGGER.error("File [{}] has not original date.", filename);
                            return FileVisitResult.TERMINATE;
                        }

                    } catch (ImageProcessingException | ParseException e) {
                        LOGGER.error("Extract metadata failed, file: {}, message: {}", file, e.getMessage());
                        strDateTime = DateTimeFormatter.ofPattern(DEFAULT_PATTERN).withZone(ZoneId.systemDefault()).format(attrs.creationTime().toInstant());
                    }

                    // ?????????19xx?????????????????????????????????????????????????????????????????????
                    if (strDateTime.startsWith("19")) {
                        String creationTime = DateTimeFormatter.ofPattern(DEFAULT_PATTERN).format(attrs.creationTime().toInstant());
                        LOGGER.warn("File [{}] original date is {}, metadata may broken, I replace original data to {}.", filename, strDateTime, creationTime);
                        strDateTime = creationTime;
                    }

                    // ????????????????????????
                    String millisSecond = RandomStringUtils.random(4, false, true);
                    String destFilename = strDateTime + "_IMG_" + millisSecond + "." + extension.toUpperCase();
                    Path targetPath = Paths.get(target + File.separator + destFilename);
                    if (Files.notExists(targetPath.getParent())) {
                        Files.createDirectories(targetPath.getParent());
                    }
                    Files.copy(file, targetPath);

                    LOGGER.info("[{}/{}] - source:{} target:{}", index, count, filename, destFilename);

                    index++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.error(e.getMessage(), e);
        }
    }

    /**
     * ?????????????????????
     *
     * @author lw900925
     */
    public interface MetadataExtractor {
        Metadata extract(File file) throws ImageProcessingException, IOException;
    }

    public static class JpegMetadataExtractor implements MetadataExtractor {
        @Override
        public Metadata extract(File file) throws ImageProcessingException, IOException {
            return JpegMetadataReader.readMetadata(file);
        }
    }

    public static class Mp4MetadataExtractor implements MetadataExtractor {
        @Override
        public Metadata extract(File file) throws ImageProcessingException, IOException {
            return Mp4MetadataReader.readMetadata(file);
        }
    }

    public static class MovMetadataExtractor implements MetadataExtractor {
        @Override
        public Metadata extract(File file) throws ImageProcessingException, IOException {
            return QuickTimeMetadataReader.readMetadata(file);
        }
    }

    public static class PngMetadataExtractor implements MetadataExtractor {
        @Override
        public Metadata extract(File file) throws ImageProcessingException, IOException {
            return PngMetadataReader.readMetadata(file);
        }
    }
}
