package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.CreateRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeDetail;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeFromRunRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeSummary;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RunRecipeRequest;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RunRecipeResponse;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.UpdateRecipeRequest;
import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Desktop recipe management: list, read, create by hand, save a finished run as a recipe, replay
 * one, rename, delete. Thin on purpose - the flag gate, access checks, validation and every status
 * code are decided in {@link DesktopRecipeService}, which throws only types {@code
 * GlobalExceptionHandler} already maps ({@code IllegalArgumentException} 400, {@code
 * ResponseStatusException} 403/404/409/503 and the agent-access {@code
 * ResourceAccessDeniedException} 404). A recipe in a response contains placeholders only, never a
 * secret or a value the user supplied. No {@code SecurityConfig} entry (authenticated by default).
 * Mirrors {@code BrowserRecipeController}.
 */
@RestController
@RequestMapping("/api/desktop/recipes")
public class DesktopRecipeController {

    private final DesktopRecipeService service;

    public DesktopRecipeController(DesktopRecipeService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<RecipeSummary>> list(
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(agentId), limit, offset, 50, 500);
    }

    @GetMapping("/{id}")
    public RecipeDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<RecipeDetail> create(@RequestBody CreateRecipeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    /**
     * "Save this run as a recipe": 201 with the recipe and any warnings about what was kept or
     * flagged.
     */
    @PostMapping("/from-run")
    public ResponseEntity<RecipeDetail> fromRun(@RequestBody RecipeFromRunRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.saveFromRun(request));
    }

    /**
     * Replay over REST: the same engine {@code run_desktop_recipe} uses, against the laptop's
     * desktop-agent sidecar. 200 always carries the run report; a failed or paused step is data in
     * the report ({@code success=false} plus the remedy), not an HTTP error.
     */
    @PostMapping("/{id}/run")
    public RunRecipeResponse run(@PathVariable UUID id, @RequestBody RunRecipeRequest request) {
        return service.run(id, request);
    }

    @PatchMapping("/{id}")
    public RecipeSummary update(@PathVariable UUID id, @RequestBody UpdateRecipeRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
