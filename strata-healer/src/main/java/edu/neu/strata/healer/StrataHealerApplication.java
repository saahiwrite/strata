package edu.neu.strata.healer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StrataHealerApplication {

    public static void main(String[] args) {
        SpringApplication.run(StrataHealerApplication.class, args);
    }
}
