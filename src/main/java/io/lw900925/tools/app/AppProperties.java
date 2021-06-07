package io.lw900925.tools.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "app")
@Component
public class AppProperties {
    private EXIFTool exifTool = new EXIFTool();

    public EXIFTool getExifTool() {
        return exifTool;
    }

    public void setExifTool(EXIFTool exifTool) {
        this.exifTool = exifTool;
    }

    public static class EXIFTool {
        private String path;
        private boolean backup;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isBackup() {
            return backup;
        }

        public void setBackup(boolean backup) {
            this.backup = backup;
        }
    }
}
