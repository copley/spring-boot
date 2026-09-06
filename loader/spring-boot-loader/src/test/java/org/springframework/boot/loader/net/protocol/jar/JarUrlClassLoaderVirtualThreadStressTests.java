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

package org.springframework.boot.loader.net.protocol.jar;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.loader.net.protocol.Handlers;
import org.springframework.boot.loader.zip.AssertFileChannelDataBlocksClosed;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress reproducer for gh-51463. This test intentionally makes no production-code
 * changes. It exercises concurrent resource lookup and reads from one large nested JAR
 * using virtual threads, matching the loader path reported by affected applications.
 *
 * @author Max Copley
 */
@AssertFileChannelDataBlocksClosed
@EnabledIfEnvironmentVariable(named = "SPRING_BOOT_GH51463_STRESS", matches = "true")
class JarUrlClassLoaderVirtualThreadStressTests {

	private static final int ENTRY_COUNT = 12000;

	private static final int WORKER_COUNT = 256;

	private static final int OPERATIONS_PER_WORKER = 1000;

	private static final Duration NO_PROGRESS_TIMEOUT = Duration.ofSeconds(15);

	private static final Duration OVERALL_TIMEOUT = Duration.ofSeconds(90);

	@TempDir
	File tempDir;

	@BeforeAll
	static void setup() {
		Handlers.register();
	}

	@AfterEach
	void clearCaches() {
		JarUrlConnection.clearCache();
	}

	@Test
	void concurrentNestedJarAccessFromVirtualThreadsContinuesToMakeProgress() throws Exception {
		File jarFile = new File(this.tempDir, "application.jar");
		createLargeNestedJar(jarFile);
		URL nestedJarUrl = JarUrl.create(jarFile, "BOOT-INF/lib/large.jar");
		AtomicLong progress = new AtomicLong();
		ExecutorService executor = newVirtualThreadPerTaskExecutor();
		try (JarUrlClassLoader loader = new TestJarUrlClassLoader(nestedJarUrl)) {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<?>> futures = new ArrayList<>(WORKER_COUNT);
			for (int worker = 0; worker < WORKER_COUNT; worker++) {
				int workerIndex = worker;
				futures.add(executor.submit(() -> {
					exerciseLoader(loader, start, progress, workerIndex);
					return null;
				}));
			}
			start.countDown();
			awaitCompletion(futures, progress);
			for (Future<?> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
			assertThat(progress.get()).isEqualTo((long) WORKER_COUNT * OPERATIONS_PER_WORKER);
		}
		finally {
			executor.shutdownNow();
			executor.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	private void exerciseLoader(JarUrlClassLoader loader, CountDownLatch start, AtomicLong progress, int worker)
			throws Exception {
		start.await();
		for (int operation = 0; operation < OPERATIONS_PER_WORKER; operation++) {
			int entryIndex = Math.floorMod(worker * 8191 + operation * 131, ENTRY_COUNT);
			String entryName = "entries/entry-%05d.dat".formatted(entryIndex);
			switch (operation & 3) {
				case 0 -> assertThat(loader.getResource(entryName)).isNotNull();
				case 1 -> assertThat(loader.getResource("missing/entry-%05d.dat".formatted(entryIndex))).isNull();
				case 2 -> readResource(loader, entryName, entryIndex);
				case 3 -> enumerateResource(loader, entryName);
				default -> throw new IllegalStateException("Unexpected operation");
			}
			progress.incrementAndGet();
		}
	}

	private void readResource(JarUrlClassLoader loader, String entryName, int entryIndex) throws Exception {
		URL resource = loader.getResource(entryName);
		assertThat(resource).isNotNull();
		try (InputStream inputStream = resource.openStream()) {
			assertThat(inputStream.read()).isEqualTo(entryIndex & 0xff);
		}
	}

	private void enumerateResource(JarUrlClassLoader loader, String entryName) throws Exception {
		Enumeration<URL> resources = loader.getResources(entryName);
		assertThat(resources.hasMoreElements()).isTrue();
		assertThat(resources.nextElement()).isNotNull();
	}

	private void awaitCompletion(List<Future<?>> futures, AtomicLong progress) throws Exception {
		long started = System.nanoTime();
		long lastProgressAt = started;
		long lastProgress = -1;
		while (!allDone(futures)) {
			long currentProgress = progress.get();
			long now = System.nanoTime();
			if (currentProgress != lastProgress) {
				lastProgress = currentProgress;
				lastProgressAt = now;
			}
			if (Duration.ofNanos(now - lastProgressAt).compareTo(NO_PROGRESS_TIMEOUT) > 0) {
				throw new AssertionError("Nested JAR access made no progress for " + NO_PROGRESS_TIMEOUT + "; completed "
						+ currentProgress + " operations");
			}
			if (Duration.ofNanos(now - started).compareTo(OVERALL_TIMEOUT) > 0) {
				throw new AssertionError("Nested JAR stress test exceeded " + OVERALL_TIMEOUT + "; completed "
						+ currentProgress + " operations");
			}
			Thread.sleep(25);
		}
	}

	private boolean allDone(List<Future<?>> futures) {
		for (Future<?> future : futures) {
			if (!future.isDone()) {
				return false;
			}
		}
		return true;
	}

	private ExecutorService newVirtualThreadPerTaskExecutor() throws Exception {
		try {
			Method method = Executors.class.getMethod("newVirtualThreadPerTaskExecutor");
			return (ExecutorService) method.invoke(null);
		}
		catch (NoSuchMethodException ex) {
			throw new IllegalStateException("This stress reproducer requires Java 21 or later", ex);
		}
		catch (InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof Exception exception) {
				throw exception;
			}
			throw new IllegalStateException(cause);
		}
	}

	private void createLargeNestedJar(File jarFile) throws Exception {
		byte[] nestedJar = createNestedJar();
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarFile))) {
			JarEntry nestedEntry = new JarEntry("BOOT-INF/lib/large.jar");
			nestedEntry.setMethod(ZipEntry.STORED);
			nestedEntry.setSize(nestedJar.length);
			nestedEntry.setCompressedSize(nestedJar.length);
			CRC32 crc = new CRC32();
			crc.update(nestedJar);
			nestedEntry.setCrc(crc.getValue());
			out.putNextEntry(nestedEntry);
			out.write(nestedJar);
			out.closeEntry();
		}
	}

	private byte[] createNestedJar() throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream out = new JarOutputStream(bytes)) {
			for (int i = 0; i < ENTRY_COUNT; i++) {
				out.putNextEntry(new JarEntry("entries/entry-%05d.dat".formatted(i)));
				out.write(i & 0xff);
				out.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static class TestJarUrlClassLoader extends JarUrlClassLoader {

		TestJarUrlClassLoader(URL... urls) {
			super(urls, JarUrlClassLoaderVirtualThreadStressTests.class.getClassLoader());
		}

	}

}
