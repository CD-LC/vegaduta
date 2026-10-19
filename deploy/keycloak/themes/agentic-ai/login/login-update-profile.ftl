<#import "template.ftl" as layout>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#-- "Update profile" required action (first login via an identity broker, or
     an admin-triggered refresh). Fields come from the realm's User Profile
     config through Keycloak's own macro; login.css lays them out (first +
     last name share a row). Form id kc-update-profile-form and the
     cancel-aia button are stock. -->
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowProfile")}
    <#elseif section = "header">
        ${msg("aaiUpdateProfileTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiUpdateProfileSubtitle")}
    <#elseif section = "form">
        <form id="kc-update-profile-form" action="${url.loginAction}" method="post">
            <@userProfileCommons.userProfileFormFields/>

            <#if isAppInitiatedAction??>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!} aai-button-row">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" type="submit" value="${msg('aaiSaveChanges')}"/>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-btn-secondary" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
                </div>
            <#else>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" type="submit" value="${msg('aaiSaveChanges')}"/>
                </div>
            </#if>
        </form>
    </#if>
</@layout.registrationLayout>
