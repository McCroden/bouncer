package com.wesabe.bouncer.adapters;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;

import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.wesabe.bouncer.client.BackendService;
import com.wesabe.bouncer.client.ProxyRequest;
import com.wesabe.bouncer.client.ProxyRequestFactory;
import com.wesabe.bouncer.client.ProxyResponseFactory;
import com.wesabe.bouncer.security.BadRequestException;
import com.wesabe.bouncer.security.SafeRequest;

public class ProxyAdapter extends GrizzlyAdapter {
	private static final Logger LOGGER = Logger.getLogger(ProxyAdapter.class.getName());
	private static final int BAD_GATEWAY = 502;
	private final BackendService backendService;
	private final ProxyRequestFactory requestFactory;
	private final ProxyResponseFactory responseFactory;

	public ProxyAdapter(BackendService backendService, ProxyRequestFactory requestFactory,
			ProxyResponseFactory responseFactory) {
		super();
		this.backendService = backendService;
		this.requestFactory = requestFactory;
		this.responseFactory = responseFactory;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void service(GrizzlyRequest request, GrizzlyResponse response) {
		try {
			try {
				ProxyRequest proxyRequest = requestFactory.buildFromGrizzlyRequest(new SafeRequest(request));
				HttpResponse proxyResponse = backendService.execute(proxyRequest);
				responseFactory.buildFromHttpResponse(proxyResponse, response);
				response.finishResponse();
			} catch (HttpException e) {
				LOGGER.log(Level.SEVERE, "Error proxying request to gateway", e);
				response.sendError(BAD_GATEWAY);
			}
		} catch (BadRequestException e) {
			try {
				LOGGER.log(Level.SEVERE, "Bad reqest", e);
				response.sendError(400);
			} catch (IOException e1) {
				LOGGER.log(Level.SEVERE, "Unhandled internal error", e1);
			}
		} catch (Exception e) {
			try {
				LOGGER.log(Level.SEVERE, "Internal error", e);
				response.sendError(500);
			} catch (IOException e1) {
				LOGGER.log(Level.SEVERE, "Unhandled internal error", e1);
			}
		}
	}
}
