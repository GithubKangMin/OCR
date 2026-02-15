package com.kmg.ocr.api;

import com.kmg.ocr.dto.CreateJobRequest;
import com.kmg.ocr.dto.CreateJobResponse;
import com.kmg.ocr.dto.JobView;
import com.kmg.ocr.service.JobService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public CreateJobResponse createJob(@Valid @RequestBody CreateJobRequest request) {
        return new CreateJobResponse(jobService.createJob(request));
    }

    @GetMapping
    public List<JobView> listJobs() {
        return jobService.listJobs();
    }

    @GetMapping("/{id}")
    public JobView getJob(@PathVariable String id) {
        return jobService.getJob(id);
    }

    @PostMapping("/{id}/start")
    public ResponseEntity<Void> start(@PathVariable String id) {
        jobService.startJob(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{id}/stop")
    public ResponseEntity<Void> stop(@PathVariable String id) {
        jobService.stopJob(id);
        return ResponseEntity.accepted().build();
    }
}
