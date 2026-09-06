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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.boot.loader.testsupport.TestJar;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test for the lock convoy implicated in gh-51463. This is controlled fault
 * injection rather than an upstream regression test: it deliberately holds the private
 * {@code FileDataBlock.FileAccess} lock to prove how an inner file-access stall propagates
 * outward through {@link NestedJarFile}'s monitor.
 *
 * @author Max Copley
 */
class NestedJarFileFileAccessLockConvoyTests {

	@TempDir
	File tempDir;

	@Test
	void stalledFileAccessCausesNestedJarMonitorConvoy() throws Exception {
		File file = new File(this.tempDir, "test.jar");
		TestJar.create(file);
		try (NestedJarFile jarFile = new NestedJarFile(file)) {
			Object fileAccessLock = getFileAccessLock(jarFile);
			CountDownLatch lockHeld = new CountDownLatch(1);
			CountDownLatch releaseLock = new CountDownLatch(1);
			Thread fileLockHolder = new Thread(() -> holdLock(fileAccessLock, lockHeld, releaseLock), "file-access-lock-holder");
			Thread firstJarReader = new Thread(() -> jarFile.hasEntry("not-present-1.dat"), "nested-jar-reader-1");
			Thread secondJarReader = new Thread(() -> jarFile.hasEntry("not-present-2.dat"), "nested-jar-reader-2");
			try {
				fileLockHolder.start();
				assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

				firstJarReader.start();
				awaitBlocked(firstJarReader);
				ThreadInfo first = threadInfo(firstJarReader);
				assertThat(first.getLockOwnerId()).isEqualTo(fileLockHolder.getId());
				assertThat(first.getStackTrace()).anySatisfy((frame) -> assertThat(frame.getClassName())
					.isEqualTo("org.springframework.boot.loader.zip.FileDataBlock$FileAccess"));

				secondJarReader.start();
				awaitBlocked(secondJarReader);
				ThreadInfo second = threadInfo(secondJarReader);
				assertThat(second.getLockOwnerId()).isEqualTo(firstJarReader.getId());
				assertThat(second.getStackTrace()).anySatisfy((frame) -> {
					assertThat(frame.getClassName()).isEqualTo(NestedJarFile.class.getName());
					assertThat(frame.getMethodName()).isIn("hasEntry", "getContentEntry");
				});

				System.out.println("GH-51463 causal lock chain:");
				System.out.printf("  %s waits for FileAccess lock owned by %s%n", firstJarReader.getName(),
						fileLockHolder.getName());
				System.out.printf("  %s waits for NestedJarFile monitor owned by %s%n", secondJarReader.getName(),
						firstJarReader.getName());
				System.out.println("  Therefore an inner FileAccess stall can surface as many callers blocked at NestedJarFile.");
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
		Field field = instance.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(instance);
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
