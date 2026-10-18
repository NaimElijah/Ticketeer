package com.ticketing.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EventTicketSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(EventTicketSystemApplication.class, args);
	}

}
