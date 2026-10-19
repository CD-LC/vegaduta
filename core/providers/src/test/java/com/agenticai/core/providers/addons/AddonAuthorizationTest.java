package com.agenticai.core.providers.addons;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The role rules really run: the controller is proxied by Spring's method security (as in core) and
 * called as different callers. The MockMvc tests bypass that proxy, so without this a typo in a
 * {@code @PreAuthorize} expression would pass them all.
 */
class AddonAuthorizationTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (context != null) {
            context.close();
        }
    }

    private AddonController controller(MockEnvironment env) {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(AddonService.class, () -> {
            AddonService service = mock(AddonService.class);
            return service;
        });
        context.registerBean("addonAccess", AddonAccess.class, () -> new AddonAccess(env));
        context.registerBean(AddonHostAuth.class, () -> new AddonHostAuth(AddonKit.TOKEN));
        context.registerBean(MethodSecurityConfig.class);
        context.registerBean(AddonController.class);
        context.refresh();
        return context.getBean(AddonController.class);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfig {}

    private static Authentication as(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("user-1", "n/a", authorities);
        token.setAuthenticated(true);
        return token;
    }

    private void signIn(Authentication a) {
        SecurityContextHolder.getContext().setAuthentication(a);
    }

    private static boolean mayChange(AddonController c, Authentication a) {
        try {
            c.enable("search", a);
            return true;
        } catch (AccessDeniedException e) {
            return false;
        }
    }

    @Test
    void anyone_signed_in_may_read_and_nobody_else() {
        AddonController c = controller(new MockEnvironment());
        signIn(as("ROLE_user"));
        assertDoesNotThrow(c::list);
        assertDoesNotThrow(() -> c.get("search"));
        SecurityContextHolder.clearContext();
        assertThrows(Exception.class, c::list, "no authentication at all");
        assertThrows(Exception.class, () -> c.get("search"));
    }

    @Test
    void aPlainMemberCannotChangeAnything() {
        AddonController c = controller(new MockEnvironment().withProperty("app.auth.mode", "local"));
        Authentication member = as("ROLE_user", "ROLE_agent-builder", "PERM_MANAGE_TOOLS");
        signIn(member);
        assertFalse(mayChange(c, member));
        assertThrows(AccessDeniedException.class, () -> c.disable("search", false, member));
    }

    @Test
    void onAOneOwnerInstallTheTenantAdminChangesIt() {
        AddonController c = controller(new MockEnvironment().withProperty("app.auth.mode", "local"));
        Authentication owner = as("ROLE_tenant-admin");
        signIn(owner);
        assertTrue(mayChange(c, owner));
        assertDoesNotThrow(() -> c.disable("search", true, owner));
    }

    @Test
    void onASharedInstallOnlyAPlatformAdminChangesIt() {
        AddonController c = controller(new MockEnvironment().withProperty("app.auth.mode", "keycloak"));
        Authentication tenantAdmin = as("ROLE_tenant-admin");
        signIn(tenantAdmin);
        assertFalse(mayChange(c, tenantAdmin));
        Authentication platformAdmin = as("ROLE_platform-admin");
        signIn(platformAdmin);
        assertTrue(mayChange(c, platformAdmin));
    }

    @Test
    void theDefaultIsASharedInstallSoNothingIsOpenedByForgettingAProperty() {
        AddonController c = controller(new MockEnvironment());
        Authentication tenantAdmin = as("ROLE_tenant-admin");
        signIn(tenantAdmin);
        assertFalse(mayChange(c, tenantAdmin));
    }

    @Test
    void anExplicitSettingOverridesTheAuthMode() {
        AddonController yes = controller(new MockEnvironment().withProperty("app.addons.tenant-admin-may-change", "true"));
        Authentication tenantAdmin = as("ROLE_tenant-admin");
        signIn(tenantAdmin);
        assertTrue(mayChange(yes, tenantAdmin));
        context.close();
        AddonController no = controller(new MockEnvironment()
                .withProperty("app.auth.mode", "local")
                .withProperty("app.addons.tenant-admin-may-change", "false"));
        signIn(tenantAdmin);
        assertFalse(mayChange(no, tenantAdmin));
    }

    @Test
    void anUnauthenticatedCallerIsNeverAnAdmin() {
        AddonAccess access = new AddonAccess(new MockEnvironment().withProperty("app.auth.mode", "local"));
        assertFalse(access.canChange(null));
        TestingAuthenticationToken notAuthenticated = new TestingAuthenticationToken("x", "n/a", "ROLE_platform-admin");
        notAuthenticated.setAuthenticated(false);
        assertFalse(access.canChange(notAuthenticated));
    }

    @Test
    void hostHandlersNeedTheTokenNotARoleAndFailClosedWithoutIt() {
        AddonController c = controller(new MockEnvironment());
        // method security lets them through; the handler itself refuses
        AddonException e = assertThrows(AddonException.class, () -> c.hostWanted(null));
        assertEquals("unauthorized", e.code());
        Authentication platformAdmin = as("ROLE_platform-admin");
        signIn(platformAdmin);
        assertEquals("unauthorized", assertThrows(AddonException.class, () -> c.hostWanted(null)).code(),
                "a platform admin's session is not the installer");
        assertEquals("unauthorized", assertThrows(AddonException.class, () -> c.hostReport("Bearer nope", null)).code());
    }

    @Test
    void everyHandlerCarriesAnExplicitPreAuthorizeAndChangesNeedTheAccessRule() {
        List<String> missing = new ArrayList<>();
        for (Method m : AddonController.class.getDeclaredMethods()) {
            if (AnnotatedElementUtils.findMergedAnnotation(m, org.springframework.web.bind.annotation.GetMapping.class) == null
                    && AnnotatedElementUtils.findMergedAnnotation(m, org.springframework.web.bind.annotation.PostMapping.class) == null) {
                continue;
            }
            PreAuthorize rule = m.getAnnotation(PreAuthorize.class);
            if (rule == null) {
                missing.add(m.getName());
                continue;
            }
            boolean post = AnnotatedElementUtils.findMergedAnnotation(m, org.springframework.web.bind.annotation.PostMapping.class) != null;
            String[] paths = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class).path();
            String path = paths.length == 0 ? "" : paths[0];
            boolean host = path.startsWith("/host/");
            if (post && !host) {
                assertTrue(rule.value().contains("@addonAccess.canChange"), m.getName() + " must use the access rule");
            }
            if (!post && !host) {
                assertEquals("isAuthenticated()", rule.value(), m.getName());
            }
            if (host) {
                assertEquals("permitAll()", rule.value(), m.getName() + ": the shared secret is checked in the handler");
            }
        }
        assertTrue(missing.isEmpty(), "handlers without @PreAuthorize: " + missing);
    }
}
