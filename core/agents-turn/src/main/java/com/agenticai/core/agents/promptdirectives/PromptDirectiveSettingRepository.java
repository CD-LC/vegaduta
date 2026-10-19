package com.agenticai.core.agents.promptdirectives;

import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-schema store of {@link PromptDirectiveSetting}; keyed by directive key. */
interface PromptDirectiveSettingRepository extends JpaRepository<PromptDirectiveSetting, String> {}
