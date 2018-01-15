package git4idea.push;

import com.intellij.dvcs.push.VcsPushOptionValue;

/**
 * from kotlin
 */
public class GitVcsPushOptionValue implements VcsPushOptionValue
{
	private final GitPushTagMode myPushTagMode;
	private final boolean mySkipHook;

	public GitVcsPushOptionValue(GitPushTagMode pushTagMode, boolean skipHook)
	{
		myPushTagMode = pushTagMode;
		mySkipHook = skipHook;
	}

	public GitPushTagMode getPushTagMode()
	{
		return myPushTagMode;
	}

	public boolean isSkipHook()
	{
		return mySkipHook;
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

		GitVcsPushOptionValue that = (GitVcsPushOptionValue) o;

		if(mySkipHook != that.mySkipHook)
		{
			return false;
		}
		if(myPushTagMode != null ? !myPushTagMode.equals(that.myPushTagMode) : that.myPushTagMode != null)
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		int result = myPushTagMode != null ? myPushTagMode.hashCode() : 0;
		result = 31 * result + (mySkipHook ? 1 : 0);
		return result;
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder("GitVcsPushOptionValue{");
		sb.append("myPushTagMode=").append(myPushTagMode);
		sb.append(", mySkipHook=").append(mySkipHook);
		sb.append('}');
		return sb.toString();
	}
}
