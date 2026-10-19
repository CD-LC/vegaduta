<#import "template.ftl" as layout>
<#-- Forgot password: one field, one button. The stock "info" paragraph
     (emailInstruction) is not rendered - the subtitle already says what will
     happen, and the base theme's copy repeated it under the button. Field
     name/id (username) and form id kc-reset-password-form are Keycloak's
     own. -->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username') displayInfo=false; section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowReset")}
    <#elseif section = "header">
        ${msg("aaiForgotPasswordTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiForgotPasswordSubtitle")}
    <#elseif section = "form">
        <form id="kc-reset-password-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="username" class="aai-label">
                    <#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if>
                </label>
                <input type="text" id="username" name="username" class="${properties.kcInputClass!} aai-input" autofocus autocomplete="username" autocapitalize="none" spellcheck="false"
                       value="${(auth.attemptedUsername!'')}"
                       aria-invalid="<#if messagesPerField.existsError('username')>true</#if>"
                       aria-describedby="<#if messagesPerField.existsError('username')>input-error-username</#if>"
                />
                <#if messagesPerField.existsError('username')>
                    <span id="input-error-username" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('username'))?no_esc}
                    </span>
                </#if>
            </div>

            <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit"
                       type="submit" value="${msg('aaiSendResetLink')}"/>
            </div>

            <div class="aai-hint">
                <a href="${url.loginUrl}">${msg("aaiBackToSignIn")}</a>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
