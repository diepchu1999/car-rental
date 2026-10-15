<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=true displayInfo=false; section>
    <#if section = "header">
        ${msg("carRentalPhoneTitle")}
    <#elseif section = "form">
        <form id="car-rental-phone-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="phone" class="${properties.kcLabelClass!}">${msg("carRentalPhoneLabel")}</label>
                <input id="phone" name="phone" type="tel" autocomplete="tel" maxlength="64" required autofocus
                       class="${properties.kcInputClass!}" />
            </div>
            <button type="submit" name="action" value="send"
                    class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!}">${msg("carRentalPhoneSend")}</button>
        </form>
    </#if>
</@layout.registrationLayout>
