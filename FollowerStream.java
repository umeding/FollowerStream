/*
 * This program is free software. It comes without any warranty, to
 * the extent permitted by applicable law. You can redistribute it
 * and/or modify as you wish.
 * Copyright (c) 2012 Uwe B. Meding <uwe@uwemeding.com>
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import static java.nio.file.StandardWatchEventKinds.*;
import java.nio.file.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Follow an input stream - similar to "tail -f"
 *
 * @author uwe
 */
public class FileFollowerStream extends InputStream {

	private final File file;
	private final BlockingQueue<String> queue;
	private final FileWatcher watcher;
	private byte[] buffer;
	private int pos;
	private int buflen;

	public FileFollowerStream(File file) throws IOException {
		if (file.isDirectory()) {
			throw new IOException(file + ": must be a file");
		}

		this.file = file;
		this.queue = new LinkedBlockingQueue<>();
		this.watcher = new FileWatcher(file.toPath());
		this.pos = -1;
	}

	/**
	 * Get the file content that has changed. Returns null if we are done.
	 *
	 * @return a string
	 * @throws IOException
	 */
	public String getContent() throws IOException {
		try {

			if (!watcher.running) {
				Thread t = new Thread(watcher, file + " watcher");
				t.setDaemon(true);
				t.start();

				synchronized (watcher) {
					while (!watcher.running) {
						try {
							watcher.wait();
						} catch (InterruptedException e) {
							throw new IOException(e.getLocalizedMessage());
						}
					}
				}
			}

			String string = queue.take();
			return string.length() == 0 ? null : string;
		} catch (InterruptedException ex) {
			return null;
		}
	}

	/**
	 * Read a byte from a buffer
	 *
	 * @return a byte
	 */
	@Override
	public int read() throws IOException {
		if (pos < 0 || (buffer != null && pos > buflen)) {
			String content = getContent();
			if (content == null) {
				return -1;
			}
			buffer = content.getBytes();
			pos = -1;
			buflen = content.length() - 1;
		}

		int val;
		if (pos < buflen) {
			val = buffer[++pos];
		} else {
			val = pos = -1;
		}
		return val;
	}

	/**
	 * Get the number of bytes we can read before we block.
	 *
	 * @return the number bytes
	 * @throws IOException
	 */
	@Override
	public int available() throws IOException {
		if (buffer == null) {
			return 0;
		} else if (pos < 0) {
			return 0;
		} else {
			return buflen - pos - 1;
		}
	}

	/**
	 * No mark supported
	 *
	 * @return false
	 */
	@Override
	public boolean markSupported() {
		return false;
	}

	/**
	 * Close this file follower
	 */
	@Override
	public void close() throws IOException {
		queue.offer("");
		watcher.running = false;
	}

	/**
	 * Watch a file - uses the inotify facility
	 */
	private class FileWatcher implements Runnable {

		private final static int BUFFER_SIZE = 1024;
		private final String filename;
		private final Path file;
		private final Path rootDir;
		private boolean running;
		private long filesize;

		public FileWatcher(Path file) {
			this.filename = file.toString();
			this.file = file;
			this.rootDir = file.getParent();
			this.running = false;
		}

		@Override
		public void run() {
			try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
				WatchKey regkey = rootDir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);

				// Save the initial size of the file
				this.filesize = file.toFile().length();

				synchronized (this) {
					this.running = true;
					notifyAll();
				}

				for (;;) {
					// wait for key to be signalled
					WatchKey key;
					try {
						do {
							key = watcher.poll(2, TimeUnit.SECONDS);
							if (!running) {
								return;
							}
						} while (key == null);
					} catch (InterruptedException x) {
						return;
					}

					if (regkey != key) {
						throw new IllegalStateException("watch key " + key + " not recognized");
					}

					for (WatchEvent<?> event : key.pollEvents()) {
						WatchEvent.Kind kind = event.kind();

						// @todo: what should we do with an overflow?
						if (kind == OVERFLOW) {
							continue;
						}

						// Context for directory entry event is the file name of entry
						WatchEvent<Path> ev = cast(event);
						Path name = ev.context();
						Path child = rootDir.resolve(name);

						// if we have a change, call the handler
						if (kind == ENTRY_MODIFY || kind == ENTRY_CREATE) {
							try {
								if (child.toString().equals(filename)) {
									long currentFilesize = file.toFile().length();
									// test if we have beed truncated since the last time
									if (currentFilesize < filesize) {
										filesize = 0;
									}

									// Read from the end of the file
									try (FileInputStream fp = new FileInputStream(filename)) {
										fp.skip(filesize);
										int nread;
										byte[] buffer = new byte[BUFFER_SIZE];
										while ((nread = fp.read(buffer)) > 0) {
											queue.offer(new String(buffer, 0, nread));
										}
									}
									filesize = currentFilesize;
								}
							} catch (Throwable t) {
								// ignore any exception by the handler, we need
								// the watcher to continue.
							}
						}
					}

					// reset the key so we can get more events
					if (!key.reset()) {
						break;
					}
				}

			} catch (Throwable t) {
			}
		}

		/**
		 * Cast a watch event to our particular type
		 *
		 * @param event is the generic event
		 * @return the specifically typed event
		 */
		@SuppressWarnings("unchecked")
		private <T> WatchEvent<T> cast(WatchEvent<?> event) {
			return (WatchEvent<T>) event;
		}
	}
}
