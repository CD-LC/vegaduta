<#import "template.ftl" as layout>
<#import "password-commons.ftl" as passwordCommons>
<#-- "Update password" required action - after a reset link, or on first
     login when the realm/admin requires a new password. Field names
     (password-new, password-confirm, logout-sessions, cancel-aia) and the
     form id kc-passwd-update-form are Keycloak's own stock markup. -->
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('password','password-confirm'); section>
    <#if section = "eyebrow">
        ${msg("aaiEyebrowSecurity")}
    <#elseif section = "header">
        ${msg("updatePasswordTitle")}
    <#elseif section = "subtitle">
        ${msg("aaiUpdatePasswordSubtitle")}
    <#elseif section = "form">
        <form id="kc-passwd-update-form" action="${url.loginAction}" method="post">
            <#-- Hidden username / current-password hints so browser password
                 managers associate the new password with the right account
                 (stock Keycloak markup, not visible). -->
            <input type="text" id="username" name="username" value="${username!''}" autocomplete="username" readonly style="display:none;"/>
            <input type="password" id="password" name="password" autocomplete="current-password" style="display:none;"/>

            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="password-new" class="aai-label">${msg("passwordNew")}</label>
                <div class="aai-input-wrap">
                    <input type="password" id="password-new" name="password-new" class="${properties.kcInputClass!} aai-input" autofocus autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password','password-confirm')>true</#if>"
                           aria-describedby="<#if messagesPerField.existsError('password')>input-error-password</#if>"
                    />
                    <button class="aai-eye" type="button" aria-label="${msg('showPassword')}"
                            aria-controls="password-new" data-password-toggle
                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                    </button>
                </div>
                <#if messagesPerField.existsError('password')>
                    <span id="input-error-password" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password'))?no_esc}
                    </span>
                </#if>
            </div>

            <div class="${properties.kcFormGroupClass!} aai-field">
                <label for="password-confirm" class="aai-label">${msg("passwordConfirm")}</label>
                <div class="aai-input-wrap">
                    <input type="password" id="password-confirm" name="password-confirm" class="${properties.kcInputClass!} aai-input" autocomplete="new-password"
                           aria-invalid="<#if messagesPerField.existsError('password-confirm')>true</#if>"
                           aria-describedby="<#if messagesPerField.existsError('password-confirm')>input-error-password-confirm</#if>"
                    />
                    <button class="aai-eye" type="button" aria-label="${msg('showPassword')}"
                            aria-controls="password-confirm" data-password-toggle
                            data-icon-show="${properties.kcFormPasswordVisibilityIconShow!}" data-icon-hide="${properties.kcFormPasswordVisibilityIconHide!}"
                            data-label-show="${msg('showPassword')}" data-label-hide="${msg('hidePassword')}">
                        <i class="${properties.kcFormPasswordVisibilityIconShow!}" aria-hidden="true"></i>
                    </button>
                </div>
                <#if messagesPerField.existsError('password-confirm')>
                    <span id="input-error-password-confirm" class="${properties.kcInputErrorMessageClass!} aai-field-error" aria-live="polite">
                        ${kcSanitize(messagesPerField.get('password-confirm'))?no_esc}
                    </span>
                </#if>
            </div>

            <div class="aai-options">
                <@passwordCommons.logoutOtherSessions/>
            </div>

            <#if isAppInitiatedAction??>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!} aai-button-row">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" type="submit" value="${msg('aaiSavePassword')}"/>
                    <button class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-btn-secondary" type="submit" name="cancel-aia" value="true">${msg("doCancel")}</button>
                </div>
            <#else>
                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!} aai-submit" type="submit" value="${msg('aaiSavePassword')}"/>
                </div>
            </#if>
        </form>
        <script type="module" src="${url.resourcesPath}/js/passwordVisibility.js"></script>
    </#if>
</@layout.registrationLayout>
