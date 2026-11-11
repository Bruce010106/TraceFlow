package dev.traceflow;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
/** SPA entry routes; API requests and asset paths are never forwarded. */
@Controller
public class FrontendController {
    @GetMapping({"/","/projects/{projectId}/import","/projects/{projectId}/review","/projects/{projectId}/review/{documentId}","/projects/{projectId}/tasks"})
    public String index() { return "forward:/index.html"; }
}
