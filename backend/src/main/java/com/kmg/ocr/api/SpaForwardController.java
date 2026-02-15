package com.kmg.ocr.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaForwardController {
    @GetMapping(value = {"/", "/dashboard", "/credentials", "/queue", "/history"})
    public String forward() {
        return "forward:/index.html";
    }
}
