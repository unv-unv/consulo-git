package git4idea.repo;

import consulo.logging.Logger;
import jakarta.annotation.Nonnull;
import org.ini4j.Ini;

import java.io.File;
import java.io.IOException;

/**
 * from kotlin
 */
class GitConfigHelper {
    private static final Logger LOGGER = Logger.getInstance(GitConfigHelper.class);

    @Nonnull
    static Ini loadIniFile(@Nonnull File configFile) throws IOException {
        Ini ini = new Ini();
        // duplicate keys (e.g. url in [remote])
        ini.getConfig().setMultiOption(true);
        // don't need tree structure: it corrupts url in section name (e.g. [url "http://github.com/"]
        ini.getConfig().setTree(false);
        ini.getConfig().setLowerCaseOption(false);
        try {
            ini.load(configFile);
        }
        catch (IOException e) {
            LOGGER.warn("Couldn't load config file at " + configFile.getPath(), e);
            throw e;
        }
        return ini;
    }
}
