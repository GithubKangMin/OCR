package com.kmg.ocr.api;

import com.kmg.ocr.dto.PickFolderResponse;
import com.kmg.ocr.service.FolderPickerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final FolderPickerService folderPickerService;

    public SystemController(FolderPickerService folderPickerService) {
        this.folderPickerService = folderPickerService;
    }

    @PostMapping("/pick-folder")
    public PickFolderResponse pickFolder() {
        return folderPickerService.pickFolder();
    }
}
