package me.nageshk.whatsapp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WabridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(WabridgeApplication.class, args);
    }
}
