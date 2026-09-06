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

import java.time.Duration;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Executable application used to reproduce gh-51463 under startup traffic.
 *
 * @author Max Copley
 */
@RestController
@SpringBootApplication
public class Gh51463ReproducerApplication {

	@GetMapping("/probe")
	Resource probe() {
		return new ClassPathResource("gh-51463/probe.txt");
	}

	@GetMapping("/scan/{id}")
	String scan(@PathVariable long id) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		for (int i = 0; i < 8; i++) {
			classLoader.getResource("META-INF/gh-51463/optional-" + id + "-" + i + ".class");
		}
		return "ok";
	}

	@Bean
	ApplicationRunner startupWindow() {
		return (args) -> Thread.sleep(Duration.ofSeconds(8));
	}

	public static void main(String[] args) {
		SpringApplication.run(Gh51463ReproducerApplication.class, args);
	}

}
