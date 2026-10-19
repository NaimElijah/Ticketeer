package com.ticketing.system;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.persistence.PersistenceProfiles;

@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class EventTicketSystemApplicationTests {

	@Test
	void contextLoads() {
	}

}
