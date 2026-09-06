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

package org.springframework.boot.loader.jar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test for the lock convoy implicated in gh-51463. This is controlled fault
 * injection rather than an upstream regression test: it deliberately holds the private
 * {@code FileDataBlock.FileAccess} lock to prove how an entry-content read can retain
 * {@link NestedJarFile}'s monitor and block unrelated metadata lookups.
 *
 * @author Max Copley
 */
class NestedJarFileFileAccessLockConvoyTests {

	private static final String PROBE_ENTRY = "META-INF/gh-51463/probe.dat";

	@TempDir
	File tempDir;

	@Test
	void stalledFileAccessDuringEntryReadCausesNestedJarMonitorConvoy() throws Exception {
		File file = new File(this.tempDir, "test.jar");
		createJar(file);
		try (NestedJarFile jarFile = new NestedJarFile(file)) {
			JarEntry probeEntry = jarFile.getJarEntry(PROBE_ENTRY);
			assertThat(probeEntry).isNotNull();
			try (InputStream inputStream = jarFile.getInputStream(probeEntry)) {
				Object fileAccessLock = getFileAccessLock(jarFile);
				CountDownLatch lockHeld = new CountDownLatch(1);
				CountDownLatch releaseLock = new CountDownLatch(1);
				AtomicInteger firstRead = new AtomicInteger(-2);
				AtomicReference<Throwable> firstFailure = new AtomicReference<>();
				AtomicReference<Boolean> secondResult = new AtomicReference<>();
				AtomicReference<Throwable> secondFailure = new AtomicReference<>();
				Thread fileLockHolder = new Thread(() -> holdLock(fileAccessLock, lockHeld, releaseLock),
						"file-access-lock-holder");
				Thread firstJarReader = new Thread(() -> readEntry(inputStream, firstRead, firstFailure),
						"nested-jar-entry-reader");
				Thread secondJarReader = new Thread(
						() -> hasEntry(jarFile, "not-present.dat", secondResult, secondFailure),
						"nested-jar-metadata-reader");
				try {
					fileLockHolder.start();
					assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

					firstJarReader.start();
					awaitBlocked(firstJarReader);
					ThreadInfo first = threadInfo(firstJarReader);
					assertThat(first.getLockOwnerId()).isEqualTo(fileLockHolder.getId());
					assertThat(first.getStackTrace()).anySatisfy((frame) -> assertThat(frame.getClassName())
						.isEqualTo("org.springframework.boot.loader.zip.FileDataBlock$FileAccess"));
					assertThat(first.getStackTrace()).anySatisfy((frame) -> {
						assertThat(frame.getClassName()).isEqualTo(NestedJarFile.class.getName() + "$JarEntryInputStream");
						assertThat(frame.getMethodName()).isEqualTo("read");
					});

					secondJarReader.start();
					awaitBlocked(secondJarReader);
					ThreadInfo second = threadInfo(secondJarReader);
					assertThat(second.getLockOwnerId()).isEqualTo(firstJarReader.getId());
					assertThat(second.getStackTrace()).anySatisfy((frame) -> {
						assertThat(frame.getClassName()).isEqualTo(NestedJarFile.class.getName());
						assertThat(frame.getMethodName()).isEqualTo("hasEntry");
					});

					System.out.println("GH-51463 causal lock chain:");
					System.out.printf("  %s waits for FileAccess lock owned by %s while retaining NestedJarFile%n",
							firstJarReader.getName(), fileLockHolder.getName());
					System.out.printf("  %s waits for NestedJarFile monitor owned by %s%n", secondJarReader.getName(),
							firstJarReader.getName());
					System.out.println("  Therefore a stalled entry-content read can convoy unrelated NestedJarFile lookups.");
				}
				finally {
					releaseLock.countDown();
					fileLockHolder.join(5000);
					firstJarReader.join(5000);
					secondJarReader.join(5000);
				}
				assertThat(fileLockHolder.isAlive()).isFalse();
				assertThat(firstJarReader.isAlive()).isFalse();
				assertThat(secondJarReader.isAlive()).isFalse();
				assertThat(firstFailure.get()).isNull();
				assertThat(secondFailure.get()).isNull();
				assertThat(firstRead.get()).isNotEqualTo(-1);
				assertThat(secondResult.get()).isFalse();
			}
		}
	}

	private void createJar(File file) throws Exception {
		try (JarOutputStream output = new JarOutputStream(new FileOutputStream(file))) {
			output.putNextEntry(new JarEntry(PROBE_ENTRY));
			output.write(new byte[] { 1, 2, 3 });
			output.closeEntry();
		}
	}

	private void readEntry(InputStream inputStream, AtomicInteger result, AtomicReference<Throwable> failure) {
		try {
			result.set(inputStream.read());
		}
		catch (Throwable ex) {
			failure.set(ex);
		}
	}

	private void hasEntry(NestedJarFile jarFile, String name, AtomicReference<Boolean> result,
			AtomicReference<Throwable> failure) {
		try {
			result.set(jarFile.hasEntry(name));
		}
		catch (Throwable ex) {
			failure.set(ex);
		}
	}

	private Object getFileAccessLock(NestedJarFile jarFile) throws Exception {
		Object resources = readField(jarFile, "resources");
		Object zipContent = readField(resources, "zipContent");
		Object data = readField(zipContent, "data");
		Object fileAccess = readField(data, "fileAccess");
		return readField(fileAccess, "lock");
	}

	private Object readField(Object instance, String name) throws Exception {
		Class<?> type = instance.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(instance);
			}
			catch (NoSuchFieldException ex) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}

	private void holdLock(Object lock, CountDownLatch lockHeld, CountDownLatch releaseLock) {
		synchronized (lock) {
			lockHeld.countDown();
			try {
				releaseLock.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void awaitBlocked(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
			Thread.sleep(10);
		}
		assertThat(thread.getState()).isEqualTo(Thread.State.BLOCKED);
	}

	private ThreadInfo threadInfo(Thread thread) {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		ThreadInfo threadInfo = threads.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
		assertThat(threadInfo).isNotNull();
		return threadInfo;
	}

}
