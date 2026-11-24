package edu.neu.strata.chaostest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimal service used by the chaos-kit tests. It lives outside {@code edu.neu.strata.chaos} so
 * component scanning does not pick up the chaos classes; they must come from the auto-configuration.
 *
 * It has one business endpoint the switches can hit.
 */
@SpringBootApplication
public class TestServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestServiceApplication.class, args);
    }

    @RestController
    static class ItemsController {

        @GetMapping("/items")
        List<String> items() {
            return List.of("a", "b");
        }
    }
}
