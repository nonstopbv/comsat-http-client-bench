import jersey.repackaged.com.google.common.util.concurrent.FutureCallback;
import jersey.repackaged.com.google.common.util.concurrent.Futures;
import jersey.repackaged.com.google.common.util.concurrent.SettableFuture;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.api.*;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.OutputStreamContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.ClientRequest;
import org.glassfish.jersey.client.ClientResponse;
import org.glassfish.jersey.client.spi.AsyncConnectorCallback;
import org.glassfish.jersey.client.spi.Connector;
import org.glassfish.jersey.internal.util.collection.ByteBufferInputStream;
import org.glassfish.jersey.internal.util.collection.NonBlockingInputStream;
import org.glassfish.jersey.jetty.connector.JettyClientProperties;
import org.glassfish.jersey.jetty.connector.LocalizationMessages;
import org.glassfish.jersey.message.internal.HeaderUtils;
import org.glassfish.jersey.message.internal.OutboundMessageContext;
import org.glassfish.jersey.message.internal.Statuses;

import javax.net.ssl.SSLContext;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.MultivaluedMap;
import java.io.*;
import java.net.CookieStore;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Created by fabio on 11/18/15.
 */
public class CustomJettyConnector implements Connector {

	private static final Logger LOGGER = Logger.getLogger(CustomJettyConnector.class.getName());

	private final HttpClient client;
	private final CookieStore cookieStore;

	/**
	 * Create the new Jetty client connector.
	 *
	 * @param jaxrsClient JAX-RS client instance, for which the connector is created.
	 * @param config client configuration.
	 */
	CustomJettyConnector(final Client jaxrsClient, final Configuration config) {
		final SSLContext sslContext = jaxrsClient.getSslContext();
		final SslContextFactory sslContextFactory = new SslContextFactory();
		sslContextFactory.setSslContext(sslContext);
		this.client = new HttpClient(sslContextFactory);

		this.client.setMaxConnectionsPerDestination(100000);
		this.client.setMaxRequestsQueuedPerDestination(100000);

		final Object connectTimeout = config.getProperties().get(ClientProperties.CONNECT_TIMEOUT);
		if (connectTimeout != null && connectTimeout instanceof Integer && (Integer) connectTimeout > 0) {
			client.setConnectTimeout((Integer) connectTimeout);
		}
		final Object threadPoolSize = config.getProperties().get(ClientProperties.ASYNC_THREADPOOL_SIZE);
		if (threadPoolSize != null && threadPoolSize instanceof Integer && (Integer) threadPoolSize > 0) {
			final String name = HttpClient.class.getSimpleName() + "@" + hashCode();
			final QueuedThreadPool threadPool = new QueuedThreadPool((Integer) threadPoolSize);
			threadPool.setName(name);
			client.setExecutor(threadPool);
		}
		Boolean disableCookies = (Boolean) config.getProperties().get(JettyClientProperties.DISABLE_COOKIES);
		disableCookies = (disableCookies != null) ? disableCookies : false;

		final AuthenticationStore auth = client.getAuthenticationStore();
		final Object basicAuthProvider = config.getProperty(JettyClientProperties.PREEMPTIVE_BASIC_AUTHENTICATION);
		if (basicAuthProvider != null && (basicAuthProvider instanceof BasicAuthentication)) {
			auth.addAuthentication((BasicAuthentication) basicAuthProvider);
		}

		final Object proxyUri = config.getProperties().get(ClientProperties.PROXY_URI);
		if (proxyUri != null) {
			final URI u = getProxyUri(proxyUri);
			final ProxyConfiguration proxyConfig = client.getProxyConfiguration();
			proxyConfig.getProxies().add(new HttpProxy(u.getHost(), u.getPort()));
		}

		if (disableCookies) {
			client.setCookieStore(new HttpCookieStore.Empty());
		}

		try {
			client.start();
		} catch (final Exception e) {
			throw new ProcessingException("Failed to start the client.", e);
		}
		this.cookieStore = client.getCookieStore();
	}

	@SuppressWarnings("ChainOfInstanceofChecks")
	private static URI getProxyUri(final Object proxy) {
		if (proxy instanceof URI) {
			return (URI) proxy;
		} else if (proxy instanceof String) {
			return URI.create((String) proxy);
		} else {
			throw new ProcessingException(LocalizationMessages.WRONG_PROXY_URI_TYPE(ClientProperties.PROXY_URI));
		}
	}

	/**
	 * Get the {@link HttpClient}.
	 *
	 * @return the {@link HttpClient}.
	 */
	@SuppressWarnings("UnusedDeclaration")
	public HttpClient getHttpClient() {
		return client;
	}

	/**
	 * Get the {@link CookieStore}.
	 *
	 * @return the {@link CookieStore} instance or null when
	 * JettyClientProperties.DISABLE_COOKIES set to true.
	 */
	public CookieStore getCookieStore() {
		return cookieStore;
	}

	@Override
	public ClientResponse apply(final ClientRequest jerseyRequest) throws ProcessingException {
		final Request jettyRequest = translateRequest(jerseyRequest);
		final Map<String, String> clientHeadersSnapshot = writeOutBoundHeaders(jerseyRequest.getHeaders(), jettyRequest);
		final ContentProvider entity = getBytesProvider(jerseyRequest);
		if (entity != null) {
			jettyRequest.content(entity);
		}

		try {
			final ContentResponse jettyResponse = jettyRequest.send();
			HeaderUtils.checkHeaderChanges(clientHeadersSnapshot, jerseyRequest.getHeaders(),
				CustomJettyConnector.this.getClass().getName());

			final javax.ws.rs.core.Response.StatusType status = jettyResponse.getReason() == null
				? Statuses.from(jettyResponse.getStatus())
				: Statuses.from(jettyResponse.getStatus(), jettyResponse.getReason());

			final ClientResponse jerseyResponse = new ClientResponse(status, jerseyRequest);
			processResponseHeaders(jettyResponse.getHeaders(), jerseyResponse);
			try {
				jerseyResponse.setEntityStream(new HttpClientResponseInputStream(jettyResponse));
			} catch (final IOException e) {
				LOGGER.log(Level.SEVERE, null, e);
			}

			return jerseyResponse;
		} catch (final Exception e) {
			throw new ProcessingException(e);
		}
	}

	private static void processResponseHeaders(final HttpFields respHeaders, final ClientResponse jerseyResponse) {
		for (final HttpField header : respHeaders) {
			final String headerName = header.getName();
			final MultivaluedMap<String, String> headers = jerseyResponse.getHeaders();
			List<String> list = headers.get(headerName);
			if (list == null) {
				list = new ArrayList<>();
			}
			list.add(header.getValue());
			headers.put(headerName, list);
		}
	}

	private static final class HttpClientResponseInputStream extends FilterInputStream {

		HttpClientResponseInputStream(final ContentResponse jettyResponse) throws IOException {
			super(getInputStream(jettyResponse));
		}

		private static InputStream getInputStream(final ContentResponse response) {
			return new ByteArrayInputStream(response.getContent());
		}
	}

	private Request translateRequest(final ClientRequest clientRequest) {
		final HttpMethod method = HttpMethod.fromString(clientRequest.getMethod());
		if (method == null) {
			throw new ProcessingException(LocalizationMessages.METHOD_NOT_SUPPORTED(clientRequest.getMethod()));
		}
		final URI uri = clientRequest.getUri();
		final Request request = client.newRequest(uri);
		request.method(method);

		request.followRedirects(clientRequest.resolveProperty(ClientProperties.FOLLOW_REDIRECTS, true));
		final Object readTimeout = clientRequest.getConfiguration().getProperties().get(ClientProperties.READ_TIMEOUT);
		if (readTimeout != null && readTimeout instanceof Integer && (Integer) readTimeout > 0) {
			request.timeout((Integer) readTimeout, TimeUnit.MILLISECONDS);
		}
		return request;
	}

	private static Map<String, String> writeOutBoundHeaders(final MultivaluedMap<String, Object> headers, final Request request) {
		final Map<String, String> stringHeaders = HeaderUtils.asStringHeadersSingleValue(headers);

		for (final Map.Entry<String, String> e : stringHeaders.entrySet()) {
			request.getHeaders().add(e.getKey(), e.getValue());
		}
		return stringHeaders;
	}

	private ContentProvider getBytesProvider(final ClientRequest clientRequest) {
		final Object entity = clientRequest.getEntity();

		if (entity == null) {
			return null;
		}

		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		clientRequest.setStreamProvider(new OutboundMessageContext.StreamProvider() {
			@Override
			public OutputStream getOutputStream(final int contentLength) throws IOException {
				return outputStream;
			}
		});

		try {
			clientRequest.writeEntity();
		} catch (final IOException e) {
			throw new ProcessingException("Failed to write request entity.", e);
		}
		return new BytesContentProvider(outputStream.toByteArray());
	}

	private ContentProvider getStreamProvider(final ClientRequest clientRequest) {
		final Object entity = clientRequest.getEntity();

		if (entity == null) {
			return null;
		}

		final OutputStreamContentProvider streamContentProvider = new OutputStreamContentProvider();
		clientRequest.setStreamProvider(new OutboundMessageContext.StreamProvider() {
			@Override
			public OutputStream getOutputStream(final int contentLength) throws IOException {
				return streamContentProvider.getOutputStream();
			}
		});

		try {
			clientRequest.writeEntity();
		} catch (final IOException e) {
			throw new ProcessingException("Failed to write request entity.", e);
		}
		return streamContentProvider;
	}

	@Override
	public Future<?> apply(final ClientRequest jerseyRequest, final AsyncConnectorCallback callback) {
		final Request jettyRequest = translateRequest(jerseyRequest);
		final Map<String, String> clientHeadersSnapshot = writeOutBoundHeaders(jerseyRequest.getHeaders(), jettyRequest);
		final ContentProvider entity = getStreamProvider(jerseyRequest);
		if (entity != null) {
			jettyRequest.content(entity);
		}
		final AtomicBoolean callbackInvoked = new AtomicBoolean(false);
		final Throwable failure;
		try {
			final SettableFuture<ClientResponse> responseFuture = SettableFuture.create();
			Futures.addCallback(responseFuture, new FutureCallback<ClientResponse>() {
				@Override
				public void onSuccess(final ClientResponse result) {
				}

				@Override
				public void onFailure(final Throwable t) {
					if (t instanceof CancellationException) {
						// take care of future cancellation
						jettyRequest.abort(t);
					}
				}
			});
			final AtomicReference<ClientResponse> jerseyResponse = new AtomicReference<>();
			final ByteBufferInputStream entityStream = new ByteBufferInputStream();
			jettyRequest.send(new Response.Listener.Adapter() {

				@Override
				public void onHeaders(final Response jettyResponse) {
					HeaderUtils.checkHeaderChanges(clientHeadersSnapshot, jerseyRequest.getHeaders(),
						CustomJettyConnector.this.getClass().getName());

					if (responseFuture.isDone()) {
						if (!callbackInvoked.compareAndSet(false, true)) {
							return;
						}
					}
					final ClientResponse response = translateResponse(jerseyRequest, jettyResponse, entityStream);
					jerseyResponse.set(response);
					callback.response(response);
				}

				@Override
				public void onContent(final Response jettyResponse, final ByteBuffer content) {
					try {
						entityStream.put(content);
					} catch (final InterruptedException ex) {
						final ProcessingException pe = new ProcessingException(ex);
						entityStream.closeQueue(pe);
						// try to complete the future with an exception
						responseFuture.setException(pe);
						Thread.currentThread().interrupt();
					}
				}

				@Override
				public void onComplete(final Result result) {
					entityStream.closeQueue();
					// try to complete the future with the response only once truly done
					responseFuture.set(jerseyResponse.get());
				}

				@Override
				public void onFailure(final Response response, final Throwable t) {
					entityStream.closeQueue(t);
					// try to complete the future with an exception
					responseFuture.setException(t);
					if (callbackInvoked.compareAndSet(false, true)) {
						callback.failure(t);
					}
				}
			});
			return responseFuture;
		} catch (final Throwable t) {
			failure = t;
		}

		if (callbackInvoked.compareAndSet(false, true)) {
			callback.failure(failure);
		}
		return Futures.immediateFailedFuture(failure);
	}

	private static ClientResponse translateResponse(final ClientRequest jerseyRequest,
	                                                final org.eclipse.jetty.client.api.Response jettyResponse,
	                                                final NonBlockingInputStream entityStream) {
		final ClientResponse jerseyResponse = new ClientResponse(Statuses.from(jettyResponse.getStatus()), jerseyRequest);
		processResponseHeaders(jettyResponse.getHeaders(), jerseyResponse);
		jerseyResponse.setEntityStream(entityStream);
		return jerseyResponse;
	}

	@Override
	public String getName() {
		return "Jetty HttpClient " + Jetty.VERSION;
	}

	@Override
	public void close() {
		try {
			client.stop();
		} catch (final Exception e) {
			throw new ProcessingException("Failed to stop the client.", e);
		}
	}
}