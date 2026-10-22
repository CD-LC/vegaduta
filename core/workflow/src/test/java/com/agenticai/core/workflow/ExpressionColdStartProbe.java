package com.agenticai.core.workflow;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.agenticai.core.workflow.expression.BasicEvalContext;
import com.agenticai.core.workflow.expression.ExpressionEngine;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Main class for {@link ExpressionColdStartTest}, run in a fresh JVM so nothing is pre-loaded by
 * other tests. Prints {@code key=value} lines: classes loaded while constructing the resolver (the
 * Spring bean), and while running the first real expressions afterwards, plus how long those first
 * expressions took and whether any tripped the wall budget.
 */
public final class ExpressionColdStartProbe {

    private ExpressionColdStartProbe() {}

    public static void main(String[] args) {
        var classes = ManagementFactory.getClassLoadingMXBean();
        ObjectMapper mapper = JsonMappers.plain();
        long c0 = classes.getTotalLoadedClassCount();
        WorkflowExpressionResolver resolver =
                new WorkflowExpressionResolver(
                        (RunDataStore) null, (WorkflowRunRepository) null, mapper, true, false);
        long c1 = classes.getTotalLoadedClassCount();

        WorkflowExpressionResolver.NodeEvalInfo info =
                new WorkflowExpressionResolver.NodeEvalInfo("w", "wf", null, "ann");
        ExpressionScope scope =
                ExpressionScope.iteration("{\"price\":5}", 2, List.of("{\"price\":5}"));
        WorkflowNodeDto node =
                new WorkflowNodeDto(
                        "n1",
                        "http",
                        Map.of(),
                        Map.of(
                                "label",
                                "N",
                                "config",
                                Map.of(
                                        "url",
                                        "https://api.test/items/{{ $json.id }}?p={{ $item.price * 1.18 }}",
                                        "bodyType",
                                        "json",
                                        "body",
                                        "{\"n\":{{ $index }},\"name\":{{ toJson(upper($json.name)) }}}")));
        String failure = "none";
        long t0 = System.nanoTime();
        try {
            WorkflowNodeDto out =
                    resolver.resolve(node, "{\"id\":7,\"name\":\"ann\"}", info, scope);
            boolean cond =
                    resolver.evaluateCondition(
                            "$json.id > 3 && $index == 2", "{\"id\":7}", info, scope);
            String rendered =
                    new ExpressionEngine()
                            .render(
                                    "{{ $json.a[0] }} {{ formatDate($now, 'yyyy') }}",
                                    BasicEvalContext.ofInput("{\"a\":[1]}"));
            if (!out.config().get("url").toString().contains("items/7")
                    || !cond
                    || rendered.isBlank()) {
                failure = "wrong-result";
            }
        } catch (RuntimeException e) {
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        long c2 = classes.getTotalLoadedClassCount();

        System.out.println("constructionLoaded=" + (c1 - c0));
        System.out.println("firstUseLoaded=" + (c2 - c1));
        System.out.println("firstUseMs=" + elapsedMs);
        System.out.println("failure=" + failure);
    }
}
