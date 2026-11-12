package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.context.ChatContextCompaction;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.agents.vision.VisionSessionService;
import com.agenticai.core.agents.vision.VisionUsageService;
import com.agenticai.core.storage.FileStorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What a turn reads into the prompt besides history: long-term memory, attachments and vision
 * frames, the data-analysis sandbox, model modalities and context compaction. Constructor-injected
 * as one unit; the optional members are lazy providers. Holds no logic.
 */
@Component
record ChatContextFacade(
        ObjectProvider<AgentMemoryService> agentMemoryService,
        ObjectProvider<TenantMemoryService> tenantMemoryService,
        FileStorageService fileStorageService,
        InlineAttachmentProcessor inlineAttachmentProcessor,
        VisionUsageService visionUsageService,
        VisionSessionService visionSessionService,
        ObjectProvider<SandboxFileRegistry> sandboxFileRegistry,
        ObjectProvider<ModelModalitiesService> modelModalitiesService,
        ObjectProvider<ChatContextCompaction> chatContextCompaction,
        ObjectProvider<com.agenticai.core.agents.memory.SessionTaintStore> sessionTaintStore) {}
