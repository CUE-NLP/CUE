package com.cue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CueApplication {
    public static void main(String[] args) {
        SpringApplication.run(CueApplication.class, args);
    }
}
