package com.agenticai.core.common.studioapp;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom domains for published studio apps. Owner paths run in the caller's
 * tenant schema; the hostname -> tenant registry is
 * {@code channel_account_link} under {@link ExternalAccountProvider#STUDIO_APP_DOMAIN},
 * written only once DNS control is proven, so a hostname can be held by one
 * tenant at a time. The check-then-link in {@link #verify} is not atomic
 * across tenants; the registry's unique key keeps the row itself single, and
 * both racers must have proven DNS control of the same name.
 */
@Service
public class StudioAppDomainService {

    private static final Logger log = LoggerFactory.getLogger(StudioAppDomainService.class);
    static final int MAX_DOMAINS_PER_APP = 10;

    private final StudioAppRepository appRepository;
    private final StudioAppDomainRepository domainRepository;
    private final TenantResolutionService tenantResolutionService;
    private final DnsTxtResolver dnsResolver;
    private final String cnameTarget;
    private final String aTarget;

    @Autowired
    public StudioAppDomainService(
        StudioAppRepository appRepository,
        StudioAppDomainRepository domainRepository,
        TenantResolutionService tenantResolutionService,
        @Value("${app.studio-app.domains.cname-target:}") String cnameTarget,
        @Value("${app.studio-app.domains.a-target:}") String aTarget,
        @Value("${app.studio-app.domains.dns-timeout-ms:3000}") int dnsTimeoutMs
    ) {
        this(appRepository, domainRepository, tenantResolutionService,
            new DnsTxtResolver.Jndi(dnsTimeoutMs), cnameTarget, aTarget);
    }

    StudioAppDomainService(
        StudioAppRepository appRepository,
        StudioAppDomainRepository domainRepository,
        TenantResolutionService tenantResolutionService,
        DnsTxtResolver dnsResolver,
        String cnameTarget,
        String aTarget
    ) {
        this.appRepository = appRepository;
        this.domainRepository = domainRepository;
        this.tenantResolutionService = tenantResolutionService;
        this.dnsResolver = dnsResolver;
        this.cnameTarget = blankToNull(cnameTarget);
        this.aTarget = blankToNull(aTarget);
    }

    /** Records the owner must create. Targets come from config, never hardcoded; null when unconfigured. */
    public record DnsInstructions(String txtName, String txtValue, String cnameTarget, String aTarget) {
    }

    public DnsInstructions instructionsFor(StudioAppDomain d) {
        return new DnsInstructions(
            StudioAppDomainRules.txtRecordName(d.getHostname()), d.getVerificationToken(), cnameTarget, aTarget);
    }

    @Transactional(readOnly = true)
    public List<StudioAppDomain> list(UUID appId) {
        requireApp(appId);
        return domainRepository.findByAppIdOrderByCreatedAtAsc(appId);
    }

    @Transactional
    public StudioAppDomain add(UUID appId, String rawHostname) {
        requireApp(appId);
        String host;
        try {
            host = StudioAppDomainRules.normalizeHostname(rawHostname);
        } catch (StudioAppDomainRules.InvalidHostnameException e) {
            throw new StudioAppService.StudioAppException(e.getMessage());
        }
        if (domainRepository.countByAppId(appId) >= MAX_DOMAINS_PER_APP) {
            throw new StudioAppService.StudioAppException("too_many_domains");
        }
        if (domainRepository.findByHostname(host).isPresent()) {
            throw new StudioAppService.StudioAppException("hostname_in_use");
        }
        StudioAppDomain d = new StudioAppDomain();
        d.setAppId(appId);
        d.setHostname(host);
        d.setVerificationToken(StudioAppDomainRules.newVerificationToken());
        try {
            return domainRepository.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            throw new StudioAppService.StudioAppException("hostname_in_use");
        }
    }

    /** Real DNS TXT lookup, then applies {@link StudioAppDomainRules#decide}. */
    @Transactional
    public StudioAppDomain verify(UUID appId, UUID domainId) {
        requireApp(appId);
        StudioAppDomain d = domainRepository.findByIdAndAppId(domainId, appId)
            .orElseThrow(() -> new StudioAppService.StudioAppException("domain_not_found"));
        List<String> txt = dnsResolver.lookupTxt(StudioAppDomainRules.txtRecordName(d.getHostname()));
        applyDecision(d, StudioAppDomainRules.decide(d.getVerificationToken(), txt));
        return d;
    }

    /** Package-visible so the decision -> state mapping is testable without DNS. */
    void applyDecision(StudioAppDomain d, StudioAppDomainRules.Decision decision) {
        d.setLastCheckedAt(Instant.now());
        switch (decision) {
            case VERIFIED -> claim(d);
            case NOT_FOUND -> {
                if (d.getStatus() != StudioAppDomain.Status.VERIFIED) {
                    d.setStatus(StudioAppDomain.Status.PENDING);
                }
                d.setLastError("txt_not_found");
            }
            case MISMATCH -> {
                if (d.getStatus() != StudioAppDomain.Status.VERIFIED) {
                    d.setStatus(StudioAppDomain.Status.FAILED);
                }
                d.setLastError("txt_mismatch");
            }
            case LOOKUP_ERROR -> d.setLastError("dns_lookup_failed");
        }
    }

    private void claim(StudioAppDomain d) {
        String schema = currentSchema();
        Optional<String> holder = tenantResolutionService.resolveTenantSchema(
            ExternalAccountProvider.STUDIO_APP_DOMAIN, d.getHostname());
        if (holder.isPresent() && !holder.get().equals(schema)) {
            d.setStatus(StudioAppDomain.Status.FAILED);
            d.setLastError("hostname_claimed");
            return;
        }
        tenantResolutionService.link(ExternalAccountProvider.STUDIO_APP_DOMAIN, d.getHostname(), schema);
        d.setStatus(StudioAppDomain.Status.VERIFIED);
        d.setLastError(null);
        if (d.getVerifiedAt() == null) {
            d.setVerifiedAt(Instant.now());
        }
    }

    @Transactional
    public void remove(UUID appId, UUID domainId) {
        requireApp(appId);
        StudioAppDomain d = domainRepository.findByIdAndAppId(domainId, appId)
            .orElseThrow(() -> new StudioAppService.StudioAppException("domain_not_found"));
        unlinkIfOurs(d);
        domainRepository.delete(d);
    }

    /** Frees every hostname of an app about to be deleted, so the registry does not keep them reserved. */
    @Transactional
    public void releaseAll(UUID appId) {
        for (StudioAppDomain d : domainRepository.findByAppIdOrderByCreatedAtAsc(appId)) {
            unlinkIfOurs(d);
        }
    }

    private void unlinkIfOurs(StudioAppDomain d) {
        if (d.getStatus() == StudioAppDomain.Status.VERIFIED) {
            Optional<String> holder = tenantResolutionService.resolveTenantSchema(
                ExternalAccountProvider.STUDIO_APP_DOMAIN, d.getHostname());
            if (holder.isPresent() && holder.get().equals(currentSchema())) {
                tenantResolutionService.unlink(ExternalAccountProvider.STUDIO_APP_DOMAIN, d.getHostname());
            }
        }
    }

    /**
     * Public: hostname -> slug of the LIVE app it serves, only for VERIFIED
     * hosts. Sets and always clears TenantContext itself.
     */
    public Optional<String> resolveSlugForHost(String rawHost) {
        String host;
        try {
            host = StudioAppDomainRules.normalizeHostname(rawHost);
        } catch (StudioAppDomainRules.InvalidHostnameException e) {
            return Optional.empty();
        }
        Optional<String> schema = tenantResolutionService.resolveTenantSchema(
            ExternalAccountProvider.STUDIO_APP_DOMAIN, host);
        if (schema.isEmpty()) {
            return Optional.empty();
        }
        TenantContext.set(TenantContext.of(schema.get(), "system:studio-app-domain:" + host, java.util.Set.of()));
        try {
            return domainRepository.findByHostname(host)
                .filter(d -> d.getStatus() == StudioAppDomain.Status.VERIFIED)
                .flatMap(d -> appRepository.findById(d.getAppId()))
                .filter(a -> a.getStatus() == StudioApp.Status.LIVE)
                .map(StudioApp::getSlug);
        } catch (RuntimeException e) {
            log.warn("studio-app host resolution failed for {}", host, e);
            return Optional.empty();
        } finally {
            TenantContext.clear();
        }
    }

    private void requireApp(UUID appId) {
        if (appRepository.findById(appId).isEmpty()) {
            throw new StudioAppService.StudioAppException("app_not_found");
        }
    }

    private static String currentSchema() {
        return TenantContext.current().map(TenantContext::getTenantId)
            .orElseThrow(() -> new IllegalStateException("StudioAppDomainService requires a tenant context"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
