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
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression contract for gh-51463. A stalled low-level file read must not prevent an
 * unrelated metadata-only operation from using the same {@link NestedJarFile}.
 *
 * @author Max Copley
 */
class NestedJarFileFileAccessLockConvoyRegressionTests {

	private static final String PROBE_ENTRY = "META-INF/gh-51463/probe.dat";

	@TempDir
	File tempDir;

	@Test
	void stalledFileAccessDoesNotBlockIndependentNestedJarMetadataRead() throws Exception {
		File file = new File(this.tempDir, "test.jar");
		createJar(file);
		try (NestedJarFile jarFile = new NestedJarFile(file)) {
			Object fileAccessLock = getFileAccessLock(jarFile);
			CountDownLatch lockHeld = new CountDownLatch(1);
			CountDownLatch releaseLock = new CountDownLatch(1);
			CountDownLatch metadataFinished = new CountDownLatch(1);
			AtomicInteger metadataSize = new AtomicInteger(-1);
			Thread fileLockHolder = new Thread(() -> holdLock(fileAccessLock, lockHeld, releaseLock),
					"file-access-lock-holder");
			Thread nestedJarReader = new Thread(() -> jarFile.hasEntry(PROBE_ENTRY), "nested-jar-reader");
			Thread metadataReader = new Thread(() -> {
				metadataSize.set(jarFile.size());
				metadataFinished.countDown();
			}, "independent-metadata-reader");
			try {
				fileLockHolder.start();
				assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();

				nestedJarReader.start();
				awaitBlocked(nestedJarReader);
				ThreadInfo readerInfo = threadInfo(nestedJarReader);
				assertThat(readerInfo.getLockOwnerId()).isEqualTo(fileLockHolder.getId());
				assertThat(readerInfo.getStackTrace()).anySatisfy((frame) -> assertThat(frame.getClassName())
					.isEqualTo("org.springframework.boot.loader.zip.FileDataBlock$FileAccess"));

				metadataReader.start();
				assertThat(metadataFinished.await(2, TimeUnit.SECONDS))
					.as("GH-51463 regression: independent NestedJarFile metadata reads must not wait behind a "
							+ "stalled FileAccess read")
					.isTrue();
				assertThat(metadataSize.get()).isEqualTo(1);
			}
			finally {
				releaseLock.countDown();
				fileLockHolder.join(5000);
				nestedJarReader.join(5000);
				metadataReader.join(5000);
			}
			assertThat(fileLockHolder.isAlive()).isFalse();
			assertThat(nestedJarReader.isAlive()).isFalse();
			assertThat(metadataReader.isAlive()).isFalse();
		}
	}

	private void createJar(File file) throws Exception {
		try (JarOutputStream output = new JarOutputStream(new FileOutputStream(file))) {
			output.putNextEntry(new JarEntry(PROBE_ENTRY));
			output.write(new byte[] { 1, 2, 3 });
			output.closeEntry();
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
