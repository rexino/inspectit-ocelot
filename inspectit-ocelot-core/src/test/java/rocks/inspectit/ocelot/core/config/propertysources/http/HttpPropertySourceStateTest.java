package rocks.inspectit.ocelot.core.config.propertysources.http;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import org.springframework.core.env.PropertySource;
import rocks.inspectit.ocelot.config.model.config.HttpConfigSettings;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class HttpPropertySourceStateTest {

    private HttpPropertySourceState state;

    @Nested
    public class Update {

        private WireMockServer mockServer;

        @Mock
        private Appender<ILoggingEvent> mockAppender;

        private HttpConfigSettings httpSettings;

        @BeforeEach
        public void setup() throws MalformedURLException {
            mockServer = new WireMockServer(options().dynamicPort());
            mockServer.start();

            httpSettings = new HttpConfigSettings();
            httpSettings.setUrl(new URL("http://localhost:" + mockServer.port() + "/"));
            httpSettings.setAttributes(new HashMap<>());
            httpSettings.setPersistenceFile(generateTempFilePath());
            state = new HttpPropertySourceState("test-state", httpSettings);
        }


        private String generateTempFilePath() {
            try {
                Path tempFile = Files.createTempFile("inspectit", "");
                Files.delete(tempFile);
                return tempFile.toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @AfterEach
        public void teardown() {
            mockServer.stop();
        }

        @Test
        public void fetchingYaml() {
            String config = "inspectit:\n  service-name: test-name";

            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(config)));

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertTrue(updateResult);
            assertTrue(state.getErrorCounter() == 0);
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent(config);
            assertThat(result.getProperty("inspectit.service-name")).isEqualTo("test-name");
        }

        @Test
        public void fetchingJson() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"inspectit\": {\"service-name\": \"test-name\"}}")));

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertTrue(updateResult);
            assertTrue(state.getErrorCounter() == 0);
            assertThat(result.getProperty("inspectit.service-name")).isEqualTo("test-name");
        }

        @Test
        public void fetchingEmptyResponse() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();
            Properties source = (Properties) result.getSource();


            assertTrue(updateResult);
            assertTrue(state.getErrorCounter() == 0);
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent("");
            assertTrue(source.isEmpty());
        }

        @Test
        public void multipleFetchingWithoutCaching() {
            String config = "{\"inspectit\": {\"service-name\": \"test-name\"}}";
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(config)));

            boolean updateResultFirst = state.update(false);
            PropertySource resultFirst = state.getCurrentPropertySource();

            boolean updateResultSecond = state.update(false);
            PropertySource resultSecond = state.getCurrentPropertySource();

            assertTrue(updateResultFirst);
            assertTrue(updateResultSecond);
            assertTrue(state.getErrorCounter() == 0);
            assertNotSame(resultFirst, resultSecond);
            assertThat(resultFirst.getProperty("inspectit.service-name")).isEqualTo("test-name");
            assertThat(resultSecond.getProperty("inspectit.service-name")).isEqualTo("test-name");
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent(config);
        }

        @Test
        public void testLogFetchError() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));
            Logger logger = (Logger) LoggerFactory.getLogger(HttpPropertySourceState.class.getName());
            // create and start a ListAppender
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();

            logger.addAppender(listAppender);

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertFalse(state.getErrorCounter() == 0);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).doesNotExist();

            boolean check = false;
            // JUnit assertions
            List<ILoggingEvent> logsList = listAppender.list;
            for (ILoggingEvent log : logsList){
                if(log.getLevel().equals(Level.ERROR)) {
                    assertTrue(log.getMessage().contains("A IO problem occurred while fetching configuration."));
                    check = true;
                }
            }
            assertTrue(check);
        }

        @Test
        public void testNumberOfLogs() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));
            Logger logger = (Logger) LoggerFactory.getLogger(HttpPropertySourceState.class.getName());
            // create and start a ListAppender
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();

            logger.addAppender(listAppender);

            boolean updateResult = state.update(false);
            updateResult = state.update(false);
            updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertTrue(state.getErrorCounter() == 3);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).doesNotExist();

            int check = 0;
            // JUnit assertions
            List<ILoggingEvent> logsList = listAppender.list;
            for (ILoggingEvent log : logsList){
                if(log.getLevel().equals(Level.ERROR)) {
                    assertTrue(log.getMessage().contains("A IO problem occurred while fetching configuration."));
                    check++;
                }
            }
            assertEquals(2, check);
        }

        @Test
        public void testLogSuccesFetchAfterError() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));
            Logger logger = (Logger) LoggerFactory.getLogger(HttpPropertySourceState.class.getName());
            // create and start a ListAppender
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();

            logger.addAppender(listAppender);

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertTrue(state.getErrorCounter() == 1);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).doesNotExist();

            boolean check = false;
            // JUnit assertions
            List<ILoggingEvent> logsList = listAppender.list;
            for (ILoggingEvent log : logsList){
                if(log.getLevel().equals(Level.ERROR)) {
                    assertTrue(log.getMessage().contains("A IO problem occurred while fetching configuration."));
                    check = true;
                }
            }
            assertTrue(check);

            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            updateResult = state.update(false);
            result = state.getCurrentPropertySource();
            Properties source = (Properties) result.getSource();
            assertTrue(updateResult);
            assertTrue(state.getErrorCounter() == 0);
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent("");
            assertTrue(source.isEmpty());

            for (ILoggingEvent log : logsList){
                if(log.getLevel().equals(Level.INFO)) {
                    if(log.getMessage().contains("Configuration fetch has been successful after 1 unsuccessful attempts."))
                        check = true;
                }
            }
            assertTrue(check);
        }

        @Test
        public void testLogFetchSuccess() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("")));

            Logger logger = (Logger) LoggerFactory.getLogger(HttpPropertySourceState.class.getName());
            // create and start a ListAppender
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();

            logger.addAppender(listAppender);

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();
            Properties source = (Properties) result.getSource();

            assertTrue(updateResult);
            assertTrue(state.getErrorCounter() == 0);
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent("");
            assertTrue(source.isEmpty());

            boolean check = true;
            // JUnit assertions
            List<ILoggingEvent> logsList = listAppender.list;
            for (ILoggingEvent log : logsList){
                if(log.getLevel().equals(Level.ERROR)) {
                    assertTrue(log.getMessage().contains("A IO problem occurred while fetching configuration."));
                    check = false;
                }
            }
            assertTrue(check);
        }

        @Test
        public void testLogFetchCounter() {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));
            Logger logger = (Logger) LoggerFactory.getLogger(HttpPropertySourceState.class.getName());
            // create and start a ListAppender
            ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
            listAppender.start();

            logger.addAppender(listAppender);

            boolean updateResult = state.update(false);
            updateResult = state.update(false);
            updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertTrue(state.getErrorCounter() == 3);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).doesNotExist();
        }

        @Test
        public void usingLastModifiedHeader() {
            String config = "{\"inspectit\": {\"service-name\": \"test-name\"}}";
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(config)
                            .withHeader("Last-Modified", "last_modified_header")));
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .withHeader("If-Modified-Since", equalTo("last_modified_header"))
                    .willReturn(aResponse()
                            .withStatus(304)));

            boolean updateResultFirst = state.update(false);
            PropertySource resultFirst = state.getCurrentPropertySource();

            boolean updateResultSecond = state.update(false);
            PropertySource resultSecond = state.getCurrentPropertySource();

            assertTrue(updateResultFirst);
            assertFalse(updateResultSecond);
            assertTrue(state.getErrorCounter() == 0);
            assertSame(resultFirst, resultSecond);
            assertThat(resultFirst.getProperty("inspectit.service-name")).isEqualTo("test-name");
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent(config);
        }

        @Test
        public void usingETagHeader() {
            String config = "{\"inspectit\": {\"service-name\": \"test-name\"}}";
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody(config)
                            .withHeader("ETag", "etag_header")));
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .withHeader("If-None-Match", matching("etag_header.*")) // regex required because this header can be different - e.g. Jetty adds "--gzip" to the ETag header value
                    .willReturn(aResponse()
                            .withStatus(304)));

            boolean updateResultFirst = state.update(false);
            PropertySource resultFirst = state.getCurrentPropertySource();

            boolean updateResultSecond = state.update(false);
            PropertySource resultSecond = state.getCurrentPropertySource();

            assertTrue(updateResultFirst);
            assertFalse(updateResultSecond);
            assertTrue(state.getErrorCounter() == 0);
            assertSame(resultFirst, resultSecond);
            assertThat(resultFirst.getProperty("inspectit.service-name")).isEqualTo("test-name");
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent(config);
        }

        @Test
        public void serverReturnsErrorNoFallback() throws IOException {
            Files.write(Paths.get(httpSettings.getPersistenceFile()), "test: testvalue".getBytes());

            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertFalse(state.getErrorCounter() == 0);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent("test: testvalue");
        }

        @Test
        public void serverReturnsErrorWithFallback() throws IOException {
            Files.write(Paths.get(httpSettings.getPersistenceFile()), "test: testvalue".getBytes());

            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));

            boolean updateResult = state.update(true);
            PropertySource result = state.getCurrentPropertySource();

            assertTrue(updateResult);
            assertFalse(state.getErrorCounter() == 0);
            assertThat(result.getProperty("test")).isEqualTo("testvalue");
            assertThat(new File(httpSettings.getPersistenceFile())).hasContent("test: testvalue");
        }

        @Test
        public void serverReturnsErrorWithoutFallbackFile() throws IOException {
            mockServer.stubFor(get(urlPathEqualTo("/"))
                    .willReturn(aResponse()
                            .withStatus(500)));

            boolean updateResult = state.update(false);
            PropertySource result = state.getCurrentPropertySource();

            assertFalse(updateResult);
            assertFalse(state.getErrorCounter() == 0);
            assertThat(((Properties) result.getSource())).isEmpty();
            assertThat(new File(httpSettings.getPersistenceFile())).doesNotExist();
        }
    }


    @Nested
    public class GetEffectiveRequestUri {

        @Test
        void emptyParametersIgnored() throws Exception {
            HttpConfigSettings httpSettings = new HttpConfigSettings();
            httpSettings.setUrl(new URL("http://localhost:4242/endpoint"));

            HashMap<String, String> attributes = new HashMap<>();
            attributes.put("a", null);
            attributes.put("b", "valb");
            attributes.put("c", "");
            httpSettings.setAttributes(attributes);

            state = new HttpPropertySourceState("test-state", httpSettings);

            assertThat(state.getEffectiveRequestUri().toString()).isEqualTo("http://localhost:4242/endpoint?b=valb");
        }

        @Test
        void existingParametersPreserved() throws Exception {
            HttpConfigSettings httpSettings = new HttpConfigSettings();
            httpSettings.setUrl(new URL("http://localhost:4242/endpoint?fixed=something"));
            httpSettings.setAttributes(ImmutableMap.of("service", "myservice"));

            state = new HttpPropertySourceState("test-state", httpSettings);

            assertThat(state.getEffectiveRequestUri().toString()).isEqualTo("http://localhost:4242/endpoint?fixed=something&service=myservice");
        }
    }
}