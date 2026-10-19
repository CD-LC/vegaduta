<#import "template.ftl" as layout>
<#-- Shown after registration / email change when the realm requires email
     verification. No form - instructions and a resend link. displayMessage
     stays false: there is no field to attach a validation message to, and
     the resend action's own redirect still shows real errors. -->
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowVerifyEmail")}
    <#elseif section = "header">
        ${msg("aaiVerifyEmailTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiVerifyEmailSubtitle")}
    <#elseif section = "form">
        <div class="aai-copy">
            <#-- Stock key takes the address as {0}; without it the page renders a
                 literal "{0}". Guarded so a social-login account with no email
                 on file still gets a full sentence. -->
            <p class="instruction">${msg("emailVerifyInstruction1", (user.email)!msg("aaiYourEmailFallback"))}</p>
            <p class="instruction">
                ${msg("emailVerifyInstruction2")}
                <a href="${url.loginAction}">${msg("aaiResendVerificationEmail")}</a> ${msg("emailVerifyInstruction3")}
            </p>
        </div>
    </#if>
</@layout.registrationLayout>
