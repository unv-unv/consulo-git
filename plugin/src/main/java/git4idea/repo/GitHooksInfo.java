package git4idea.repo;

/**
 * from kotlin
 */
public class GitHooksInfo
{
	private final boolean myPreCommitHookAvailable;
	private final boolean myPrePushHookAvailable;

	public GitHooksInfo(boolean preCommitHookAvailable, boolean prePushHookAvailable)
	{
		myPreCommitHookAvailable = preCommitHookAvailable;
		myPrePushHookAvailable = prePushHookAvailable;
	}

	public boolean isPreCommitHookAvailable()
	{
		return myPreCommitHookAvailable;
	}

	public boolean isPrePushHookAvailable()
	{
		return myPrePushHookAvailable;
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("GitHooksInfo{");
		sb.append("myPreCommitHookAvailable=").append(myPreCommitHookAvailable);
		sb.append(", myPrePushHookAvailable=").append(myPrePushHookAvailable);
		sb.append('}');
		return sb.toString();
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

		GitHooksInfo that = (GitHooksInfo) o;

		if(myPreCommitHookAvailable != that.myPreCommitHookAvailable)
		{
			return false;
		}
		if(myPrePushHookAvailable != that.myPrePushHookAvailable)
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		int result = (myPreCommitHookAvailable ? 1 : 0);
		result = 31 * result + (myPrePushHookAvailable ? 1 : 0);
		return result;
	}
}
