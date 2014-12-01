package org.mockserver.integration.proxy;

import com.google.common.base.Charsets;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.mockserver.client.proxy.ProxyClient;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.socket.SSLFactory;
import org.mockserver.streams.IOStreamUtils;

import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.Socket;

import static org.hamcrest.core.StringStartsWith.startsWith;
import static org.junit.Assert.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.test.Assert.assertContains;
import static org.mockserver.verify.VerificationTimes.atLeast;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * @author jamesdbloom
 */
public abstract class AbstractClientProxyIntegrationTest {

    protected static String servletContext = "";

    protected HttpClient createHttpClient() throws Exception {
        HttpClientBuilder httpClientBuilder = HttpClients
                .custom()
                .setSslcontext(SSLFactory.getInstance().sslContext())
                .setHostnameVerifier(new AllowAllHostnameVerifier());
        if (Boolean.parseBoolean(System.getProperty("defaultProxySet"))) {
            httpClientBuilder.setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault())).build();
        } else if (Boolean.parseBoolean(System.getProperty("proxySet"))) {
            HttpHost httpHost = new HttpHost(System.getProperty("http.proxyHost"), Integer.parseInt(System.getProperty("http.proxyPort")));
            DefaultProxyRoutePlanner defaultProxyRoutePlanner = new DefaultProxyRoutePlanner(httpHost);
            httpClientBuilder.setRoutePlanner(defaultProxyRoutePlanner).build();
        } else {
            HttpHost httpHost = new HttpHost("localhost", getProxyPort());
            DefaultProxyRoutePlanner defaultProxyRoutePlanner = new DefaultProxyRoutePlanner(httpHost);
            httpClientBuilder.setRoutePlanner(defaultProxyRoutePlanner);
        }
        return httpClientBuilder.build();
    }

    public abstract int getProxyPort();

    public abstract int getServerPort();

    protected String calculatePath(String path) {
        return "/" + path;
    }

    @Test
    public void shouldForwardRequestsUsingSocketDirectly() throws Exception {
        Socket socket = null;
        try {
            socket = new Socket("localhost", getProxyPort());

            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request for headers only
            output.write(("" +
                    "GET /test_headers_only HTTP/1.1\r\n" +
                    "Host: localhost:" + getServerPort() + "\r\n" +
                    "x-test: test_headers_only\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n"
            ).getBytes(Charsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readInputStreamToString(socket), "x-test: test_headers_only");

            // - send GET request for headers and body
            output.write(("" +
                    "GET /test_headers_and_body HTTP/1.1\r\n" +
                    "Host: localhost:" + getServerPort() + "\r\n" +
                    "Content-Length: " + "an_example_body".getBytes(Charsets.UTF_8).length + "\r\n" +
                    "x-test: test_headers_and_body\r\n" +
                    "\r\n" +
                    "an_example_body"
            ).getBytes(Charsets.UTF_8));
            output.flush();

            // then
            String response = IOStreamUtils.readInputStreamToString(socket);
            assertContains(response, "x-test: test_headers_and_body");
            assertContains(response, "an_example_body");
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Test
    public void shouldForwardRequestsUsingHttpClient() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();

        // when
        HttpPost request = new HttpPost(
                new URIBuilder()
                        .setScheme("http")
                        .setHost("localhost")
                        .setPort(getServerPort())
                        .setPath("/test_headers_and_body")
                        .build()
        );
        request.setEntity(new StringEntity("an_example_body"));
        HttpResponse response = httpClient.execute(request);

        // then
        assertEquals(HttpStatusCode.OK_200.code(), response.getStatusLine().getStatusCode());
        assertEquals("an_example_body", new String(EntityUtils.toByteArray(response.getEntity()), com.google.common.base.Charsets.UTF_8));
    }

    @Test
    public void shouldForwardRequestsToUnknownPath() throws Exception {
        Socket socket = null;
        try {
            socket = new Socket("localhost", getProxyPort());
            // given
            OutputStream output = socket.getOutputStream();

            // when
            // - send GET request
            output.write(("" +
                    "GET /not_found HTTP/1.1\r\n" +
                    "Host: localhost:" + getServerPort() + "\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
            ).getBytes(Charsets.UTF_8));
            output.flush();

            // then
            assertContains(IOStreamUtils.readInputStreamToString(socket), "HTTP/1.1 404 Not Found");
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Test
    public void shouldVerifyRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_only")
                                .build()
                )
        );

        // then
        proxyClient
                .verify(
                        request()
                                .withMethod("GET")
                                .withPath(calculatePath("test_headers_and_body"))
                );
        proxyClient
                .verify(
                        request()
                                .withMethod("GET")
                                .withPath(calculatePath("test_headers_and_body")),
                        exactly(1)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("test_headers_.*")),
                        atLeast(1)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("test_headers_.*")),
                        exactly(2)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("other_path")),
                        exactly(0)
                );
    }

    @Test
    public void shouldVerifyZeroRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );

        // then
        try {
            proxyClient.verify(request()
                    .withPath(calculatePath("test_headers_and_body")), exactly(0));
            fail();
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found exactly 0 times, expected:<{" + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"" + System.getProperty("line.separator") +
                    "}> but was:<{" + System.getProperty("line.separator") +
                    "  \"method\" : \"GET\"," + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"," + System.getProperty("line.separator")));
        }
    }

    @Test
    public void shouldVerifyNoRequestsExactly() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );

        // then
        try {
            proxyClient
                    .verify(
                            request()
                                    .withPath(calculatePath("other_path")),
                            exactly(1)
                    );
            fail();
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found exactly once, expected:<{" + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("other_path") + "\"" + System.getProperty("line.separator") +
                    "}> but was:<{" + System.getProperty("line.separator") +
                    "  \"method\" : \"GET\"," + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"," + System.getProperty("line.separator")));
        }
    }

    @Test
    public void shouldVerifyNoRequestsTimesNotSpecified() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );

        // then
        try {
            proxyClient
                    .verify(
                            request()
                                    .withPath(calculatePath("other_path"))
                    );
            fail();
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request sequence not found, expected:<[ {" + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("other_path") + "\"" + System.getProperty("line.separator") +
                    "} ]> but was:<[ {" + System.getProperty("line.separator") +
                    "  \"method\" : \"GET\"," + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"," + System.getProperty("line.separator")));
        }
    }

    @Test
    public void shouldVerifyNotEnoughRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );

        // then
        try {
            proxyClient
                    .verify(
                            request()
                                    .withPath(calculatePath("test_headers_and_body")),
                            atLeast(3)
                    );
            fail();
        } catch (AssertionError ae) {
            assertThat(ae.getMessage(), startsWith("Request not found at least 3 times, expected:<{" + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"" + System.getProperty("line.separator") +
                    "}> but was:<[ {" + System.getProperty("line.separator") +
                    "  \"method\" : \"GET\"," + System.getProperty("line.separator") +
                    "  \"path\" : \"" + calculatePath("test_headers_and_body") + "\"," + System.getProperty("line.separator")));
        }
    }

    @Test
    public void shouldClearRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_only")
                                .build()
                )
        );
        proxyClient.clear(
                request()
                        .withMethod("GET")
                        .withPath(calculatePath("test_headers_and_body"))
        );

        // then
        proxyClient
                .verify(
                        request()
                                .withMethod("GET")
                                .withPath(calculatePath("test_headers_and_body")),
                        exactly(0)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("test_headers_.*")),
                        exactly(1)
                );
    }

    @Test
    public void shouldResetRequests() throws Exception {
        // given
        HttpClient httpClient = createHttpClient();
        ProxyClient proxyClient = new ProxyClient("127.0.0.1", getProxyPort()).reset();

        // when
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_and_body")
                                .build()
                )
        );
        httpClient.execute(
                new HttpGet(
                        new URIBuilder()
                                .setScheme("http")
                                .setHost("localhost")
                                .setPort(getServerPort())
                                .setPath("/test_headers_only")
                                .build()
                )
        );
        proxyClient.reset();

        // then
        proxyClient
                .verify(
                        request()
                                .withMethod("GET")
                                .withPath(calculatePath("test_headers_and_body")),
                        exactly(0)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("test_headers_.*")),
                        atLeast(0)
                );
        proxyClient
                .verify(
                        request()
                                .withPath(calculatePath("test_headers_.*")),
                        exactly(0)
                );
    }
}
