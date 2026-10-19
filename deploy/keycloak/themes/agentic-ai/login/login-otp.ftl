<#import "template.ftl" as layout>
<#-- Two-factor (OTP) challenge - only reachable if the realm turns on an OTP
     required action or auth-flow step. Field name (otp), the credential
     radio list (selectedCredentialId) and the messagesPerField key ('totp')
     are Keycloak's stock login-otp contract. -->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('totp'); section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowTwoFactor")}
    <#elseif section = "header">
        ${msg("aaiOtpTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiOtpSubtitle")}
    <#elseif section = "form">
        <form id="kc-otp-login-form" action="${url.loginAction}" method="post">
            <#if otpLogin.userOtpCredentials?size gt 1>
                <div class="${properties.kcFormGroupClass!} aai-field aai-otp-list">
                    <#list otpLogin.userOtpCredentials as otpCredential>
                        <label class="aai-otp-item" for="kc-otp-credential-${otpCredential?index}">
                            <input id="kc-otp-credential-${otpCredential?index}" type="radio" name="selectedCredentialId" value="${otpCredential.id}" <#if otpCredential.id == otpLogin.selectedCredentialId>checked="checked"</#if>>
                            <span>${otpCredential.userLabel}</span>
                        </label>
                    </#list>
                </div>
            </#if>

            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="otp" class="aai-label">${msg("loginOtpOneTime")}</label>
                <input id="otp" name="otp" autocomplete="one-time-code" type="text" inputmode="numeric" class="${properties.kcInputClass!} aai-input aai-otp-input" autofocus
                       aria-invalid="<#if messagesPerField.existsError('totp')>true</#if>"
                       aria-describedby="<#if messagesPerField.existsError('totp')>input-error-otp-code</#if>"
                />
                <#if messagesPerField.existsError('totp')>
                    <span id="input-error-otp-code" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('totp'))?no_esc}
                    </span>
                </#if>
            </div>

            <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" name="login" id="kc-login" type="submit" value="${msg('aaiVerify')}"/>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
