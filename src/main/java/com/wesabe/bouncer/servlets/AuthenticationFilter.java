package com.wesabe.bouncer.servlets;

import java.io.IOException;
import java.security.Principal;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.jetty.HttpHeaders;
import org.mortbay.jetty.Request;

import com.wesabe.bouncer.auth.Authenticator;
import com.wesabe.bouncer.auth.LockedAccountException;
import com.wesabe.servlet.SafeRequest;

public class AuthenticationFilter implements Filter {
	private final Authenticator authenticator;
	private final String challenge;
	
	public AuthenticationFilter(Authenticator authenticator, String realm) {
		this.authenticator = authenticator;
		this.challenge = "Basic realm=\"" + realm + "\"";
	}
	
	
	@Override
	public void destroy() {
		
	}

	@Override
	public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
			throws IOException, ServletException {
		final SafeRequest safeRequest = (SafeRequest) req;
		final Request request = (Request) safeRequest.getRequest();
		final HttpServletResponse response = (HttpServletResponse) resp;
		final Principal principal;
		try {
			principal = authenticator.authenticate(request);
		} catch (LockedAccountException e) {
			response.setIntHeader(HttpHeaders.RETRY_AFTER, e.getPenaltyDuration());
			response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			return;
		}
		
		if (principal != null) {
			request.setUserPrincipal(principal);
			request.setAuthType(HttpServletRequest.BASIC_AUTH);
			chain.doFilter(request, resp);
		} else {
			
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
		}
	}

	@Override
	public void init(FilterConfig config) throws ServletException {
		
	}

}
