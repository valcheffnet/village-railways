package net.valcheff.villagerailways;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckTest {
	@Test
	void jarVersionBelongsToTheRelease() {
		assertEquals("1.0.0", UpdateCheck.release("1.0.0+26.3"));
		assertEquals("1.0.0", UpdateCheck.release("1.0.0+1.21.11"));
		assertEquals("1.2.0-beta", UpdateCheck.release("1.2.0-beta"));
	}

	@Test
	void versionsCompareByNumberThenSuffix() {
		assertTrue(UpdateCheck.compare("1.0.1", "1.0.0") > 0);
		assertTrue(UpdateCheck.compare("1.10.0", "1.9.0") > 0);
		assertTrue(UpdateCheck.compare("1.0.0", "1.0.0-beta") > 0);
		assertEquals(0, UpdateCheck.compare("1.0.0", "1.0"));
	}

	@Test
	void latestSkipsDraftsAndTestBuilds() {
		String json = "[{\"tag_name\":\"v1.2.0\",\"draft\":true},{\"tag_name\":\"v1.1.0-test\"},"
				+ "{\"tag_name\":\"v1.0.0\"},{\"tag_name\":\"v1.1.0\"}]";
		assertEquals("1.1.0", UpdateCheck.latest(json));
	}
}
