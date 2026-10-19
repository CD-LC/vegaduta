package com.agenticai.core.providers.addons;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.providers.localmodels.HardwareDetector;
import com.agenticai.core.providers.localmodels.HardwareProfile;
import com.agenticai.core.providers.localmodels.LocalModelOllamaGateway;
import com.agenticai.core.providers.localmodels.LocalModelPullService;
import com.agenticai.core.providers.localmodels.LocalModelsService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** A fully wired {@link AddonService} over in-memory state, a fake Ollama and a fake audit log. */
final class AddonKit {

    static final String TOKEN = "t".repeat(40);

    /**
     * search (flag) <- docs (profile, requires search) ; browser (env) ; small model <- big model ; one planned.
     * The ids and their order are what the service tests assert on.
     */
    static final String CATALOG = """
        {"schemaVersion":1,"addons":[
         {"id":"search","name":"Search","description":"D","category":"search","kind":"compose-profile","availability":"available",
          "sizeMb":300,"minRamMb":512,"needsGpu":false,"license":"AGPL-3.0","free":true,"requires":[],"defaultOn":false,
          "hostCommand":"./scripts/vegaduta personal --search","learnMore":"docs/x.md","unlocks":["web_search"],"model":null,
          "compose":{"profiles":[],"flags":["--search"],"env":{}},
          "observe":{"property":"test.search.url","test":"non-blank"}},
         {"id":"docs","name":"Docs","description":"D","category":"documents","kind":"compose-profile","availability":"available",
          "sizeMb":4000,"minRamMb":2048,"needsGpu":false,"license":"Apache-2.0","free":true,"requires":["search"],"defaultOn":false,
          "hostCommand":"./scripts/vegaduta personal --profile rag","learnMore":"docs/x.md","unlocks":["documents"],"model":null,
          "compose":{"profiles":["rag"],"flags":[],"env":{}},
          "observe":{"property":"test.sidecar","test":"true"}},
         {"id":"browser","name":"Browser","description":"D","category":"assistant","kind":"compose-profile","availability":"available",
          "sizeMb":6000,"minRamMb":1024,"needsGpu":false,"license":"Apache-2.0","free":true,"requires":[],"defaultOn":false,
          "hostCommand":"Set CORE_IMAGE_VARIANT=-full and CORE_MEM_LIMIT=2560m in .env","learnMore":"docs/x.md","unlocks":["browser_automation"],
          "model":null,"compose":{"profiles":[],"flags":[],"env":{"CORE_IMAGE_VARIANT":"-full","CORE_MEM_LIMIT":"2560m"}},"observe":null},
         {"id":"work","name":"Work","description":"D","category":"assistant","kind":"compose-profile","availability":"available",
          "sizeMb":1350,"minRamMb":3072,"needsGpu":false,"license":"Apache-2.0","free":true,"requires":[],"defaultOn":true,
          "hostCommand":"./scripts/vegaduta personal --profile workspace","learnMore":"docs/x.md","unlocks":["assistant_terminal"],
          "model":null,"compose":{"profiles":["workspace"],"flags":[],"env":{}},"observe":null},
         {"id":"m-small","name":"Small","description":"D","category":"models","kind":"model-pull","availability":"available",
          "sizeMb":1536,"minRamMb":6144,"needsGpu":false,"license":"Apache-2.0","free":true,"requires":[],"defaultOn":false,
          "hostCommand":"ollama pull qwen3.5:2b","learnMore":"docs/x.md","unlocks":["local_models"],"model":"qwen3.5:2b",
          "compose":null,"observe":null},
         {"id":"m-big","name":"Big","description":"D","category":"models","kind":"model-pull","availability":"available",
          "sizeMb":7168,"minRamMb":14336,"needsGpu":false,"license":"Apache-2.0","free":true,"requires":["m-small"],"defaultOn":false,
          "hostCommand":"ollama pull qwen3.5:9b","learnMore":"docs/x.md","unlocks":["local_models"],"model":"qwen3.5:9b",
          "compose":null,"observe":null},
         {"id":"later","name":"Later","description":"Planned. Not yet.","category":"media","kind":"planned","availability":"planned",
          "sizeMb":0,"minRamMb":0,"needsGpu":false,"license":"","free":true,"requires":[],"defaultOn":false,
          "hostCommand":"","learnMore":"docs/x.md","unlocks":["speech"],"model":null,"compose":null,"observe":null}
        ]}
        """;

    final Map<String, AddonState> rows = new LinkedHashMap<>();
    final MockEnvironment environment = new MockEnvironment();
    final LocalModelOllamaGateway ollama = mock(LocalModelOllamaGateway.class);
    final LocalModelsService localModels = mock(LocalModelsService.class);
    final LocalModelPullService pulls = mock(LocalModelPullService.class);
    final HardwareDetector hardware = mock(HardwareDetector.class);
    final AuditLogService auditLog = mock(AuditLogService.class);
    final List<String> audited = new ArrayList<>();
    final List<String> startedPulls = new ArrayList<>();
    final Map<String, LocalModelPullService.Status> jobs = new HashMap<>();
    List<String> installedModels = new ArrayList<>();
    boolean ollamaUp = true;
    final AddonHostAuth hostAuth = new AddonHostAuth(TOKEN);
    final AddonCatalog catalog;
    final AddonStateStore store;
    final AddonService service;

    AddonKit() {
        this(CATALOG);
    }

    AddonKit(String catalogJson) {
        catalog = AddonCatalog.parse(catalogJson);
        // The kit simulates the personal install (local auth); tests flip it to check other shapes.
        environment.setProperty("app.auth.mode", "local");
        AddonStateRepository repository = mock(AddonStateRepository.class);
        when(repository.findAll()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(repository.findById(anyString())).thenAnswer(i -> Optional.ofNullable(rows.get((String) i.getArgument(0))));
        when(repository.save(any(AddonState.class))).thenAnswer(i -> {
            AddonState s = i.getArgument(0);
            rows.put(s.getAddonId(), s);
            return s;
        });
        store = new AddonStateStore(repository);

        when(ollama.tags()).thenAnswer(i -> ollamaUp
                ? new LocalModelOllamaGateway.Tags(true, List.copyOf(installedModels))
                : new LocalModelOllamaGateway.Tags(false, List.of()));
        when(localModels.startPull(anyString())).thenAnswer(i -> {
            String tag = i.getArgument(0);
            startedPulls.add(tag);
            String job = "job-" + startedPulls.size();
            jobs.put(job, new LocalModelPullService.Status("running", 5, "Starting"));
            return job;
        });
        when(pulls.status(anyString())).thenAnswer(i -> Optional.ofNullable(jobs.get((String) i.getArgument(0))));
        when(hardware.detect()).thenReturn(new HardwareProfile(16, 8, null, false));
        when(auditLog.record(any(), anyString(), anyString(), anyString(), any(AuditOutcome.class), any()))
                .thenAnswer(i -> {
                    audited.add(i.getArgument(1) + ":" + i.getArgument(3) + ":" + i.getArgument(5));
                    return null;
                });
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("audit", auditLog);
        service = new AddonService(
                catalog, store, localModels, pulls, ollama, hardware, environment, hostAuth,
                beans.getBeanProvider(AuditLogService.class));
    }

    /** What the installer would send for {@code id} at the revision currently stored. */
    AddonApi.ReportItem report(String id, String status) {
        AddonState row = rows.get(id);
        return new AddonApi.ReportItem(id, row == null ? 0L : row.getRev(), status, null);
    }

    AddonApi.ReportResult hostReports(AddonApi.ReportItem... items) {
        return service.hostReport(new AddonApi.ReportRequest(List.of(items)));
    }
}
