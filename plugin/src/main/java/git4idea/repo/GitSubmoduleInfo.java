package git4idea.repo;

import org.jetbrains.annotations.NotNull;

/**
 * from kotlin
 */
public class GitSubmoduleInfo
{
	private final String myPath;
	private final String myUrl;

	public GitSubmoduleInfo(@NotNull String path, @NotNull  String url)
	{
		myPath = path;
		myUrl = url;
	}

	public String getPath()
	{
		return myPath;
	}

	public String getUrl()
	{
		return myUrl;
	}

	@Override
	public boolean equals(Object o)
	{
		if(this == o)
		{
			return true;
		}
		if(o == null || getClass() != o.getClass())
		{
			return false;
		}

		GitSubmoduleInfo that = (GitSubmoduleInfo) o;

		if(!myPath.equals(that.myPath))
		{
			return false;
		}
		if(!myUrl.equals(that.myUrl))
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		int result = myPath.hashCode();
		result = 31 * result + myUrl.hashCode();
		return result;
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("GitSubmoduleInfo{");
		sb.append("myPath='").append(myPath).append('\'');
		sb.append(", myUrl='").append(myUrl).append('\'');
		sb.append('}');
		return sb.toString();
	}
}
