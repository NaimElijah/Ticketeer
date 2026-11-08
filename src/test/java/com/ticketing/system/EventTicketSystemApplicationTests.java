package com.ticketing.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.IRepository;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * Boots the whole application under the profiles the acceptance suite uses. Every acceptance class boots
 * the same context, so starting it proves little alone. What this adds is the adapter choice those classes
 * rely on, made by Boot's own profile processing, which {@code RepositoryAdapterProfileTest}'s scanner skips.
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class EventTicketSystemApplicationTests {

	@Autowired
	private ApplicationContext context;

	@Test
	void GivenTheTestAndMemoryProfiles_WhenTheApplicationContextStarts_ThenEveryRepositoryIsAnInMemoryAdapter() {
		assertThat(context.getBeansOfType(IRepository.class).values())
				.isNotEmpty()
				.allSatisfy(repository -> assertThat(AopUtils.getTargetClass(repository).getSimpleName())
						.as("the adapter behind a repository port").startsWith("Memory"));
	}
}
