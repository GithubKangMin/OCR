package com.kmg.ocr.service;

import com.kmg.ocr.dto.PickFolderResponse;
import org.springframework.stereotype.Service;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FolderPickerService {

    public PickFolderResponse pickFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            PickFolderResponse fromAppleScript = pickFolderWithAppleScript();
            if (!fromAppleScript.cancelled()) {
                return fromAppleScript;
            }
            // Fallback to Swing if AppleScript was cancelled or failed.
        }

        if (GraphicsEnvironment.isHeadless()) {
            return new PickFolderResponse(null, true, "GUI picker unavailable. 경로 입력 후 '경로 추가'를 사용하세요.");
        }

        PickFolderResponse swingResult = pickFolderWithSwing();
        if (!swingResult.cancelled()) {
            return swingResult;
        }
        return new PickFolderResponse(null, true, "폴더 선택이 취소되었거나 표시되지 않았습니다. 경로 입력 후 '경로 추가'를 사용하세요.");
    }

    private PickFolderResponse pickFolderWithSwing() {
        AtomicReference<String> selected = new AtomicReference<>(null);
        AtomicReference<Exception> error = new AtomicReference<>(null);

        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setDialogTitle("OCR 대상 폴더 선택");
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    chooser.setAcceptAllFileFilterUsed(false);
                    int result = chooser.showOpenDialog(null);
                    if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                        selected.set(chooser.getSelectedFile().getAbsolutePath());
                    }
                } catch (Exception e) {
                    error.set(e);
                }
            });
        } catch (Exception e) {
            return new PickFolderResponse(null, true, "Folder picker failed: " + e.getMessage());
        }

        if (error.get() != null) {
            return new PickFolderResponse(null, true, "Folder picker failed: " + error.get().getMessage());
        }

        if (selected.get() == null) {
            return new PickFolderResponse(null, true, "Selection cancelled");
        }

        return new PickFolderResponse(selected.get(), false, "OK");
    }

    private PickFolderResponse pickFolderWithAppleScript() {
        String script = "set p to POSIX path of (choose folder with prompt \"OCR 대상 폴더 선택\")\nreturn p";
        ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            String text = output.toString().trim();
            if (exitCode == 0 && !text.isBlank()) {
                return new PickFolderResponse(text, false, "OK");
            }
            if (text.toLowerCase().contains("user canceled")) {
                return new PickFolderResponse(null, true, "Selection cancelled");
            }
            return new PickFolderResponse(null, true, "AppleScript picker failed: " + text);
        } catch (Exception e) {
            return new PickFolderResponse(null, true, "AppleScript picker failed: " + e.getMessage());
        }
    }
}
