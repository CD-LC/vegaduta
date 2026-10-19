<#-- Plain-text counterpart of html/executeActions.ftl, sent as the
     multipart/alternative text part for mail clients that prefer/require
     plain text. Keep this in sync with the HTML version's copy. -->
<#if user.firstName?has_content>Hi ${user.firstName},<#else>Hi there,</#if>

Your ${realmName!'VegaDūta'} account needs one more step before you can continue. Use the link below to complete it:

${link}

This link expires in ${linkExpiration} minutes. If you weren't expecting this, please contact your administrator.

VegaDūta
