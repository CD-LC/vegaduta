<#-- Plain-text counterpart of html/email-verification.ftl, sent as the
     multipart/alternative text part for mail clients that prefer/require
     plain text. Keep this in sync with the HTML version's copy. -->
<#if user.firstName?has_content>Hi ${user.firstName},<#else>Hi there,</#if>

Thanks for creating a VegaDūta account. Please confirm this is your email address to activate ${realmName!'VegaDūta'}:

${link}

This link expires in ${linkExpiration} minutes. If you didn't create a VegaDūta account, you can ignore this email.

VegaDūta
