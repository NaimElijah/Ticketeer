package com.ticketing.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point of the event ticketing platform, a Spring Boot application that scans
 * {@code com.ticketing.system} and everything below it.
 *
 * <p>{@code @ConfigurationPropertiesScan} binds the {@code @ConfigurationProperties} records of every
 * module, such as those in {@code bootstrap.config}, so a missing or invalid setting stops the boot.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EventTicketSystemApplication {

	/**
	 * Starts the application.
	 *
	 * @param args Spring Boot command-line arguments, such as {@code --spring.profiles.active=dev} or
	 *             {@code --seed.mode=reseed}
	 */
	public static void main(String[] args) {
		SpringApplication.run(EventTicketSystemApplication.class, args);
	}

}
