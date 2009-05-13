package com.wesabe.bouncer;

import org.mortbay.jetty.Connector;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.client.HttpClient;
import org.mortbay.jetty.nio.SelectChannelConnector;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.FilterHolder;
import org.mortbay.jetty.servlet.ServletHolder;
import org.mortbay.servlet.GzipFilter;
import org.mortbay.thread.QueuedThreadPool;

import com.mchange.v2.c3p0.DataSources;
import com.mchange.v2.c3p0.PooledDataSource;
import com.wesabe.bouncer.auth.Authenticator;
import com.wesabe.bouncer.auth.WesabeAuthenticator;
import com.wesabe.bouncer.proxy.ProxyHttpExchangeFactory;
import com.wesabe.bouncer.servlets.AuthenticationFilter;
import com.wesabe.bouncer.servlets.ProxyServlet;
import com.wesabe.servlet.ErrorReporterFilter;
import com.wesabe.servlet.SafeFilter;
import com.wesabe.servlet.errors.DebugErrorReporter;
import com.wesabe.servlet.errors.ErrorReporter;
import com.wesabe.servlet.errors.SendmailErrorReporter;

public class Runner {
	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			System.err.println("Usage: java -jar <bouncer.jar> <config file> <port>");
			System.exit(-1);
		}
		
		final Configuration config = new Configuration(args[0]);
		final int port = Integer.valueOf(args[1]);
		
		final Server server = new Server();
		final Connector connector = new SelectChannelConnector();
		connector.setPort(port);
		server.addConnector(connector);
		
		server.setGracefulShutdown(5000);
		server.setSendServerVersion(false);
		server.setStopAtShutdown(true);
		
		final Context context = new Context();
		context.addFilter(SafeFilter.class, "/*", 0);
		
		final ErrorReporter reporter;
		if (config.isDebug()) {
			reporter = new DebugErrorReporter("Exception Notifier <support@wesabe.com>", "you@wesabe.com", "bouncer");
		} else {
			reporter = new SendmailErrorReporter("Exception Notifier <support@wesabe.com>", "eng@wesabe.com", "bouncer", "/usr/sbin/sendmail");
		}
		
		final FilterHolder errorHolder = new FilterHolder(new ErrorReporterFilter(reporter, "Wesabe engineers have been alerted to this error. If you have further questions, please contact <support@wesabe.com>."));
		context.addFilter(errorHolder, "/*", 0);
		
		if (config.isHttpCompressionEnabled()) {
			final FilterHolder gzipHolder = new FilterHolder(GzipFilter.class);
			gzipHolder.setInitParameter("minGzipSize", config.getHttpCompressionMinimumSize().toString());
			gzipHolder.setInitParameter("mimeTypes", config.getHttpCompressableMimeTypes());
			context.addFilter(gzipHolder, "/*", 0);
		}
		
		final PooledDataSource dataSource = (PooledDataSource) DataSources.pooledDataSource(
				DataSources.unpooledDataSource(
						config.getJdbcUri().toASCIIString(),
						config.getJdbcUsername(),
						config.getJdbcPassword()
				),
				config.getC3P0Properties()
		);
		
		final Authenticator authenticator = new WesabeAuthenticator(dataSource);
		
		context.addFilter(new FilterHolder(
			new AuthenticationFilter(authenticator, config.getAuthenticationRealm())
		), "/*", 0);
		
		final HttpClient client = new HttpClient();
		client.setThreadPool(new QueuedThreadPool(20));
		client.setMaxConnectionsPerAddress(1000);
		final ProxyHttpExchangeFactory factory = new ProxyHttpExchangeFactory(config.getBackendUri());
		final ServletHolder proxyHolder = new ServletHolder(new ProxyServlet(client, factory));
		context.addServlet(proxyHolder, "/*");
		
		server.addHandler(context);
		
		server.start();
		server.join();
	}
}
