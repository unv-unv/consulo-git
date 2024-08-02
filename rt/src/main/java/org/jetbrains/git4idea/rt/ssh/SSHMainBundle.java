/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.git4idea.rt.ssh;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The bundle for SSH messages
 */
public class SSHMainBundle
{
	private static Reference<ResourceBundle> ourBundle;

	private static final String BUNDLE = "org.jetbrains.git4idea.ssh.SSHMainBundle";

	private SSHMainBundle()
	{
	}

	public static String message(String key, Object... params)
	{
		return message(getBundle(), key, params);
	}

	@Nonnull
	public static String message(@Nonnull ResourceBundle bundle, @Nonnull String key, @Nonnull Object... params)
	{
		return messageOrDefault(bundle, key, null, params);
	}

	public static String messageOrDefault(@Nullable final ResourceBundle bundle,
										  @Nonnull String key,
										  @Nullable final String defaultValue,
										  @Nonnull Object... params)
	{
		if(bundle == null)
		{
			return defaultValue;
		}

		String value;
		try
		{
			value = bundle.getString(key);
		}
		catch(MissingResourceException e)
		{
			if(defaultValue != null)
			{
				value = defaultValue;
			}
			else
			{
				value = "!" + key + "!";
			}
		}

		return format(value, params);
	}

	@Nonnull
	public static String format(@Nonnull String value, @Nonnull Object... params)
	{
		if(params.length > 0 && value.indexOf('{') >= 0)
		{
			return MessageFormat.format(value, params);
		}

		return value;
	}

	private static ResourceBundle getBundle()
	{
		ResourceBundle bundle = null;
		if(ourBundle != null)
		{
			bundle = ourBundle.get();
		}
		if(bundle == null)
		{
			bundle = ResourceBundle.getBundle(BUNDLE);
			ourBundle = new SoftReference<ResourceBundle>(bundle);
		}
		return bundle;
	}

	public static String getString(final String key)
	{
		return getBundle().getString(key);
	}
}
