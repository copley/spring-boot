/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.stream.XMLOutputFactory;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executable application used to reproduce gh-51463 under startup traffic.
 *
 * <p>The workload deliberately mirrors the reported production shape: a large executable
 * archive launched with {@code PropertiesLauncher}, virtual-thread request handling,
 * startup-time resource fingerprinting, and concurrent actuator health/prometheus calls.
 * It does not synchronize on Spring Boot's jar objects directly.
 *
 * @author Max Copley
 */
@RestController
@SpringBootApplication
public class Gh51463ReproducerApplication {

	private final Object fingerprintMonitor = new Object();

	private final AtomicLong fingerprintPasses = new AtomicLong();

	private final AtomicLong healthPasses = new AtomicLong();

	@GetMapping("/probe")
	Resource probe() {
		return new ClassPathResource("gh-51463/probe.txt");
	}

	@GetMapping("/progress")
	String progress() {
		return "fingerprint=" + this.fingerprintPasses.get() + ",health=" + this.healthPasses.get();
	}

	@Bean
	HealthIndicator loaderHealthIndicator() {
		return () -> {
			try {
				// Match the production health-check path that enters ServiceLoader and
				// classpath resource discovery while the application is still starting.
				XMLOutputFactory.newFactory();
				readAll("META-INF/gh-51463/common.dat");
				this.healthPasses.incrementAndGet();
				return Health.up().build();
			}
			catch (Exception ex) {
				return Health.down(ex).build();
			}
		};
	}

	@Bean
	ApplicationRunner startupFingerprinting() {
		return (args) -> {
			long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
			while (System.nanoTime() < deadline) {
				fingerprintClasspath();
				this.fingerprintPasses.incrementAndGet();
			}
		};
	}

	private void fingerprintClasspath() throws Exception {
		synchronized (this.fingerprintMonitor) {
			readAll("META-INF/gh-51463/common.dat");
		}
	}

	private void readAll(String name) throws Exception {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		Enumeration<URL> resources = classLoader.getResources(name);
		byte[] buffer = new byte[8192];
		while (resources.hasMoreElements()) {
			URL resource = resources.nextElement();
			try (InputStream input = resource.openStream()) {
				while (input.read(buffer) != -1) {
					// Drain the resource to exercise NestedJarFile/FileDataBlock reads.
				}
			}
		}
	}

	public static void main(String[] args) {
		SpringApplication.run(Gh51463ReproducerApplication.class, args);
	}

}
