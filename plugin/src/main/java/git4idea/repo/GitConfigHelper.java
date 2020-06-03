package git4idea.repo;

import java.io.File;
import java.io.IOException;

import org.ini4j.Ini;
import javax.annotation.Nonnull;

import consulo.logging.Logger;

/**
 * from kotlin
 */
class GitConfigHelper
{
	private static final Logger LOGGER = Logger.getInstance(GitConfigHelper.class);

	@Nonnull
	static Ini loadIniFile(@Nonnull File configFile) throws IOException
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
