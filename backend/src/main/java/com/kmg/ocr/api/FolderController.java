package com.kmg.ocr.api;

import com.kmg.ocr.dto.FolderStatsResponse;
import com.kmg.ocr.service.FileStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/folders")
public class FolderController {
    private final FileStatsService fileStatsService;

    public FolderController(FileStatsService fileStatsService) {
        this.fileStatsService = fileStatsService;
    }

    @GetMapping("/stats")
    public FolderStatsResponse stats(@RequestParam("path") String path) {
        return fileStatsService.computeStats(path);
    }
}
