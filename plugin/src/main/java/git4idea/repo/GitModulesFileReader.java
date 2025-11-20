package git4idea.repo;

import consulo.logging.Logger;
import jakarta.annotation.Nonnull;
import org.ini4j.Ini;
import org.ini4j.Profile;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * from kotlin
 */
public class GitModulesFileReader {
    private static interface ModuleBean {
        String getPath();

        String getUrl();
    }

    private static final Logger LOGGER = Logger.getInstance(GitModulesFileReader.class);
    private static final Pattern MODULE_SECTION = Pattern.compile("submodule \"(.*)\"", Pattern.CASE_INSENSITIVE);

    @Nonnull
    public static Collection<GitSubmoduleInfo> read(File file) {
        if (!file.exists()) {
            return Collections.emptyList();
        }

        Ini ini;
        try {
            ini = GitConfigHelper.loadIniFile(file);
        }
        catch (IOException e) {
            return Collections.emptyList();
        }

        List<GitSubmoduleInfo> modules = new ArrayList<>();
        ClassLoader classLoader = GitConfig.class.getClassLoader();
        for (Map.Entry<String, Profile.Section> entry : ini.entrySet()) {
            String sectionName = entry.getKey();
            Profile.Section section = entry.getValue();

            Matcher matcher = MODULE_SECTION.matcher(sectionName);
            if (matcher.matches() && matcher.groupCount() == 1) {
                ModuleBean bean = section.as(ModuleBean.class, classLoader);
                String path = bean.getPath();
                String url = bean.getUrl();
                if (path == null || url == null) {
                    LOGGER.warn("Partially defined submodule: " + section.toString());
                }
                else {
                    GitSubmoduleInfo module = new GitSubmoduleInfo(path, url);
                    LOGGER.debug("Found submodule " + module);
                    modules.add(module);
                }
            }
        }

        return modules;
    }
}
