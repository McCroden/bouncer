package com.wesabe.bouncer.client.tests;

import static org.junit.Assert.*;

import java.io.IOException;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.google.common.collect.Lists;
import com.sun.grizzly.tcp.InputBuffer;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.util.buf.ByteChunk;
import com.wesabe.bouncer.client.ProxyRequest;
import com.wesabe.bouncer.client.ProxyRequestFactory;
import com.wesabe.bouncer.security.BadRequestException;
import com.wesabe.bouncer.security.SafeRequest;

@RunWith(Enclosed.class)
public class ProxyRequestFactoryTest {
	private static class StringInputBuffer implements InputBuffer {
		private final String content;
		private boolean alreadyRead = false;
		
		public StringInputBuffer(String content) {
			this.content = content;
		}
		
		@Override
		public int doRead(ByteChunk chunk, Request request) throws IOException {
			chunk.setBytes(content.getBytes(), 0, content.length());
			if (alreadyRead) {
				return -1;
			}
			
			this.alreadyRead = true;
			return 0;
		}
		
	}
	
	public static class Building_A_Request_From_A_Grizzly_Request_Without_An_Entity {
		private ProxyRequestFactory factory;
		private GrizzlyRequest grizzlyRequest;
		private SafeRequest request;
		
		@Before
		public void setup() throws Exception {
			Request connectionRequest = new Request();
			connectionRequest.method().setString("GET");
			connectionRequest.requestURI().setString("/hello");
			connectionRequest.remoteAddr().setString("123.45.67.89");
			connectionRequest.getMimeHeaders().setValue("Date").setString("Sun, 06 Nov 1994 08:49:37 GMT");
			connectionRequest.getMimeHeaders().setValue("Content-Type").setString("text/xml");
			connectionRequest.getMimeHeaders().setValue("Accept").setString("text/xml");
			connectionRequest.getMimeHeaders().setValue("X-Death").setString("FUEGO");
			connectionRequest.getMimeHeaders().setValue("Server").setString("ALSO FUEGO");
			connectionRequest.getMimeHeaders().setValue("Expect").setString("SMUGGLE \n\n 4 LIFE");
			
			this.grizzlyRequest = new GrizzlyRequest();
			grizzlyRequest.setRequest(connectionRequest);
			
			this.request = new SafeRequest(grizzlyRequest);
			
			this.factory = new ProxyRequestFactory();
		}
		
		private ProxyRequest getRequest() throws BadRequestException {
			final ProxyRequest proxyRequest = factory.buildFromGrizzlyRequest(request);
			return proxyRequest;
		}
		
		private String getRequestHeader(String headerName) throws BadRequestException {
			return Lists.newArrayList(getRequest().getHeaders(headerName)).toString();
		}
		
		@Test
		public void itHasTheOriginalMethod() throws Exception {
			assertEquals("GET", getRequest().getMethod());
		}
		
		@Test
		public void itHasTheOriginalRequestURI() throws Exception {
			assertEquals("/hello", getRequest().getURI().toString());
		}
		
		@Test
		public void itCopiesOverAllValidRequestHeaders() throws Exception {
			assertEquals("[accept: text/xml]", getRequestHeader("Accept"));
		}
		
		@Test
		public void itCopiesOverAllValidGeneralHeaders() throws Exception {
			assertEquals("[date: Sun, 06 Nov 1994 08:49:37 GMT]", getRequestHeader("Date"));
		}
		
		@Test
		public void itCopiesOverAllValidEntityHeaders() throws Exception {
			assertEquals("[content-type: text/xml]", getRequestHeader("Content-Type"));
		}
		
		@Test
		public void itDoesNotCopyOverValidResponseHeaders() throws Exception {
			assertEquals("[]", getRequestHeader("Server"));
		}
		
		@Test
		public void itDoesNotCopyOverAnyOtherHeaders() throws Exception {
			assertEquals("[]", getRequestHeader("X-Death"));
		}
		
		@Test
		public void itDoesNotCopyOverHeadersWithBadValues() throws Exception {
			assertEquals("[]", getRequestHeader("Expect"));
		}
		
		@Test
		public void itSetsTheXForwardedForHeader() throws Exception {
			assertEquals("[X-Forwarded-For: 123.45.67.89]", getRequestHeader("X-Forwarded-For"));
		}
		
		@Test
		public void itHasNoEntity() throws Exception {
			assertFalse(getRequest().hasEntity());
		}
	}
	
	public static class Building_A_Request_From_Grizzly_Request_With_An_Entity {
		private ProxyRequestFactory factory;
		private GrizzlyRequest grizzlyRequest;
		private SafeRequest request;
		
		@Before
		public void setup() throws Exception {
			Request connectionRequest = new Request();
			connectionRequest.method().setString("POST");
			connectionRequest.requestURI().setString("/hello");
			connectionRequest.remoteAddr().setString("123.45.67.89");
			connectionRequest.setInputBuffer(new StringInputBuffer("blah blah blah"));
			connectionRequest.setContentLength(14);
			
			this.grizzlyRequest = new GrizzlyRequest();
			grizzlyRequest.setRequest(connectionRequest);
			
			this.request = new SafeRequest(grizzlyRequest);
			
			this.factory = new ProxyRequestFactory();
		}
		
		@Test
		public void itHasAnEntity() throws Exception {
			final ProxyRequest proxyRequest = factory.buildFromGrizzlyRequest(request);
			assertTrue(proxyRequest.hasEntity());
			StringBuffer out = new StringBuffer();
		    byte[] b = new byte[4096];
		    for (int n; (n = proxyRequest.getEntity().getContent().read(b)) != -1;) {
		        out.append(new String(b, 0, n));
		    }
		    assertEquals("blah blah blah", out.toString());
		}
	}
}
