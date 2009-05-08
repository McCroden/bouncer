package com.wesabe.bouncer.proxy;

import java.io.IOException;
import java.net.URI;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.io.Buffer;
import org.mortbay.jetty.client.HttpExchange;

import com.wesabe.servlet.normalizers.util.CaseInsensitiveSet;

public class ProxyHttpExchange extends HttpExchange {
	/**
	 * Valid HTTP 1.1 general header fields.
	 * 
	 * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.5">RFC 2616 Section 4.5</a>
	 */
	protected static final Set<String> GENERAL_HEADERS = CaseInsensitiveSet.of(Locale.US,
		"Cache-Control",
		"Connection",
		"Date",
		"Pragma",
		"Trailer",
		"Transfer-Encoding",
		"Upgrade",
		"Via",
		"Warning"
	);
	
	/**
	 * Valid HTTP 1.1 entity header fields.
	 * 
	 * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec7.html#sec7.1">RFC 2616, Section 7.1</a>
	 */
	protected static final Set<String> ENTITY_HEADERS = CaseInsensitiveSet.of(Locale.US,
		"Allow",
		"Content-Encoding",
		"Content-Language",
		"Content-Length",
		"Content-Location",
		"Content-MD5",
		"Content-Range",
		"Content-Type",
		"Expires",
		"Last-Modified"
	);
	
	/**
	 * Valid HTTP 1.1 request header fields.
	 * 
	 * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec5.html#sec5.3">RFC 2616, Section 5.3</a>
	 */
	protected static final Set<String>  REQUEST_HEADERS = CaseInsensitiveSet.of(Locale.US,
		"Accept",
		"Accept-Charset",
		"Accept-Encoding",
		"Accept-Language",
		"Authorization",
		"Expect",
		"From",
		"Host",
		"If-Match",
		"If-Modified-Since",
		"If-None-Match",
		"If-Range",
		"If-Unmodified-Since",
		"Max-Forwards",
		"Proxy-Authorization",
		"Range",
		"Referer",
		"TE",
		"User-Agent"
	);
	
	/**
	 * Valid HTTP 1.1 response header fields.
	 * 
	 * @see <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec6.html#sec6.2">RFC 2616, Section 6.2</a>
	 */
	protected static final Set<String>  RESPONSE_HEADERS = CaseInsensitiveSet.of(Locale.US,
		"Accept-Ranges",
		"Age",
		"ETag",
		"Location",
		"Proxy-Authenticate",
		"Retry-After",
		"Vary",
		"WWW-Authenticate"
	);
	
	/**
	 * Unproxyable headers.
	 */
	protected static final Set<String>  UNPROXYABLE_HEADERS = CaseInsensitiveSet.of(Locale.US,
		"Proxy-Connection",
		"Connection",
		"Keep-Alive",
		"Transfer-Encoding",
		"TE",
		"Trailer",
		"Proxy-Authorization",
		"Proxy-Authenticate",
		"Upgrade",
		"Server"
	);
	
	private final URI backendUri;
	private final HttpServletRequest request;
	private final HttpServletResponse response;
	
	public ProxyHttpExchange(URI backend, HttpServletRequest request, HttpServletResponse response) {
		this.backendUri = backend;
		this.request = request;
		this.response = response;
		buildFromRequest(request);
	}
	
	public URI getBackendUri() {
		return backendUri;
	}
	
	public HttpServletRequest getRequest() {
		return request;
	}
	
	public HttpServletResponse getResponse() {
		return response;
	}
	
	private void buildFromRequest(HttpServletRequest request) {
		addRequestHeader("Authorization", request.getUserPrincipal().toString());
		setVersion(request.getProtocol());
		setMethod(request.getMethod());
		setURL(buildProxyUrl(request));

		final Enumeration<?> names = request.getHeaderNames();
		while (names.hasMoreElements()) {
			String name = (String) names.nextElement();
			if (!UNPROXYABLE_HEADERS.contains(name)
					&& (GENERAL_HEADERS.contains(name)
							|| ENTITY_HEADERS.contains(name)
							|| REQUEST_HEADERS.contains(name))) {
				final Enumeration<?> values = request.getHeaders(name);
				while (values.hasMoreElements()) {
					String value = (String) values.nextElement();
					addRequestHeader(name, value);
				}
			}
		}

		if (request.getContentType() != null) {
			try {
				setRequestContentSource(request.getInputStream());
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private String buildProxyUrl(HttpServletRequest request) {
		final StringBuilder urlBuilder = new StringBuilder();
		urlBuilder.append(request.getRequestURI());
		final String queryString = request.getQueryString();
		if (queryString != null) {
			urlBuilder.append('?').append(queryString);
		}
		return backendUri.resolve(urlBuilder.toString()).toASCIIString();
	}

	@Override
	protected void onResponseContent(Buffer content) throws IOException {
		content.writeTo(response.getOutputStream());
	}

	@Override
	protected void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException {
		response.setStatus(status);
	}

	@Override
	protected void onResponseHeader(Buffer nameBuffer, Buffer valueBuffer) throws IOException {
		if (nameBuffer != null) {
			final String name = nameBuffer.toString();
			if (name != null) {
				if (!UNPROXYABLE_HEADERS.contains(name)
						&& (GENERAL_HEADERS.contains(name)
								|| ENTITY_HEADERS.contains(name)
								|| RESPONSE_HEADERS.contains(name))) {
					if (valueBuffer != null) {
						response.addHeader(name, valueBuffer.toString());
					}
				}
			}
		}
	}
}
