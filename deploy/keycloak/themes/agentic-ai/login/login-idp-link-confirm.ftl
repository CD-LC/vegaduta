<#import "template.ftl" as layout>
<#-- "You already have an account": a social sign-in (Google ...) whose email
     already belongs to a password account. Keycloak's stock page offered
     "Review profile" + "Add to existing account"; Review Profile is DISABLED
     in this realm's First Broker Login flow (reconcile-keycloak.sh step 6), so
     that button only reset the flow straight back to this page - a loop.
     One real action (link, which then asks for the existing password or an
     email link) and a way out: restart sign-in with another account.
     submitAction=linkAccount / id linkAccount are Keycloak's own. -->
<@layout.registrationLayout displayMessage=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowLinkAccount")}
    <#elseif section = "header">
        ${msg("confirmLinkIdpTitle")}
    <#elseif section = "subtitle">
        <#-- Keycloak hands this page its text as the flow "error" message
             (federatedIdentityConfirmLinkMessage, {0}=attribute name, {1}=the
             address) - shown here as the subtitle instead of an alert box. -->
        <#if message?has_content>${kcSanitize(message.summary)?no_esc}</#if>
    <#elseif section = "form">
        <form id="kc-register-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" name="submitAction" id="linkAccount" value="linkAccount">${msg("confirmLinkIdpContinue", idpDisplayName)}</button>
            </div>
        </form>
        <p class="aai-copy">
            <a id="loginRestartLink" href="${url.loginRestartFlowUrl}" class="aai-link-sm">${msg("aaiUseAnotherAccount")}</a>
        </p>
    </#if>
</@layout.registrationLayout>
