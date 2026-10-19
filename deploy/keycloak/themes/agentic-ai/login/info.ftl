<#import "template.ftl" as layout>
<#-- Generic informational page ("you are already logged in", required-action
     summary, registration disabled ...). messageHeader / message.summary /
     requiredActions come from the calling flow; only the shell is ours. -->
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowInfo")}
    <#elseif section = "header">
        <#if messageHeader??>
            ${kcSanitize(msg("${messageHeader}"))?no_esc}
        <#else>
            ${message.summary}
        </#if>
    <#elseif section = "form">
        <div id="kc-info-message" class="aai-copy">
            <p class="instruction">
                ${message.summary}
                <#if requiredActions??>
                    <#list requiredActions>
                        : <b><#items as reqActionItem>${kcSanitize(msg("requiredAction.${reqActionItem}"))?no_esc}<#sep>, </#sep></#items></b>
                    </#list>
                </#if>
            </p>

            <#if skipLink??>
            <#else>
                <#if pageRedirectUri?has_content>
                    <p><a href="${pageRedirectUri}">${kcSanitize(msg("backToApplication"))?no_esc}</a></p>
                <#elseif actionUri?has_content>
                    <p><a href="${actionUri}">${kcSanitize(msg("proceedWithAction"))?no_esc}</a></p>
                <#elseif (client.baseUrl)?has_content>
                    <p><a href="${client.baseUrl}">${kcSanitize(msg("backToApplication"))?no_esc}</a></p>
                <#else>
                    <#-- Dead-end fallback: bare link flows (the email-verification
                         "Your email address has been verified" landing is the common
                         one) arrive with no client context at all - no pageRedirectUri,
                         no actionUri, and no client with a baseUrl (the realm sets
                         none). Every branch above then renders nothing and the page
                         ends at the message. Give the reader the two ways forward:
                         the app's own /login and /signup, same URLs login.ftl falls
                         back to. -->
                    <div class="aai-button-row">
                        <a class="aai-submit" href="${properties.aaiAppUrl!""}/login">${msg("doLogIn")}</a>
                        <a class="aai-btn-secondary" href="${properties.aaiAppUrl!""}/signup">${msg("aaiCreateAccount")}</a>
                    </div>
                </#if>
            </#if>
        </div>
    </#if>
</@layout.registrationLayout>
