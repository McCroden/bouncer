package com.wesabe.bouncer;

import org.mortbay.jetty.Server;
import org.mortbay.jetty.client.HttpClient;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.servlet.GzipFilter;

import com.mchange.v2.c3p0.DataSources;
import com.mchange.v2.c3p0.PooledDataSource;
import com.wesabe.bouncer.auth.Authenticator;
import com.wesabe.bouncer.auth.WesabeAuthenticator;
import com.wesabe.bouncer.servlets.AuthenticationFilter;
import com.wesabe.bouncer.servlets.ProxyServlet;
import com.wesabe.servlet.SafeFilter;

public class Runner {
	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: java -jar <bouncer.jar> <config file> <port>");
			System.exit(-1);
		}
		
		final Configuration config = new Configuration(args[0]);
		final int port = Integer.valueOf(args[1]);
		
		final Server server = new Server(port);
		server.setGracefulShutdown(5000);
		server.setSendServerVersion(false);
		server.setStopAtShutdown(true);
		
		final Context context = new Context();
		
		if (config.isHttpCompressionEnabled()) {
			final FilterHolder gzipHolder = new FilterHolder(GzipFilter.class);
			gzipHolder.setInitParameter("minGzipSize", config.getHttpCompressionMinimumSize().toString());
			gzipHolder.setInitParameter("mimeTypes", config.getHttpCompressableMimeTypes());
			context.addFilter(gzipHolder, "/*", 0);
		}
		
		PooledDataSource dataSource = (PooledDataSource) DataSources.pooledDataSource(
				DataSources.unpooledDataSource(
						config.getJdbcUri().toASCIIString(),
						config.getJdbcUsername(),
						config.getJdbcPassword()
				),
				config.getC3P0Properties()
		);
		
		final Authenticator authenticator = new WesabeAuthenticator(dataSource);
		
		context.addFilter(new FilterHolder(
			new AuthenticationFilter(authenticator, config.getAuthenticationRealm(), config.getAuthenticationErrorMessage())
		), "/*", 0);
		
		final HttpClient client = new HttpClient();
		
		final ServletHolder proxyHolder = new ServletHolder(new ProxyServlet(config.getBackendUri(), client));
		context.addServlet(proxyHolder, "/*");
		
		context.addFilter(SafeFilter.class, "/*", 0);
		
		server.addHandler(context);
		
		server.start();
		server.join();
	}
}
