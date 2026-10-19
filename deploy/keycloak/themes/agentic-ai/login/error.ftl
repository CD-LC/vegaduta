<#import "template.ftl" as layout>
<#-- Keycloak's generic hard-error page (session not found, internal server
     error, invalid client ...). message.summary comes from Keycloak; only the
     shell/typography is ours. displayMessage=false: this page IS the message. -->
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowError")}
    <#elseif section = "header">
        ${msg("aaiErrorTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiErrorSubtitle")}
    <#elseif section = "form">
        <div id="kc-error-message" class="aai-copy">
            <p class="instruction">${kcSanitize(message.summary)?no_esc}</p>

            <#if skipLink??>
            <#else>
                <#if client?? && client.baseUrl?has_content>
                    <p><a id="backToApplication" href="${client.baseUrl}">${kcSanitize(msg("backToApplication"))?no_esc}</a></p>
                <#else>
                    <#-- Same dead-end fallback as info.ftl: with no client.baseUrl
                         (the realm sets none) an error page ended at the message
                         with no way forward. -->
                    <div class="aai-button-row">
                        <a class="aai-submit" href="${properties.aaiAppUrl!""}/login">${msg("doLogIn")}</a>
                        <a class="aai-btn-secondary" href="${properties.aaiAppUrl!""}/signup">${msg("aaiCreateAccount")}</a>
                    </div>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
