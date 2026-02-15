package com.kmg.ocr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kmg.ocr.config.OcrProperties;
import com.kmg.ocr.dto.CreateJobRequest;
import com.kmg.ocr.dto.JobItemView;
import com.kmg.ocr.dto.JobView;
import com.kmg.ocr.model.*;
import com.kmg.ocr.repo.JobRepository;
import com.kmg.ocr.repo.OcrCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final OcrCheckpointRepository checkpointRepository;
    private final FileStatsService fileStatsService;
    private final OcrService ocrService;
    private final PdfService pdfService;
    private final QuotaService quotaService;
    private final EventService eventService;
    private final OcrProperties properties;
    private final ObjectMapper objectMapper;
    private final Object credentialAllocationLock = new Object();

    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> stopRequests = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> runningJobId = new AtomicReference<>(null);

    public JobService(
            JobRepository jobRepository,
            OcrCheckpointRepository checkpointRepository,
            FileStatsService fileStatsService,
            OcrService ocrService,
            PdfService pdfService,
            QuotaService quotaService,
            EventService eventService,
            OcrProperties properties,
            ObjectMapper objectMapper
    ) {
        this.jobRepository = jobRepository;
        this.checkpointRepository = checkpointRepository;
        this.fileStatsService = fileStatsService;
        this.ocrService = ocrService;
        this.pdfService = pdfService;
        this.quotaService = quotaService;
        this.eventService = eventService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public synchronized String createJob(CreateJobRequest request) {
        List<String> folders = request.folders().stream()
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .distinct()
                .toList();

        if (folders.isEmpty()) {
            throw new IllegalArgumentException("At least one folder is required.");
        }

        String jobId = UUID.randomUUID().toString();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        JobRecord job = new JobRecord(
                jobId,
                request.strategy(),
                request.parallelism(),
                JobStatus.CREATED,
                now,
                null,
                null,
                null,
                folders.size(),
                0,
                null,
                null
        );
        jobRepository.insertJob(job);

        for (int i = 0; i < folders.size(); i++) {
            String folder = folders.get(i);
            int imageCount = fileStatsService.computeStats(folder).imageCount();
            if (imageCount <= 0) {
                throw new IllegalArgumentException("Folder has no supported images: " + folder);
            }

            JobItemRecord item = new JobItemRecord(
                    UUID.randomUUID().toString(),
                    jobId,
                    i,
                    folder,
                    imageCount,
                    0,
                    JobItemStatus.PENDING,
                    null,
                    null,
                    now,
                    null,
                    null
            );
            jobRepository.insertItem(item);
        }

        eventService.publish("job-created", jobId, "Job created", Map.of("jobId", jobId));
        return jobId;
    }

    public synchronized void startJob(String jobId) {
        if (runningJobId.get() != null) {
            throw new IllegalStateException("Another job is already running.");
        }

        JobRecord job = jobRepository.findJobById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.status() == JobStatus.RUNNING) {
            return;
        }

        List<JobItemRecord> items = jobRepository.findItemsByJobId(jobId);
        int completedItems = (int) items.stream().filter(this::isAlreadyCompleted).count();

        runningJobId.set(jobId);
        stopRequests.remove(jobId);
        jobRepository.prepareJobForRun(jobId, completedItems);
        eventService.publish("job-started", jobId, "Job started", Map.of("processedItems", completedItems));

        try {
            jobExecutor.submit(() -> runJob(jobId));
        } catch (Exception ex) {
            runningJobId.set(null);
            stopRequests.remove(jobId);
            throw ex;
        }
    }

    public void stopJob(String jobId) {
        stopRequests.add(jobId);
        eventService.publish("job-stop-requested", jobId, "Stop requested", null);
    }

    public List<JobView> listJobs() {
        return jobRepository.findJobs().stream()
                .map(this::toView)
                .toList();
    }

    public JobView getJob(String jobId) {
        JobRecord job = jobRepository.findJobById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
        return toView(job);
    }

    private JobView toView(JobRecord job) {
        List<JobItemView> items = jobRepository.findItemsByJobId(job.id()).stream()
                .map(item -> new JobItemView(
                        item.id(),
                        item.queueIndex(),
                        item.folderPath(),
                        item.imageTotal(),
                        item.imageDone(),
                        item.status(),
                        item.pdfPath(),
                        item.errorReason(),
                        toText(item.startedAt()),
                        toText(item.endedAt())
                ))
                .toList();

        return new JobView(
                job.id(),
                job.strategy(),
                job.parallelism(),
                job.status(),
                toText(job.createdAt()),
                toText(job.startedAt()),
                toText(job.endedAt()),
                job.stopReason(),
                job.totalItems(),
                job.processedItems(),
                job.currentCredentialId(),
                job.lastError(),
                items
        );
    }

    private void runJob(String jobId) {
        int completedItems = 0;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("jobId", jobId);
        report.put("startedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        List<Map<String, Object>> reportItems = new ArrayList<>();
        report.put("items", reportItems);

        try {
            JobRecord job = jobRepository.findJobById(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));
            List<JobItemRecord> items = jobRepository.findItemsByJobId(jobId);
            RoundRobinState rrState = new RoundRobinState();

            for (JobItemRecord item : items) {
                ensureNotStopped(jobId);

                if (isAlreadyCompleted(item)) {
                    completedItems++;
                    jobRepository.updateJobStatus(jobId, JobStatus.RUNNING, null, null, null, completedItems, false, false);
                    continue;
                }

                processItem(jobId, item, job.strategy(), job.parallelism(), rrState, reportItems);
                completedItems++;
                jobRepository.updateJobStatus(jobId, JobStatus.RUNNING, null, null, null, completedItems, false, false);
            }

            jobRepository.updateJobStatus(jobId, JobStatus.COMPLETED, null, null, null, completedItems, false, true);
            report.put("status", "COMPLETED");
            eventService.publish("job-completed", jobId, "Job completed", Map.of("processedItems", completedItems));
        } catch (StopRequestedException e) {
            stopRemainingItems(jobId, "Stopped by user");
            jobRepository.updateJobStatus(jobId, JobStatus.STOPPED, "Stopped by user", null, null, completedItems, false, true);
            report.put("status", "STOPPED");
            report.put("stopReason", "Stopped by user");
            eventService.publish("job-stopped", jobId, "Job stopped", null);
        } catch (Exception e) {
            log.error("Job failed: {}", e.getMessage(), e);
            stopRemainingItems(jobId, "Stopped due to failure");
            jobRepository.updateJobStatus(
                    jobId,
                    JobStatus.FAILED,
                    "OCR failure",
                    e.getMessage(),
                    null,
                    completedItems,
                    false,
                    true
            );
            report.put("status", "FAILED");
            report.put("error", e.getMessage());
            eventService.publish("job-failed", jobId, "Job failed", Map.of("error", e.getMessage()));
        } finally {
            reconcileRunningJobIfNeeded(jobId);
            report.put("endedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            writeReport(jobId, report);
            runningJobId.set(null);
            stopRequests.remove(jobId);
        }
    }

    private boolean isAlreadyCompleted(JobItemRecord item) {
        if (item.status() != JobItemStatus.COMPLETED || item.pdfPath() == null || item.pdfPath().isBlank()) {
            return false;
        }
        return Files.exists(Path.of(item.pdfPath()));
    }

    private void processItem(String jobId, JobItemRecord item, KeySelectionStrategy strategy, int parallelism,
                             RoundRobinState rrState, List<Map<String, Object>> reportItems) {
        List<Path> images = sortNatural(fileStatsService.listSupportedImages(item.folderPath()));
        ResumeState resume = loadResumeState(item, images);

        jobRepository.updateItem(item.id(), JobItemStatus.RUNNING, resume.startIndex(), null, null, true, false);
        eventService.publish("item-started", jobId, "Folder started", Map.of(
                "folder", item.folderPath(),
                "resumeFrom", resume.startIndex()
        ));

        List<OcrPageResult> pages = new ArrayList<>(resume.cachedPages());
        if (parallelism > 1) {
            processImagesParallel(jobId, item, images, pages, resume.startIndex(), strategy, parallelism, rrState);
        } else {
            processImagesSequential(jobId, item, images, pages, resume.startIndex(), strategy, rrState);
        }

        if (pages.size() != images.size()) {
            throw new RuntimeException("Resume mismatch: expected " + images.size() + " pages but got " + pages.size());
        }

        String pdfName = derivePdfName(item.folderPath());
        Path pdfPath = Path.of(properties.getOutput().getPdfDir()).resolve(pdfName);
        Path writtenPdf = pdfService.writeSearchablePdf(pages, uniquePath(pdfPath));

        checkpointRepository.deleteByItemId(item.id());
        jobRepository.updateItem(item.id(), JobItemStatus.COMPLETED, images.size(), writtenPdf.toString(), null, false, true);
        eventService.publish("item-completed", jobId, "Folder completed", Map.of("pdfPath", writtenPdf.toString()));

        Map<String, Object> reportItem = new LinkedHashMap<>();
        reportItem.put("folderPath", item.folderPath());
        reportItem.put("imageTotal", images.size());
        reportItem.put("status", "COMPLETED");
        reportItem.put("pdfPath", writtenPdf.toString());
        reportItem.put("resumedFrom", resume.startIndex());
        reportItems.add(reportItem);
    }

    private ResumeState loadResumeState(JobItemRecord item, List<Path> images) {
        List<OcrCheckpointRepository.CheckpointRow> checkpoints = checkpointRepository.findByItemId(item.id());
        Map<Integer, OcrCheckpointRepository.CheckpointRow> byIndex = checkpoints.stream()
                .collect(Collectors.toMap(OcrCheckpointRepository.CheckpointRow::pageIndex, v -> v, (a, b) -> b, LinkedHashMap::new));

        List<OcrPageResult> cachedPages = new ArrayList<>();
        int contiguous = 0;

        while (contiguous < images.size()) {
            OcrCheckpointRepository.CheckpointRow row = byIndex.get(contiguous);
            if (row == null) {
                break;
            }

            Path expected = images.get(contiguous).toAbsolutePath().normalize();
            Path stored = Path.of(row.imagePath()).toAbsolutePath().normalize();
            if (!expected.equals(stored)) {
                break;
            }

            cachedPages.add(new OcrPageResult(
                    expected,
                    row.fullText(),
                    readWordsJson(row.wordsJson())
            ));
            contiguous++;
        }

        int requested = Math.max(0, Math.min(item.imageDone(), images.size()));
        if (requested > contiguous) {
            log.warn("Job item {} had image_done={} but contiguous checkpoints={} (fallback resume index={})",
                    item.id(), requested, contiguous, contiguous);
        }

        return new ResumeState(contiguous, cachedPages);
    }

    private List<OcrWord> readWordsJson(String wordsJson) {
        if (wordsJson == null || wordsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(wordsJson, new TypeReference<List<OcrWord>>() {
            });
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse OCR checkpoint words", e);
        }
    }

    private String writeWordsJson(List<OcrWord> words) {
        try {
            return objectMapper.writeValueAsString(words);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize OCR checkpoint words", e);
        }
    }

    private void processImagesSequential(String jobId, JobItemRecord item, List<Path> images, List<OcrPageResult> pages,
                                         int startIndex, KeySelectionStrategy strategy, RoundRobinState rrState) {
        for (int i = startIndex; i < images.size(); i++) {
            ensureNotStopped(jobId);
            Path image = images.get(i);
            try {
                OcrPageResult page = detectWithCredentialFallback(jobId, image, strategy, rrState);
                pages.add(page);
                checkpointRepository.upsert(item.id(), i, image.toString(), page.fullText(), writeWordsJson(page.words()));
            } catch (Exception ex) {
                jobRepository.updateItem(item.id(), JobItemStatus.FAILED, i, null, ex.getMessage(), false, true);
                throw new RuntimeException("OCR failed at " + image + ": " + ex.getMessage(), ex);
            }

            int done = i + 1;
            jobRepository.updateItem(item.id(), JobItemStatus.RUNNING, done, null, null, false, false);
            eventService.publish("item-progress", jobId, "Processing image", Map.of(
                    "folder", item.folderPath(),
                    "imageDone", done,
                    "imageTotal", images.size()
            ));
        }
    }

    private void processImagesParallel(String jobId, JobItemRecord item, List<Path> images, List<OcrPageResult> pages,
                                       int startIndex, KeySelectionStrategy strategy, int parallelism,
                                       RoundRobinState rrState) {
        if (startIndex >= images.size()) {
            return;
        }

        ExecutorService executor = Executors.newFixedThreadPool(parallelism);
        CompletionService<IndexedPageResult> completionService = new ExecutorCompletionService<>(executor);
        Map<Integer, OcrPageResult> collected = new ConcurrentHashMap<>();
        int remaining = images.size() - startIndex;

        try {
            for (int i = startIndex; i < images.size(); i++) {
                final int index = i;
                final Path image = images.get(i);
                completionService.submit(() -> {
                    ensureNotStopped(jobId);
                    OcrPageResult result = detectWithCredentialFallback(jobId, image, strategy, rrState);
                    return new IndexedPageResult(index, result);
                });
            }

            int completedNew = 0;
            while (completedNew < remaining) {
                ensureNotStopped(jobId);
                Future<IndexedPageResult> future = completionService.take();
                IndexedPageResult pageResult = future.get();
                collected.put(pageResult.index(), pageResult.page());

                checkpointRepository.upsert(
                        item.id(),
                        pageResult.index(),
                        pageResult.page().imagePath().toString(),
                        pageResult.page().fullText(),
                        writeWordsJson(pageResult.page().words())
                );

                completedNew++;
                int done = startIndex + completedNew;
                jobRepository.updateItem(item.id(), JobItemStatus.RUNNING, done, null, null, false, false);
                eventService.publish("item-progress", jobId, "Processing image", Map.of(
                        "folder", item.folderPath(),
                        "imageDone", done,
                        "imageTotal", images.size()
                ));
            }
        } catch (StopRequestedException e) {
            throw e;
        } catch (Exception ex) {
            int done = startIndex + collected.size();
            jobRepository.updateItem(item.id(), JobItemStatus.FAILED, done, null, ex.getMessage(), false, true);
            throw new RuntimeException("Parallel OCR failed: " + ex.getMessage(), ex);
        } finally {
            executor.shutdownNow();
        }

        List<OcrPageResult> resumedPages = collected.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        pages.addAll(resumedPages);
    }

    private OcrPageResult detectWithCredentialFallback(String jobId, Path image, KeySelectionStrategy strategy,
                                                       RoundRobinState rrState) {
        int attempts = 0;
        while (attempts < 10) {
            ensureNotStopped(jobId);
            attempts++;

            CredentialSummary credential;
            synchronized (credentialAllocationLock) {
                credential = selectCredential(strategy, rrState);
                quotaService.consumeOneUnit(credential.id());
            }

            try {
                return ocrService.detectText(image, Path.of(credential.filePath()));
            } catch (OcrService.OcrQuotaExceededException quotaEx) {
                quotaService.markExhausted(credential.id(), "Quota exceeded: " + quotaEx.getMessage());
            } catch (Exception ex) {
                try {
                    quotaService.releaseOneUnit(credential.id());
                } catch (Exception releaseEx) {
                    log.warn("Failed to release quota unit after OCR error: {}", releaseEx.getMessage());
                }
                throw ex;
            }
        }
        throw new RuntimeException("All credentials are exhausted.");
    }

    private void reconcileRunningJobIfNeeded(String jobId) {
        JobRecord current = jobRepository.findJobById(jobId).orElse(null);
        if (current == null || current.status() != JobStatus.RUNNING) {
            return;
        }

        List<JobItemRecord> items = jobRepository.findItemsByJobId(jobId);
        int completedCount = (int) items.stream().filter(this::isAlreadyCompleted).count();
        Optional<String> failedError = items.stream()
                .filter(item -> item.status() == JobItemStatus.FAILED)
                .map(JobItemRecord::errorReason)
                .filter(Objects::nonNull)
                .filter(reason -> !reason.isBlank())
                .findFirst();
        boolean hasPendingOrRunning = items.stream()
                .anyMatch(item -> item.status() == JobItemStatus.PENDING || item.status() == JobItemStatus.RUNNING);

        if (failedError.isPresent()) {
            jobRepository.updateJobStatus(
                    jobId,
                    JobStatus.FAILED,
                    "OCR failure",
                    failedError.get(),
                    null,
                    completedCount,
                    false,
                    true
            );
            return;
        }

        if (!items.isEmpty() && completedCount == items.size()) {
            jobRepository.updateJobStatus(jobId, JobStatus.COMPLETED, null, null, null, completedCount, false, true);
            return;
        }

        if (hasPendingOrRunning) {
            stopRemainingItems(jobId, "Stopped due to unexpected interruption");
            jobRepository.updateJobStatus(
                    jobId,
                    JobStatus.FAILED,
                    "Unexpected interruption",
                    current.lastError(),
                    null,
                    completedCount,
                    false,
                    true
            );
        }
    }

    private CredentialSummary selectCredential(KeySelectionStrategy strategy, RoundRobinState rrState) {
        List<CredentialSummary> available = quotaService.listCredentialSummaries().stream()
                .filter(c -> "ACTIVE".equals(c.status()))
                .filter(c -> c.remainingUnits() > 0)
                .sorted(Comparator.comparing(CredentialSummary::fileName))
                .toList();

        if (available.isEmpty()) {
            throw new RuntimeException("All credentials are exhausted.");
        }

        return switch (strategy) {
            case MAX_REMAINING -> available.stream().max(Comparator.comparingInt(CredentialSummary::remainingUnits)).orElseThrow();
            case FILENAME_ORDER -> available.getFirst();
            case ROUND_ROBIN -> {
                int idx = rrState.nextIndex(available.size());
                yield available.get(idx);
            }
        };
    }

    private void ensureNotStopped(String jobId) {
        if (stopRequests.contains(jobId)) {
            throw new StopRequestedException();
        }
    }

    private void stopRemainingItems(String jobId, String reason) {
        List<JobItemRecord> items = jobRepository.findItemsByJobId(jobId);
        for (JobItemRecord item : items) {
            if (item.status() == JobItemStatus.PENDING || item.status() == JobItemStatus.RUNNING) {
                jobRepository.updateItem(item.id(), JobItemStatus.STOPPED, item.imageDone(), item.pdfPath(), reason, false, true);
            }
        }
    }

    private void writeReport(String jobId, Map<String, Object> report) {
        try {
            Files.createDirectories(Path.of(properties.getOutput().getReportDir()));
            Path reportPath = Path.of(properties.getOutput().getReportDir()).resolve(jobId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        } catch (IOException e) {
            log.warn("Failed to write report for {}: {}", jobId, e.getMessage());
        }
    }

    private List<Path> sortNatural(List<Path> paths) {
        return paths.stream()
                .sorted(Comparator.comparing(this::naturalKey))
                .collect(Collectors.toList());
    }

    private String naturalKey(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        Matcher matcher = Pattern.compile("\\d+").matcher(name);
        StringBuilder key = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            key.append(name, last, matcher.start());
            try {
                key.append(String.format("%12d", Long.parseLong(matcher.group())));
            } catch (NumberFormatException e) {
                key.append(matcher.group());
            }
            last = matcher.end();
        }
        key.append(name.substring(last));
        return key.toString();
    }

    private String derivePdfName(String folderPath) {
        String folder = Path.of(folderPath).getFileName().toString();
        if (folder.isBlank()) {
            folder = "output";
        }
        return folder + ".pdf";
    }

    private Path uniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return path.getParent().resolve(base + "_" + System.currentTimeMillis() + ext);
    }

    private String toText(Object value) {
        return value == null ? null : value.toString();
    }

    private static class RoundRobinState {
        private final AtomicInteger cursor = new AtomicInteger(0);

        int nextIndex(int size) {
            if (size <= 1) {
                return 0;
            }
            return Math.floorMod(cursor.getAndIncrement(), size);
        }
    }

    private static class StopRequestedException extends RuntimeException {
    }

    private record IndexedPageResult(int index, OcrPageResult page) {
    }

    private record ResumeState(int startIndex, List<OcrPageResult> cachedPages) {
    }
}
