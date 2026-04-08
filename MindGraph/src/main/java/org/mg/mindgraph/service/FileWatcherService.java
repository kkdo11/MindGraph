package org.mg.mindgraph.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileWatcherService {

    private final ExtractionPublisher extractionPublisher;

    @Value("${mindgraph.file-watcher.directory:./watched-files}")
    private String watchDirectoryPath;

    private WatchService watchService;
    private ExecutorService executorService;

    @PostConstruct
    public void init() {
        try {
            Path watchPath = Paths.get(watchDirectoryPath);
            if (!Files.exists(watchPath)) {
                Files.createDirectories(watchPath);
                log.info("Created watch directory: {}", watchPath);
            }

            watchService = FileSystems.getDefault().newWatchService();
            watchPath.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);

            executorService = Executors.newSingleThreadExecutor();
            executorService.submit(this::watchFiles);

            log.info("FileWatcherService started, watching directory: {}", watchDirectoryPath);
        } catch (IOException e) {
            log.error("Failed to initialize FileWatcherService", e);
        }
    }

    private void watchFiles() {
        try {
            WatchKey key;
            while ((key = watchService.take()) != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path filePath = Paths.get(watchDirectoryPath).resolve(fileName);

                    log.info("File event: {} - {}", kind, filePath);

                    try {
                        String content = Files.readString(filePath);
                        if (content != null && !content.trim().isEmpty()) {
                            // 비동기 추출을 위해 메시지 발행
                            extractionPublisher.publishExtractionRequest(content);
                        }
                    } catch (IOException e) {
                        log.error("Failed to read file: {}", filePath, e);
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("File watcher interrupted.");
        } catch (ClosedWatchServiceException e) {
            log.info("File watcher service closed.");
        }
    }

    @PreDestroy
    public void cleanup() {
        try {
            if (watchService != null) {
                watchService.close();
            }
            if (executorService != null) {
                executorService.shutdown();
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            }
            log.info("FileWatcherService shut down.");
        } catch (IOException | InterruptedException e) {
            log.error("Error during FileWatcherService cleanup", e);
        }
    }
}
