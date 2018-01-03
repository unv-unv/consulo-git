package git4idea.repo;

import java.io.File;
import java.io.IOException;

import org.ini4j.Ini;
import org.jetbrains.annotations.NotNull;
import com.intellij.openapi.diagnostic.Logger;

/**
 * from kotlin
 */
class GitConfigHelper
{
	private static final Logger LOGGER = Logger.getInstance(GitConfigHelper.class);

	@NotNull
	static Ini loadIniFile(@NotNull File configFile) throws IOException
	{
		Ini ini = new Ini();
		ini.getConfig().setMultiOption(true);  // duplicate keys (e.g. url in [remote])
		ini.getConfig().setTree(false);        // don't need tree structure: it corrupts url in section name (e.g. [url "http://github.com/"]
		ini.getConfig().setLowerCaseOption(false);
		try
		{
			ini.load(configFile);
		}
		catch(IOException e)
		{
			LOGGER.warn("Couldn't load config file at " + configFile.getPath(), e);
			throw e;
		}
		return ini;
	}
}
