<#import "template.ftl" as layout>
<#-- Shown when a login / registration / reset page has gone stale (tab left
     open, double submit). Just the two stock "restart" / "continue" links
     (loginRestartFlowUrl, loginAction); ids loginRestartLink /
     loginContinueLink are Keycloak's own. -->
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowSession")}
    <#elseif section = "header">
        ${msg("pageExpiredTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiPageExpiredSubtitle")}
    <#elseif section = "form">
        <div class="aai-copy">
            <p id="instruction1" class="instruction">
                ${msg("pageExpiredMsg1")} <a id="loginRestartLink" href="${url.loginRestartFlowUrl}">${msg("aaiRestartLoginLink")}</a>.
            </p>
            <p id="instruction2" class="instruction">
                ${msg("pageExpiredMsg2")} <a id="loginContinueLink" href="${url.loginAction}">${msg("aaiContinueLoginLink")}</a>.
            </p>
        </div>
    </#if>
</@layout.registrationLayout>
