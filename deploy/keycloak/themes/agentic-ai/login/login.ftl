<#import "template.ftl" as layout>
<#-- Sign-in page - the real form users see at the public auth URL.

     The trailing "; section" is required: template.ftl's macro calls
     <#nested "header">, <#nested "form"> etc with one positional value and
     "section" is what binds it (standard Keycloak base-theme pattern).
     displayWide is still passed because the macro declares it; the card is
     the same 28rem on every page now.

     Field ids/names and the form id are Keycloak's own (username, password,
     rememberMe, credentialId, kc-form-login, kc-login) - the e2e suites
     click them and Keycloak's flows post them. Only layout/classes are ours. -->
<@layout.registrationLayout displayInfo=social.displayInfo displayWide=(realm.password && social.providers??) displayMessage=!messagesPerField.existsError('username','password'); section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowSignIn")}
    <#elseif section = "header">
        ${msg("aaiWelcomeBack")}
    <#elseif section = "subtitle">
        ${msg("aaiWelcomeBackSubtitle")}
    <#elseif section = "form">
        <#-- Apple App Store Guideline 4.8 (the app-store compliance requirements (described inline)):
             an iOS app that offers third-party social login (Google, GitHub, Microsoft...)
             must also offer Sign in with Apple. Until an Apple identity provider is
             configured (Apple Services ID + key - a human step), the iOS app signs in
             through its own client, agentic-ai-ios, and this page shows it email/password
             only, which does not trigger 4.8. Every other client is unchanged. When Apple
             sign-in exists: drop this guard and list the "apple" provider first. -->
        <#assign aaiHideSocial = (client?? && client.clientId?? && client.clientId == "agentic-ai-ios")>
        <#if realm.password>
            <#if !aaiHideSocial && social.providers?? && social.providers?size gt 0>
                <div class="aai-social" id="kc-social-providers">
                    <#list social.providers as p>
                        <a id="social-${p.alias}" class="aai-social-btn" href="${p.loginUrl}">
                            <#-- Glyphs shipped for google / github / microsoft; anything
                                 else gets the neutral key mark. -->
                            <#if p.alias == "google" || p.alias == "github" || p.alias == "microsoft">
                            <span class="aai-social-glyph aai-social-glyph-${p.alias}" aria-hidden="true"></span>
                            <#else>
                            <span class="aai-social-glyph aai-social-glyph-generic" aria-hidden="true"></span>
                            </#if>
                            <span>${msg("aaiContinueWith", p.displayName!p.alias)}</span>
                        </a>
                    </#list>
                </div>
                <div class="aai-divider">${msg("aaiOrWithEmail")}</div>
            </#if>

            <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <#if !usernameHidden??>
                <div class="${properties.kcFormGroupClass!} aai-field">
                    <label for="username" class="aai-label">
                        <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                    </label>
                    <input id="username" class="${properties.kcInputClass!} aai-input" name="username" value="${(login.username!'')}" type="text" autofocus autocomplete="username" autocapitalize="none" spellcheck="false"
                           aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                           aria-describedby="<#if messagesPerField.existsError('username','password')>input-error</#if>"
                    />
                    <#if messagesPerField.existsError('username','password')>
                        <span id="input-error" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>
                </#if>

                <div class="${properties.kcFormGroupClass!} aai-field">
                    <div class="aai-label-row">
                        <label for="password" class="aai-label">${msg("password")}</label>
                        <#if realm.resetPasswordAllowed>
                            <a href="${url.loginResetCredentialsUrl}" class="aai-link-sm">${msg("doForgotPassword")}</a>
                        </#if>
                    </div>
                    <div class="aai-input-wrap">
                        <input id="password" class="${properties.kcInputClass!} aai-input" name="password" type="password" autocomplete="current-password"
                               aria-invalid="<#if messagesPerField.existsError('username','password')>true</#if>"
                               aria-describedby="<#if messagesPerField.existsError('username','password')>input-error</#if>"
                        />
                        <button class="aai-eye" type="button" aria-label="${msg("showPassword")}"
                                aria-controls="password" data-password-toggle
                                data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                                data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                            <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                        </button>
                    </div>
                    <#if usernameHidden?? && messagesPerField.existsError('username','password')>
                        <span id="input-error" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                            ${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}
                        </span>
                    </#if>
                </div>

                <#if realm.rememberMe && !usernameHidden??>
                    <div class="aai-options">
                        <div class="checkbox aai-check">
                            <label>
                                <#if login.rememberMe??>
                                    <input id="rememberMe" name="rememberMe" type="checkbox" checked> ${msg("rememberMe")}
                                <#else>
                                    <input id="rememberMe" name="rememberMe" type="checkbox"> ${msg("rememberMe")}
                                </#if>
                            </label>
                        </div>
                    </div>
                </#if>

                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" name="login" id="kc-login" type="submit" value="${msg('doLogIn')}"/>
                </div>
            </form>
            <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
        </#if>
    <#elseif section = "info" >
        <#-- Real tenant signup (org, plan, schema provisioning) happens in the
             app's own /signup flow, not Keycloak's native registration - so
             this links there, derived from the calling client's base URL so
             every deployment gets its own domain; theme.properties
             aaiAppUrl (PUBLIC_APP_URL) is only the fallback. -->
        <div id="kc-registration-container" class="aai-hint">
            <#if client?? && client.baseUrl?has_content>
            <span>${msg("aaiNoAccount")} <a href="${client.baseUrl?remove_ending("/")}/signup">${msg("aaiCreateAccount")}</a></span>
            <#else>
            <span>${msg("aaiNoAccount")} <a href="${properties.aaiAppUrl!""}/signup">${msg("aaiCreateAccount")}</a></span>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
