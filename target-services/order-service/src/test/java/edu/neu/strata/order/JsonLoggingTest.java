package edu.neu.strata.order;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.ConsoleAppender;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JsonLoggingTest {

    @Test
    void rootLoggerWritesJsonToStdout() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        ConsoleAppender<?> appender = (ConsoleAppender<?>) root.getAppender("JSON");

        assertThat(appender).isNotNull();
        assertThat(appender.getEncoder()).isInstanceOf(LogstashEncoder.class);
        assertThat(((LogstashEncoder) appender.getEncoder()).getCustomFields())
                .contains("\"service\":\"order-service\"");
    }
}
