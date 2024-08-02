/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.git4idea.rt.http;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;
import jakarta.annotation.Nonnull;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Calls {@link GitAskPassXmlRpcHandler} methods via XML RPC.
 *
 * @author Kirill Likhodedov
 */
class GitAskPassXmlRpcClient
{
	@Nonnull
	private final XmlRpcClient myClient;

	GitAskPassXmlRpcClient(int port) throws MalformedURLException
	{
		XmlRpcClientConfigImpl clientConfig = new XmlRpcClientConfigImpl();
		clientConfig.setServerURL(new URL("http://127.0.0.1:" + port + "/RPC2"));
		myClient = new XmlRpcClient();
		myClient.setConfig(clientConfig);
	}

	String askUsername(String token, @Nonnull String url)
	{
		List<Object> parameters = new ArrayList<Object>();
		parameters.add(token);
		parameters.add(url);

		try
		{
			return (String) myClient.execute(methodName("askUsername"), parameters);
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	String askPassword(String token, @Nonnull String url)
	{
		List<Object> parameters = new ArrayList<Object>();
		parameters.add(token);
		parameters.add(url);

		try
		{
			return (String) myClient.execute(methodName("askPassword"), parameters);
		}
		catch(XmlRpcException e)
		{
			throw new RuntimeException("Invocation failed " + e.getMessage(), e);
		}
	}

	@Nonnull
	private static String methodName(@Nonnull String method)
	{
		return GitAskPassXmlRpcHandler.HANDLER_NAME + "." + method;
	}
}
